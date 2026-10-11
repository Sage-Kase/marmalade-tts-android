package app.marmalade.tts.engine

/**
 * An engine's estimate of how much of the audio it has already emitted is
 * still waiting to be heard — what decides whether a new request is a
 * "cold start" worth cutting its first sentence for (T8, TTFA assessment
 * 2026-09-28, `docs/release/ttfa-chunking-lab.html`).
 *
 * The services play one engine's output back to back, so emitted audio is
 * modelled as a queue that drains in real time from the moment each chunk
 * is sent: `busyUntil = max(busyUntil, now) + playedMs`. A request
 * prefetched behind a playing one therefore sees seconds of audio still
 * ahead of it and keeps whole sentences; a request after an idle spell, a
 * Stop or a reader tap sees none and gets the short first piece.
 *
 * It is an estimate, and deliberately errs cheap: a pause makes the queue
 * look drained too early (an unneeded cut — a comma seam), and a Stop after
 * rendering finished makes it look full (no cut — today's TTFA). A stream
 * that ends by cancellation or failure clears it, since what it was
 * feeding has been cut off.
 */
class PlaybackHorizon(private val nowNs: () -> Long = System::nanoTime) {

    private var busyUntilNs = 0L
    private var busy = false

    // The same queue counting only the current request's audio (#54).
    private var requestUntilNs = 0L
    private var requestBusy = false
    private var rate = 1f

    /** Milliseconds of emitted audio still ahead of the listener (≥ 0). */
    @Synchronized
    fun remainingMs(): Long {
        if (!busy) return 0
        return ((busyUntilNs - nowNs()) / 1_000_000).coerceAtLeast(0)
    }

    /**
     * Like [remainingMs], counting only audio emitted since [startRequest]:
     * what chunk mode's hold may rely on. Audio from an earlier request may
     * already have been flushed without the engine hearing of it (a client's
     * Stop after its render finished), so it can't count as cover for this
     * request's first piece; when it really is still playing, holding the
     * piece a little longer costs nothing audible.
     */
    @Synchronized
    fun requestRemainingMs(): Long {
        if (!requestBusy) return 0
        return ((requestUntilNs - nowNs()) / 1_000_000).coerceAtLeast(0)
    }

    /** A request starts, heard at [rate]. */
    @Synchronized
    fun startRequest(rate: Float) {
        requestBusy = false
        this.rate = rate
    }

    /**
     * The listener's rate is now [rate] (a speed change mid-request): what is
     * still ahead drains in proportion.
     */
    @Synchronized
    fun follow(rate: Float) {
        if (rate <= 0f || rate == this.rate) return
        val factor = this.rate / rate.toDouble()
        val now = nowNs()
        if (busy && busyUntilNs > now) busyUntilNs = now + ((busyUntilNs - now) * factor).toLong()
        if (requestBusy && requestUntilNs > now) requestUntilNs = now + ((requestUntilNs - now) * factor).toLong()
        this.rate = rate
    }

    /** [playedMs] of audio (on the played clock) was just sent downstream. */
    @Synchronized
    fun onEmitted(playedMs: Long) {
        val now = nowNs()
        val start = if (busy && busyUntilNs > now) busyUntilNs else now
        busyUntilNs = start + playedMs * 1_000_000
        busy = true
        val requestStart = if (requestBusy && requestUntilNs > now) requestUntilNs else now
        requestUntilNs = requestStart + playedMs * 1_000_000
        requestBusy = true
    }

    /** The stream feeding the queue was cancelled or failed. */
    @Synchronized
    fun clear() {
        busy = false
        requestBusy = false
    }
}
