package app.marmalade.tts.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The harness's writer (§5.1): the device's own [TrackWriter] — one per
 * [Track], the real [app.marmalade.tts.audio.StreamingEffectChain] inside —
 * over [VirtualTrack]s played on the test scheduler's virtual clock in
 * [sliceMs] ticks. Only the AudioTrack is virtual.
 *
 * Each tick: queued commands apply (between slices, like the device's writer
 * thread), the writers step until they wait, every playing track plays one
 * tick of audio, and the writers step again (marks fire, buffers refill).
 *
 * The virtual track models what the writer must cope with on a device:
 *  - a small buffer ([bufferMs]) that refuses writes when full;
 *  - a mixer that plays whole periods only — a track holding less than one
 *    tick of audio stays silent until more arrives (the writer must pad the
 *    tail through);
 *  - `flush()` ignored while playing, as AudioTrack does — so a writer that
 *    forgot to pause first would leave stale audio audible, and the harness
 *    would see it;
 *  - the head resets to 0 on flush.
 *
 * Chunk identity travels as SIDE-BAND tags on every written frame run, so
 * "heard" and "stale" are measured after the chain, frame-exact. PCM values
 * are never used as tags (Tempo would mangle them).
 */
class VirtualAudioOutput(
    scope: CoroutineScope,
    private val clock: () -> Long,
    private val sliceMs: Long = 10,
    private val bufferMs: Int = 30,
    stallMs: Long = TrackWriter.DEFAULT_STALL_MS,
) : AudioOutput {

    /** One audible slice of one track: what the listener hears in [atMs, atMs + sliceMs). */
    data class Heard(
        val atMs: Long,
        val track: Track,
        val tag: ChunkTag?,
        val audibleMs: Double,
        val silentMs: Double,
        /** A heard frame's epoch is below its session's flushed minimum: must never happen. */
        val stale: Boolean,
    )

    val timeline = ArrayList<Heard>()
    val events = ArrayList<Pair<Long, OutputEvent>>()
    val commands = ArrayList<Pair<Long, OutputCommand>>()

    /** Every track built, with the time and rate. */
    val opens = ArrayList<Triple<Long, Track, Int>>()

    /** Track rebuilds on a sample-rate change (a build at another rate than the track's last), with the time. */
    val rebuilds = ArrayList<Pair<Long, Int>>()

    /** Stream items the writer drops because their epoch was below the flushed minimum. */
    var staleDropped = 0
        private set

    /** `flush()` calls on a playing track — AudioTrack ignores them. */
    var illegalFlushes = 0
        private set

    /** The writer's own log lines. */
    val writerLog = ArrayList<String>()

    private var sink: (OutputEvent) -> Unit = {}
    private val pending = ArrayDeque<OutputCommand>()
    private val minEpoch = HashMap<Track, HashMap<Long, Int>>()
    private val live = HashMap<Track, VirtualTrack>()
    private val lastRate = HashMap<Track, Int>()

    /** Set to make the next track built on [Track] park (never play) — a track the OS muted. */
    var parkNext: Track? = null

    private val writers: Map<Track, TrackWriter> = Track.entries.associateWith { track ->
        TrackWriter(
            track = track,
            factory = { rate -> open(track, rate) },
            clock = clock,
            emit = { e ->
                events += clock() to e
                sink(e)
            },
            sliceMs = sliceMs.toInt(),
            stallMs = stallMs,
            log = { writerLog += "${clock()} $it" },
        )
    }

    init {
        scope.launch {
            while (isActive) {
                tick()
                delay(sliceMs)
            }
        }
    }

    override fun attach(sink: (OutputEvent) -> Unit) {
        this.sink = sink
    }

    override fun submit(command: OutputCommand) {
        commands += clock() to command
        pending.addLast(command)
    }

    /** Deliver an event as if the writer had reported it (stale-event scenarios). */
    fun inject(event: OutputEvent) = sink(event)

    fun trackOpen(track: Track): Boolean = writers.getValue(track).trackOpen

    private fun tick() {
        while (pending.isNotEmpty()) apply(pending.removeFirst())
        stepAll()
        for (track in Track.entries) {
            val t = live[track] ?: continue
            if (t.playing) timeline += t.play(sliceMs)
        }
        stepAll()
    }

    private fun apply(c: OutputCommand) {
        val epochs = minEpoch.getOrPut(c.track) { HashMap() }
        when (c) {
            is OutputCommand.Flush -> epochs[c.sessionId] = maxOf(epochs[c.sessionId] ?: 0, c.minEpoch)
            is OutputCommand.Begin, is OutputCommand.Write, is OutputCommand.End -> {
                val (id, epoch) = when (c) {
                    is OutputCommand.Begin -> c.sessionId to c.epoch
                    is OutputCommand.Write -> c.tag.sessionId to c.tag.epoch
                    is OutputCommand.End -> c.sessionId to c.epoch
                    else -> error("unreachable")
                }
                if (epoch < (epochs[id] ?: 0)) staleDropped++
            }
            else -> Unit
        }
        writers.getValue(c.track).apply(c)
    }

    private fun stepAll() {
        for (w in writers.values) {
            var n = 0
            while (w.step() == 0L) check(++n < 100_000) { "writer never waits" }
        }
    }

    private fun open(track: Track, rate: Int): PcmTrack {
        val prev = lastRate[track]
        if (prev != null && prev != rate) rebuilds += clock() to rate
        lastRate[track] = rate
        opens += Triple(clock(), track, rate)
        val parked = parkNext == track
        if (parked) parkNext = null
        return VirtualTrack(track, rate, parked).also { live[track] = it }
    }

    /** One virtual AudioTrack. Frames are kept as runs, each with the tag the writer gave it. */
    inner class VirtualTrack(
        private val track: Track,
        override val sampleRate: Int,
        private val parked: Boolean,
    ) : PcmTrack {
        private inner class Run(var frames: Int, val tag: ChunkTag?)

        override val capacityFrames: Int = sampleRate * bufferMs / 1000
        private val runs = ArrayDeque<Run>()
        private var queued = 0
        private var head = 0L
        var playing = false
            private set

        /** The last real tag heard, so a silent slice still names what was on air. */
        private var audible: ChunkTag? = null

        override fun write(pcm: ShortArray, offset: Int, size: Int, tag: ChunkTag?): Int {
            val n = minOf(size, capacityFrames - queued)
            if (n <= 0) return 0
            runs.addLast(Run(n, tag))
            queued += n
            return n
        }

        override fun head(): Long = head

        override fun play() {
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun flush() {
            if (playing) {
                illegalFlushes++
                return
            }
            runs.clear()
            queued = 0
            head = 0
        }

        override fun release() {
            playing = false
            runs.clear()
            queued = 0
            if (live[track] === this) live.remove(track)
        }

        /** Play [ms] of audio: whole periods only, nothing at all when parked. */
        fun play(ms: Long): Heard {
            val period = (sampleRate * ms / 1000).toInt()
            val take = if (parked || queued < period) 0 else period
            var left = take
            var real = 0
            var stale = false
            val epochs = minEpoch[track].orEmpty()
            while (left > 0) {
                val r = runs.first()
                val n = minOf(left, r.frames)
                r.tag?.let { tag ->
                    real += n
                    audible = tag
                    if (tag.epoch < (epochs[tag.sessionId] ?: 0)) stale = true
                }
                r.frames -= n
                left -= n
                if (r.frames == 0) runs.removeFirst()
            }
            queued -= take
            head += take
            val audibleMs = real * 1000.0 / sampleRate
            return Heard(clock(), track, audible, audibleMs, ms - audibleMs, stale)
        }
    }
}
