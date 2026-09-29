package app.marmalade.tts.service

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import app.marmalade.tts.audio.StreamingEffectChain
import app.marmalade.tts.playback.AudioOutput
import app.marmalade.tts.playback.ChunkTag
import app.marmalade.tts.playback.OutputCommand
import app.marmalade.tts.playback.OutputEvent
import app.marmalade.tts.playback.Track
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Narrator's audio sink on the device for step C1: today's per-block
 * playback mechanics, now under the Narrator's control. Each block (article
 * paragraph, shared-text paragraph) still gets its own AudioTrack and its own
 * effect chain — built on its first chunk, drained to its last frame and
 * released before the next block's track is built — so a paragraph boundary
 * sounds exactly as before. C2 replaces this with one continuous track.
 *
 * What is already the C2 shape:
 *  - one writer thread per [Track] (Main, Interrupt) is the ONLY caller of
 *    its AudioTracks; [submit] never blocks, and commands apply between
 *    ~50 ms slices in submission order;
 *  - stream items of a flushed epoch are dropped ([OutputCommand.Flush]);
 *  - writes are non-blocking: a track the OS parked (data queued, head frozen
 *    while playing — Android 16's audio hardening) is reported as
 *    [OutputEvent.TrackFailed] instead of blocking the writer for ever;
 *  - chunk marks are read off the playback head AFTER the effect chain, so
 *    [OutputEvent.ChunkStarted] means "heard" (the highlight, Back's window).
 *
 * The live session speed is the chain's Tempo stage, set per slice.
 */
@Singleton
class BlockTrackOutput @Inject constructor(
    private val wakeLock: NarratorWakeLock,
) : AudioOutput {

    @Volatile private var sink: (OutputEvent) -> Unit = {}

    private val writers = Track.entries.associateWith { TrackWriter(it) }

    override fun attach(sink: (OutputEvent) -> Unit) {
        this.sink = sink
    }

    override fun submit(command: OutputCommand) {
        writers.getValue(command.track).post(command)
    }

    private class Mark(val frame: Long, val event: OutputEvent)

    private enum class Write { Done, Interrupted, Failed }

    private inner class TrackWriter(private val track: Track) {
        private val inbox = LinkedBlockingQueue<OutputCommand>()

        private val thread: Thread by lazy {
            Thread(::loop, "narrator-writer-${track.name.lowercase()}").apply {
                isDaemon = true
                start()
            }
        }

        fun post(command: OutputCommand) {
            inbox.put(command)
            thread // started on first use
        }

        // -- writer-thread state ------------------------------------------------

        private var playing = false
        private val queue = ArrayDeque<OutputCommand>()
        private val minEpoch = HashMap<Long, Int>()
        private val tempos = HashMap<Long, Float>()

        /** The session whose stream items are being written. */
        private var session: OutputCommand.Begin? = null

        /** The current block's track, chain and position. */
        private var audio: AudioTrack? = null
        private var block: Pair<Long, Int>? = null
        private var chain: StreamingEffectChain? = null
        private var written = 0L
        private val marks = ArrayDeque<Mark>()

        /** Where the head item (a Write) resumes after a pause. */
        private var offset = 0

        /** Bumped by every flush/release: a nested wait that sees it change gives up. */
        private var generation = 0

        private fun loop() {
            while (true) {
                try {
                    val busy = playing && queue.isNotEmpty()
                    applyInbox(if (busy) 0L else IDLE_POLL_MS)
                    fireMarks()
                    if (playing && queue.isNotEmpty()) feedHead()
                } catch (t: Throwable) {
                    // Never let the writer die: the Narrator would wait for
                    // events that can't come. Fail what was playing and go on.
                    Log.e(TAG, "$track writer error", t)
                    failSession(t.message ?: t.javaClass.simpleName)
                }
            }
        }

        private fun applyInbox(waitMs: Long) {
            var c = if (waitMs > 0) inbox.poll(waitMs, TimeUnit.MILLISECONDS) else inbox.poll()
            while (c != null) {
                apply(c)
                c = inbox.poll()
            }
        }

        private fun apply(c: OutputCommand) {
            when (c) {
                is OutputCommand.Begin, is OutputCommand.Write, is OutputCommand.End -> {
                    if (isStale(c)) return
                    // A Begin states the session's speed as of its submission;
                    // SetTempos submitted after it win (applied on arrival).
                    if (c is OutputCommand.Begin) tempos[c.sessionId] = c.tempo
                    queue.addLast(c)
                }
                is OutputCommand.Play -> {
                    playing = true
                    audio?.play()
                }
                is OutputCommand.Pause -> {
                    playing = false
                    audio?.pause()
                }
                is OutputCommand.Flush -> {
                    minEpoch[c.sessionId] = maxOf(minEpoch[c.sessionId] ?: 0, c.minEpoch)
                    clear()
                }
                is OutputCommand.SetTempo -> {
                    tempos[c.sessionId] = c.tempo
                    if (session?.sessionId == c.sessionId) chain?.setLiveTempo(c.tempo)
                }
                is OutputCommand.Release -> {
                    clear()
                    playing = false
                }
            }
        }

        private fun isStale(c: OutputCommand): Boolean {
            val (id, epoch) = when (c) {
                is OutputCommand.Begin -> c.sessionId to c.epoch
                is OutputCommand.Write -> c.tag.sessionId to c.tag.epoch
                is OutputCommand.End -> c.sessionId to c.epoch
                else -> return false
            }
            return epoch < (minEpoch[id] ?: 0)
        }

        private fun clear() {
            queue.clear()
            releaseTrack()
            marks.clear()
            session = null
            offset = 0
            generation++
        }

        private fun feedHead() {
            when (val item = queue.first()) {
                is OutputCommand.Begin -> {
                    queue.removeFirst()
                    session = item
                }
                is OutputCommand.Write -> writeChunk(item)
                is OutputCommand.End -> {
                    val gen = generation
                    if (!finishBlock()) return
                    if (gen != generation) return
                    queue.removeFirst()
                    session = null
                    emit(OutputEvent.SessionEnded(track, item.sessionId, item.epoch))
                }
                else -> queue.removeFirst()
            }
        }

        private fun writeChunk(item: OutputCommand.Write) {
            val gen = generation
            val key = item.tag.sessionId to item.tag.segment
            if (block != key) {
                // A new block: today's mechanics — drain and release the last
                // block's track, then build this one's.
                if (!finishBlock() || gen != generation) return
                val begin = session
                if (begin == null || begin.sessionId != item.tag.sessionId) {
                    Log.w(TAG, "$track: chunk ${item.tag} without its session's Begin; dropped")
                    queue.removeFirst()
                    return
                }
                if (!openBlock(begin, key)) return
            }
            val out = chain!!
            val rate = session!!.sampleRate
            val pcm = item.pcm
            if (offset == 0) marks.addLast(Mark(written, OutputEvent.ChunkStarted(track, item.tag)))
            val slice = (rate / SLICES_PER_SECOND).coerceAtLeast(1)
            while (offset < pcm.size) {
                val to = minOf(offset + slice, pcm.size)
                val shaped = out.process(pcm.copyOfRange(offset, to))
                when (writeAll(shaped)) {
                    Write.Done -> offset = to
                    Write.Interrupted -> return
                    Write.Failed -> {
                        failSession("AudioTrack stalled or failed at ${item.tag}")
                        return
                    }
                }
                // A pause parks the writer inside writeAll; everything after
                // it (a flush) is checked there.
                if (gen != generation) return
            }
            marks.addLast(Mark(written, OutputEvent.ChunkFinished(track, item.tag)))
            queue.removeFirst()
            offset = 0
        }

        /** Build the block's track and chain; false (and the session failed) if the device refuses the format. */
        private fun openBlock(begin: OutputCommand.Begin, key: Pair<Long, Int>): Boolean {
            val t = try {
                buildTrack(begin.sampleRate)
            } catch (e: Exception) {
                Log.e(TAG, "$track: no AudioTrack at ${begin.sampleRate} Hz", e)
                failSession("AudioTrack rejected ${begin.sampleRate} Hz mono PCM16")
                return false
            }
            audio = t
            block = key
            written = 0
            chain = StreamingEffectChain(
                begin.effectBlocks,
                begin.sampleRate,
                liveTempo = tempos[begin.sessionId] ?: begin.tempo,
            )
            if (playing) t.play()
            return true
        }

        /**
         * Write [pcm] without ever blocking: commands keep applying while the
         * track's buffer is full, a pause parks here, a flush interrupts. A
         * head frozen for [STALL_MS] while playing with data queued is a track
         * the OS parked.
         */
        private fun writeAll(pcm: ShortArray): Write {
            val gen = generation
            var at = 0
            var lastHead = head()
            var lastProgress = SystemClock.elapsedRealtime()
            while (at < pcm.size) {
                applyInbox(0)
                if (gen != generation) return Write.Interrupted
                fireMarks()
                val t = audio ?: return Write.Interrupted
                if (!playing) {
                    applyInbox(PAUSED_POLL_MS)
                    lastProgress = SystemClock.elapsedRealtime()
                    continue
                }
                val n = t.write(pcm, at, pcm.size - at, AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) {
                    Log.w(TAG, "$track: AudioTrack.write returned $n")
                    return Write.Failed
                }
                if (n > 0) {
                    at += n
                    written += n
                    continue
                }
                val h = head()
                val now = SystemClock.elapsedRealtime()
                if (h != lastHead) {
                    lastHead = h
                    lastProgress = now
                } else if (now - lastProgress >= STALL_MS) {
                    Log.w(TAG, "$track: playback head parked at $h with a full buffer")
                    return Write.Failed
                }
                applyInbox(FULL_POLL_MS)
            }
            return Write.Done
        }

        /**
         * End the current block: write the chain's tail (Tempo's last frames,
         * a reverb ring-out), wait for the head to reach the last frame, then
         * release the track. A head that stops short while playing is given up
         * on after [STALL_MS] and the block counts as heard — today's drain
         * rule. False when a flush interrupted it.
         */
        private fun finishBlock(): Boolean {
            val t = audio ?: return true
            val gen = generation
            val tail = chain?.flush()
            chain = null
            if (tail != null && tail.isNotEmpty()) {
                when (writeAll(tail)) {
                    Write.Done -> Unit
                    Write.Interrupted -> return false
                    Write.Failed -> Log.w(TAG, "$track: chain tail not written")
                }
            }
            var lastHead = -1L
            var lastProgress = SystemClock.elapsedRealtime()
            while (true) {
                applyInbox(DRAIN_POLL_MS)
                if (gen != generation || audio !== t) return false
                fireMarks()
                val h = head()
                if (h >= written) break
                val now = SystemClock.elapsedRealtime()
                if (!playing || h != lastHead) {
                    lastHead = h
                    lastProgress = now
                } else if (now - lastProgress >= STALL_MS) {
                    Log.w(TAG, "$track: playback head stalled at $h/$written; abandoning drain")
                    break
                }
            }
            fireMarks(all = true)
            releaseTrack()
            return true
        }

        private fun head(): Long = (audio?.playbackHeadPosition ?: 0).toLong() and 0xFFFF_FFFFL

        /** Report every mark the head has passed (or all of them). */
        private fun fireMarks(all: Boolean = false) {
            if (marks.isEmpty()) return
            val h = head()
            while (marks.isNotEmpty()) {
                val m = marks.first()
                val due = all || when (m.event) {
                    is OutputEvent.ChunkStarted -> h > m.frame
                    else -> h >= m.frame
                }
                if (!due) return
                marks.removeFirst()
                emit(m.event)
            }
        }

        private fun emit(event: OutputEvent) {
            sink(event)
            if (event is OutputEvent.ChunkStarted) wakeLock.onChunk()
        }

        /** Give up on the current session's audio on this track; the Narrator fails it. */
        private fun failSession(message: String) {
            val tag: ChunkTag? = (queue.firstOrNull() as? OutputCommand.Write)?.tag
            val s = session
            val sessionId = tag?.sessionId ?: s?.sessionId
            val epoch = tag?.epoch ?: s?.epoch
            clear()
            if (sessionId != null && epoch != null) {
                emit(OutputEvent.TrackFailed(track, sessionId, epoch, message))
            }
        }

        private fun releaseTrack() {
            audio?.let { t ->
                try {
                    t.pause()
                    t.flush()
                    t.stop()
                } catch (_: IllegalStateException) { /* already stopped */ }
                t.release()
            }
            audio = null
            block = null
            chain = null
            written = 0
        }
    }

    companion object {
        private const val TAG = "BlockTrackOutput"

        /** Input slices per second of audio: 50 ms, so a speed change is heard within the track's buffer. */
        private const val SLICES_PER_SECOND = 20

        private const val IDLE_POLL_MS = 20L
        private const val PAUSED_POLL_MS = 20L
        private const val FULL_POLL_MS = 10L
        private const val DRAIN_POLL_MS = 10L

        /** Zero head progress for this long while playing means the track is dead (Android 16 parks muted tracks). */
        private const val STALL_MS = 2_500L

        /**
         * Build a track at [sampleRate]. Buffer aims for ~250 ms of headroom —
         * small enough that pause/stop feel responsive, large enough that the
         * writer isn't constantly waiting on a full buffer.
         */
        private fun buildTrack(sampleRate: Int): AudioTrack {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(quarterSecondBufferBytes(sampleRate))
            return AudioTrack.Builder()
                .setAudioAttributes(AudioFocusPort.SPEECH_ATTRIBUTES)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }

        /**
         * ~250 ms of 16-bit mono PCM, in bytes, as a whole number of frames.
         * AudioTrack rejects a buffer size that isn't a multiple of the frame
         * size ("Invalid audio buffer size"), and `sampleRate * 2 / 4` is odd
         * at 22 050 Hz — the rate of the medium/high VITS packs.
         */
        internal fun quarterSecondBufferBytes(sampleRate: Int): Int =
            (sampleRate / 4) * BYTES_PER_FRAME_PCM16_MONO

        private const val BYTES_PER_FRAME_PCM16_MONO = 2
    }
}
