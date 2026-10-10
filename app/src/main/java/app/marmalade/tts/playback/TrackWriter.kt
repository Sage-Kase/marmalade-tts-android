package app.marmalade.tts.playback

import app.marmalade.tts.audio.StreamingEffectChain

/**
 * One output track as [TrackWriter] sees it: Android's AudioTrack on the
 * device (`service/AudioTrackOutput`), a virtual track in the tests. Only the
 * writer calls it, from one thread.
 */
interface PcmTrack {
    val sampleRate: Int

    /** Frames the track holds when full. */
    val capacityFrames: Int

    /**
     * Non-blocking: the frames accepted (0 when the buffer is full), negative
     * on a device error. [tag] says whose audio these frames are (null:
     * silence the writer pads with); the device ignores it, the virtual track
     * uses it to check that no stale audio is ever heard.
     */
    fun write(pcm: ShortArray, offset: Int, size: Int, tag: ChunkTag?): Int

    /** Frames played since the track was built or last flushed (64-bit, never wraps). */
    fun head(): Long

    fun play()
    fun pause()

    /** Drop every queued frame and reset [head] to 0. The writer only calls it while paused (AudioTrack ignores it otherwise). */
    fun flush()

    fun release()
}

/** Builds a [PcmTrack]; throws when the device refuses the format. */
fun interface PcmTrackFactory {
    fun open(sampleRate: Int): PcmTrack
}

/**
 * The writer for one [Track] (step C2): **the only caller of that track's
 * [PcmTrack]**. A flat state machine driven by a runner — the device's writer
 * thread, or the harness's virtual clock — that hands it commands ([apply])
 * and calls [step] in between. A step does one bounded unit of work (one
 * input slice of ≤ [sliceMs] through the session's effect chain, one
 * non-blocking write) and returns how long the runner may wait for the next
 * command before stepping again, so commands always apply between slices.
 *
 * - **One continuous track.** Blocks of a session, and sessions that follow
 *   each other at the same sample rate, are written back to back on one
 *   track: no drain, no rebuild, no gap at a paragraph. Only a [OutputCommand.Begin]
 *   at another rate waits for the audio ahead of it to be heard, then rebuilds
 *   the track — at the exact boundary frame.
 * - **Per-session chain.** Each session gets its own [StreamingEffectChain]
 *   (its effects, its live Tempo), built at its Begin and flushed (tail
 *   written) at its End — switched at the boundary frame.
 * - **Epochs.** A [OutputCommand.Flush] pauses the track, drops its queued
 *   frames ([PcmTrack.flush] resets the head, so every frame mark is dropped
 *   with it) and from then on drops any stream item of that session tagged
 *   with an older epoch. Nothing stale is ever written after a flush.
 * - **Marks.** Each chunk's first and last frame are recorded as positions in
 *   the track's post-chain frame count; [OutputEvent]s fire when the head
 *   passes them — "heard", after Tempo and effects.
 * - **The tail is heard.** Android's mixer does not play a track's last few
 *   tens of ms until more data arrives (it mixes whole periods). While the
 *   writer has nothing to write and real audio is still unplayed, it pads
 *   with silence — just enough to push that audio through (the amount grows
 *   if the head still doesn't move). So a session's end, and the chunk before
 *   an underrun, are heard in full and on time.
 * - **Parked tracks.** Writes never block. A head frozen for [stallMs] while
 *   playing with real audio unplayed is a track the OS parked (Android 16's
 *   audio hardening): [OutputEvent.TrackFailed].
 */
class TrackWriter(
    private val track: Track,
    private val factory: PcmTrackFactory,
    private val clock: () -> Long,
    private val emit: (OutputEvent) -> Unit,
    private val sliceMs: Int = DEFAULT_SLICE_MS,
    private val stallMs: Long = DEFAULT_STALL_MS,
    private val idleReleaseMs: Long = DEFAULT_IDLE_RELEASE_MS,
    private val log: (String) -> Unit = {},
) {
    private class Mark(val frame: Long, val event: OutputEvent)

    /** Play requested by the Narrator. */
    private var playing = false

    /** The track is actually playing (synced to [playing] each step). */
    private var started = false

    private val queue = ArrayDeque<OutputCommand>()
    private val minEpoch = HashMap<Long, Int>()
    private val tempos = HashMap<Long, Float>()

    private var out: PcmTrack? = null

    /** The session whose stream items are being written, and its chain. */
    private var session: OutputCommand.Begin? = null
    private var chain: StreamingEffectChain? = null

    /** Where the head item (a Write) continues. */
    private var offset = 0

    /** Shaped frames the track hasn't accepted yet. */
    private var pending: ShortArray? = null
    private var pendingAt = 0
    private var pendingTag: ChunkTag? = null

    /** Frames written since the track was built or flushed (padding included). */
    private var written = 0L

    /** The frame after the last REAL (non-padding) frame written. */
    private var realEnd = 0L
    private val marks = ArrayDeque<Mark>()

    /** The last chunk written, for the chain tail's tag. */
    private var lastTag: ChunkTag? = null

    // Padding (see the class doc) and the watchdog.
    private var padAheadMs = PAD_AHEAD_MS
    private var padGrownAt = 0L
    private var paddedFrames = 0L
    private var lastHead = -1L
    private var lastProgressAt = 0L
    private var idleSince: Long? = null

    /** After a Pause with the head short of the real end: re-check until then (padding drop). */
    private var settleUntil: Long? = null
    private var settleWritten = 0L

    // Boundary bookkeeping for the log: silence between one chunk and the next.
    private var emptySince: Long? = null
    private var underrunMs = 0L
    private var lastChunkEnd: ChunkTag? = null

    /** When a playing session's real audio ran out (its next chunk late: a stall, #52); null otherwise. */
    private var dryAt: Long? = null

    /** Apply one command. Stream items queue; controls act now. */
    fun apply(c: OutputCommand) {
        when (c) {
            is OutputCommand.Begin, is OutputCommand.Write, is OutputCommand.End -> {
                if (isStale(c)) return
                // A Begin states the session's speed as of its submission;
                // SetTempos submitted after it win (applied on arrival).
                if (c is OutputCommand.Begin) tempos[c.sessionId] = c.tempo
                queue.addLast(c)
            }
            is OutputCommand.Play -> playing = true
            is OutputCommand.Pause -> {
                playing = false
                syncPlayState()
                // Nothing real left to hear: drop the padding now, so the next
                // session doesn't start behind it. AudioTrack pauses
                // asynchronously (the head may still be short of the real end,
                // then play it out): if so, look again while it settles.
                if (!dropPaddingIfHeard() && out != null && written > realEnd) {
                    settleUntil = clock() + PAUSE_SETTLE_MS
                    settleWritten = written
                }
            }
            is OutputCommand.Flush -> {
                minEpoch[c.sessionId] = maxOf(minEpoch[c.sessionId] ?: 0, c.minEpoch)
                queue.clear()
                dropSession()
                if (out != null) flushTrack()
            }
            is OutputCommand.SetTempo -> {
                tempos[c.sessionId] = c.tempo
                if (session?.sessionId == c.sessionId) chain?.setLiveTempo(c.tempo)
            }
            is OutputCommand.Release -> {
                playing = false
                queue.clear()
                dropSession()
                releaseTrack("released")
            }
        }
    }

    /**
     * One unit of work. Returns the ms the runner may wait for a command
     * before the next step: 0 to step again at once, [UNTIL_COMMAND] when
     * nothing can change until a command arrives (paused, or nothing to
     * play) — so an idle or paused writer sleeps instead of polling.
     */
    fun step(): Long {
        val wait = stepOnce()
        return if (settleUntil != null) minOf(wait, SETTLE_POLL_MS) else wait
    }

    private fun stepOnce(): Long {
        syncPlayState()
        settleUntil?.let { until ->
            // Playing again, written to, or settled long enough: nothing to drop.
            if (started || written != settleWritten || clock() > until || dropPaddingIfHeard()) settleUntil = null
        }
        fireMarks()
        if (stalled()) {
            log("$name: playback head parked at ${head()} with ${realEnd - head()} frames unplayed")
            failTrack("AudioTrack parked: head frozen while playing")
            return 0
        }
        if (pending != null) return if (writePending()) 0 else fullWait()
        val item = queue.firstOrNull()
        val progressed = when (item) {
            null -> false
            is OutputCommand.Begin -> begin(item)
            is OutputCommand.Write -> shape(item)
            is OutputCommand.End -> end(item)
            else -> {
                queue.removeFirst()
                true
            }
        }
        if (progressed) {
            idleSince = null
            return if (pending == null || writePending()) 0 else fullWait()
        }
        return starved()
    }

    /**
     * The device refused, broke or parked the track: release it and fail
     * every session with audio on it or queued for it, in play order — each
     * would otherwise wait forever for its end. A short session can be on the
     * track whole, its End already applied: only its marks still name it.
     * Also the runner's last resort.
     */
    fun failTrack(message: String) {
        val owners = LinkedHashSet<Pair<Long, Int>>()
        for (m in marks) owners += ownerOf(m.event)
        pendingTag?.let { owners += it.sessionId to it.epoch }
        session?.let { owners += it.sessionId to it.epoch }
        for (c in queue) ownerOf(c)?.let { owners += it }
        queue.clear()
        dropSession()
        releaseTrack("failed")
        for ((sessionId, epoch) in owners) emit(OutputEvent.TrackFailed(track, sessionId, epoch, message))
    }

    // -- stream items -----------------------------------------------------------------

    private fun begin(item: OutputCommand.Begin): Boolean {
        val current = out
        if (current != null && current.sampleRate != item.sampleRate) {
            // Another rate: the audio ahead must be heard first — at the
            // boundary frame the track is rebuilt.
            if (head() < realEnd) return false
            fireMarks(all = true)
            log("$name: rebuild ${current.sampleRate} → ${item.sampleRate} Hz at the session boundary")
            releaseTrack("rate change")
        }
        if (out == null && !openTrack(item)) return true
        queue.removeFirst()
        session = item
        chain = StreamingEffectChain(item.effectBlocks, item.sampleRate, liveTempo = tempos[item.sessionId] ?: item.tempo)
        return true
    }

    private fun shape(item: OutputCommand.Write): Boolean {
        val s = session
        val c = chain
        if (s == null || c == null || s.sessionId != item.tag.sessionId) {
            log("$name: chunk ${item.tag} without its session's Begin; dropped")
            queue.removeFirst()
            offset = 0
            return true
        }
        val pcm = item.pcm
        if (offset == 0) {
            noteBoundary(item.tag)
            marks.addLast(Mark(written, OutputEvent.ChunkStarted(track, item.tag)))
        }
        val to = minOf(pcm.size, offset + (s.sampleRate * sliceMs / 1000).coerceAtLeast(1))
        setPending(c.process(pcm.copyOfRange(offset, to)), item.tag)
        offset = to
        if (to >= pcm.size) {
            marks.addLast(Mark(written + pendingSize(), OutputEvent.ChunkFinished(track, item.tag)))
            queue.removeFirst()
            offset = 0
            lastTag = item.tag
            lastChunkEnd = item.tag
        }
        return true
    }

    private fun end(item: OutputCommand.End): Boolean {
        queue.removeFirst()
        val tail = chain?.flush()
        if (tail != null && tail.isNotEmpty()) setPending(tail, lastTag)
        marks.addLast(Mark(written + pendingSize(), OutputEvent.SessionEnded(track, item.sessionId, item.epoch)))
        dropSession()
        return true
    }

    private fun setPending(pcm: ShortArray, tag: ChunkTag?) {
        if (pcm.isEmpty()) return
        pending = pcm
        pendingAt = 0
        pendingTag = tag
    }

    /** Paused with only padding left unplayed: flush it (and so reset the head). True when it did. */
    private fun dropPaddingIfHeard(): Boolean {
        if (out == null || pending != null || written == 0L) return false
        fireMarks()
        if (marks.isNotEmpty() || head() < realEnd) return false
        flushTrack()
        return true
    }

    /** The track is full: while it plays, look again shortly; paused, it can't drain. */
    private fun fullWait(): Long = if (started) FULL_WAIT_MS else UNTIL_COMMAND

    private fun pendingSize(): Int = pending?.let { it.size - pendingAt } ?: 0

    /** Write what's pending; true when all of it went in. */
    private fun writePending(): Boolean {
        val pcm = pending ?: return true
        val t = out ?: run {
            pending = null
            return true
        }
        val n = t.write(pcm, pendingAt, pcm.size - pendingAt, pendingTag)
        if (n < 0) {
            log("$name: write returned $n")
            failTrack("AudioTrack.write returned $n")
            return true
        }
        if (n > 0) {
            if (emptySince != null) {
                underrunMs += clock() - emptySince!!
                emptySince = null
            }
            pendingAt += n
            written += n
            realEnd = written
        }
        if (pendingAt < pcm.size) return false
        pending = null
        pendingTag = null
        return true
    }

    /**
     * Nothing writable now (queue empty, or a Begin at another rate waiting).
     * Pad while real audio is stuck unplayed; release a track idle for long.
     */
    private fun starved(): Long {
        val t = out ?: return UNTIL_COMMAND
        val h = head()
        val now = clock()
        if (drainHeld(t, h)) return 0
        if (!started || h >= realEnd) {
            // The stuck audio got through: the next stall starts from a small
            // pad again (a grown one would queue up to a buffer of silence
            // at every later session end or underrun).
            if (started) padAheadMs = PAD_AHEAD_MS
            if (started && h >= written && emptySince == null && realEnd > 0) emptySince = now
            if (started && session != null && realEnd > 0 && dryAt == null) dryAt = now
            if (session == null && queue.isEmpty() && marks.isEmpty() && h >= realEnd) {
                val since = idleSince ?: now.also { idleSince = it }
                val left = idleReleaseMs - (now - since)
                if (left <= 0) {
                    releaseTrack("idle")
                    return UNTIL_COMMAND
                }
                return if (started) minOf(IDLE_WAIT_MS, left) else left
            }
            // Paused: nothing moves until a command. Playing and starved
            // mid-session: watch for the next chunk's frames.
            return if (started) IDLE_WAIT_MS else UNTIL_COMMAND
        }
        // Mid-session the next chunk may be a moment away: pad only once the
        // head has really stopped short of the real end. At a session's end
        // nothing else is coming: push the tail through at once.
        val stuckFor = now - lastProgressAt
        if (session != null && stuckFor < STUCK_MS) return PAD_WAIT_MS
        val unplayed = written - h
        if (unplayed >= framesFor(t, padAheadMs) && stuckFor >= PAD_GROW_MS &&
            now - padGrownAt >= PAD_GROW_MS && framesFor(t, padAheadMs) < t.capacityFrames
        ) {
            // Padded and still stuck: the mixer wants more than that.
            padAheadMs *= 2
            padGrownAt = now
            log("$name: tail still unplayed, padding ${padAheadMs}ms")
        }
        val target = minOf(framesFor(t, padAheadMs), t.capacityFrames).toLong()
        if (unplayed < target) {
            val n = minOf(framesFor(t, sliceMs).toLong(), target - unplayed).toInt()
            val accepted = t.write(ShortArray(n), 0, n, null)
            if (accepted < 0) {
                failTrack("AudioTrack.write returned $accepted")
                return 0
            }
            written += accepted
            paddedFrames += accepted
        }
        return PAD_WAIT_MS
    }

    /**
     * About to run dry mid-session with nothing queued: the live Tempo stage
     * still holds the end of the last chunk (up to a frame). Push it through
     * now, so the chunk is heard whole before the underrun rather than after
     * it; its ChunkFinished moves to the real last frame. Not earlier — a
     * stretch restart at every streamed slice that arrives just in time
     * would be audible for nothing.
     */
    private fun drainHeld(t: PcmTrack, h: Long): Boolean {
        val c = chain ?: return false
        if (!started || queue.isNotEmpty() || realEnd - h > framesFor(t, DRAIN_AHEAD_MS)) return false
        val held = c.drainLiveTempo()
        if (held.isEmpty()) return false
        val last = marks.lastOrNull()
        val moveFinish = last != null && last.event is OutputEvent.ChunkFinished && last.event.tag == lastTag && last.frame == written
        setPending(held, lastTag)
        if (moveFinish) {
            marks.removeLast()
            marks.addLast(Mark(written + pendingSize(), last!!.event))
        }
        return true
    }

    // -- the track ----------------------------------------------------------------------

    private fun openTrack(item: OutputCommand.Begin): Boolean {
        val t = try {
            factory.open(item.sampleRate)
        } catch (e: Exception) {
            log("$name: no track at ${item.sampleRate} Hz: ${e.message}")
            failTrack("AudioTrack rejected ${item.sampleRate} Hz mono PCM16")
            return false
        }
        out = t
        resetPosition()
        log("$name: track opened at ${item.sampleRate} Hz (${t.capacityFrames} frames)")
        return true
    }

    private fun releaseTrack(why: String) {
        val t = out ?: return
        try {
            if (started) t.pause()
            t.flush()
        } catch (_: IllegalStateException) { /* already stopped */ }
        try {
            t.release()
        } catch (_: RuntimeException) { /* a dead track */ }
        log("$name: track released ($why)")
        out = null
        started = false
        pending = null
        pendingTag = null
        marks.clear()
        idleSince = null
        resetPosition()
    }

    /** Pause, drop every queued frame (and every mark: the head restarts at 0). */
    private fun flushTrack() {
        val t = out ?: return
        if (started) t.pause()
        started = false
        t.flush()
        pending = null
        pendingTag = null
        marks.clear()
        resetPosition()
    }

    private fun resetPosition() {
        written = 0
        realEnd = 0
        paddedFrames = 0
        padAheadMs = PAD_AHEAD_MS
        lastHead = -1
        lastProgressAt = clock()
        emptySince = null
        dryAt = null
    }

    private fun dropSession() {
        session = null
        chain = null
        offset = 0
        lastTag = null
    }

    private fun syncPlayState() {
        val t = out ?: return
        if (playing && !started) {
            t.play()
            started = true
            lastProgressAt = clock()
        } else if (!playing && started) {
            t.pause()
            started = false
            dryAt = null
        }
    }

    private fun head(): Long = out?.head()?.coerceAtMost(written) ?: 0

    /** Playing, real audio unplayed and the head hasn't moved for [stallMs]. */
    private fun stalled(): Boolean {
        if (out == null) return false
        val h = head()
        val now = clock()
        if (h != lastHead || !started || h >= realEnd) {
            if (h != lastHead) lastHead = h
            lastProgressAt = now
            return false
        }
        return now - lastProgressAt >= stallMs
    }

    private fun isStale(c: OutputCommand): Boolean {
        val (id, epoch) = ownerOf(c) ?: return false
        return epoch < (minEpoch[id] ?: 0)
    }

    /** (sessionId, epoch) of a stream item; null for a control. */
    private fun ownerOf(c: OutputCommand): Pair<Long, Int>? = when (c) {
        is OutputCommand.Begin -> c.sessionId to c.epoch
        is OutputCommand.Write -> c.tag.sessionId to c.tag.epoch
        is OutputCommand.End -> c.sessionId to c.epoch
        else -> null
    }

    private fun ownerOf(e: OutputEvent): Pair<Long, Int> = when (e) {
        is OutputEvent.ChunkStarted -> e.tag.sessionId to e.tag.epoch
        is OutputEvent.ChunkFinished -> e.tag.sessionId to e.tag.epoch
        is OutputEvent.SessionEnded -> e.sessionId to e.epoch
        is OutputEvent.TrackFailed -> e.sessionId to e.epoch
    }

    /** Report every mark the head has passed (or, before a rebuild, all of them). */
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

    /**
     * Log the silence a block boundary cost: padding written plus time the
     * track sat empty. And any chunk that came after the session's real audio
     * had run out: a stall, silent from then until now (#52).
     */
    private fun noteBoundary(tag: ChunkTag) {
        val prev = lastChunkEnd
        val t = out
        if (prev != null && t != null && tag.chunk == 0 && prev.sessionId == tag.sessionId && prev.segment != tag.segment) {
            val padMs = paddedFrames * 1000 / t.sampleRate
            val emptyMs = underrunMs + (emptySince?.let { clock() - it } ?: 0)
            log("$name: seg ${prev.segment}→${tag.segment} session=${tag.sessionId} pad=${padMs}ms underrun=${emptyMs}ms")
        }
        val dry = dryAt
        if (dry != null && prev?.sessionId == tag.sessionId) {
            log("$name: stall before seg ${tag.segment} chunk ${tag.chunk} session=${tag.sessionId} silent=${clock() - dry}ms")
        }
        paddedFrames = 0
        underrunMs = 0
        dryAt = null
    }

    private val name: String get() = track.name.lowercase()

    private fun framesFor(t: PcmTrack, ms: Int): Int = (t.sampleRate.toLong() * ms / 1000).toInt().coerceAtLeast(1)

    /** Writer-thread state for the tests' invariants. */
    internal val trackOpen: Boolean get() = out != null

    companion object {
        /** Input slice: commands apply between slices; a speed change takes effect at the next one. */
        const val DEFAULT_SLICE_MS = 20

        /** Zero head progress for this long while playing real audio means the OS parked the track. */
        const val DEFAULT_STALL_MS = 2_500L

        /** A track with nothing to play for this long is released (rebuilt on the next Begin). */
        const val DEFAULT_IDLE_RELEASE_MS = 30_000L

        /** Silence kept queued behind the last real frame while starved — at least one mixer period. */
        private const val PAD_AHEAD_MS = 60

        /** Mid-session with nothing queued, push the Tempo stage's held frame out once this little real audio is left. */
        private const val DRAIN_AHEAD_MS = 60

        /** Mid-session, the head must sit still this long short of the real end before padding starts. */
        private const val STUCK_MS = 40L

        /** Head stuck this long while padded: the mixer wants more; double the pad. */
        private const val PAD_GROW_MS = 120L

        /** How long after a Pause the head may still play out real audio (AudioTrack pauses asynchronously). */
        private const val PAUSE_SETTLE_MS = 200L
        private const val SETTLE_POLL_MS = 10L

        /** [step]'s "wait for the next command, however long". */
        const val UNTIL_COMMAND = Long.MAX_VALUE

        private const val FULL_WAIT_MS = 10L
        private const val PAD_WAIT_MS = 5L
        private const val IDLE_WAIT_MS = 20L
    }
}
