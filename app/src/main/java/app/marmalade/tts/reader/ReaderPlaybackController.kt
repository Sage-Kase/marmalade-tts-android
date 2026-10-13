package app.marmalade.tts.reader

import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.lang.VoiceChoice
import app.marmalade.tts.playback.ArticleView
import app.marmalade.tts.playback.FailureKind
import app.marmalade.tts.playback.NarratorControl
import app.marmalade.tts.playback.NarratorState
import app.marmalade.tts.playback.PauseReason
import app.marmalade.tts.playback.PausedBy
import app.marmalade.tts.playback.Status
import app.marmalade.tts.service.PlaybackHost
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
//   Who owns reader playback
// -----------------------------------------------------------------------------
//
//   The Narrator (playback/Narrator.kt) does: the article's segments, voice,
//   session speed, cursor, buffered audio, focus — everything that plays. This
//   controller is the reader's client of it (reader-session redesign, step
//   C1): an application-scoped @Singleton that
//
//     - holds the extracted article (design point 8: in memory only, dies with
//       the process) so the screen can rebind without a refetch — re-sharing
//       the link or reopening from the notification finds it here;
//     - forwards the reader's commands to the Narrator, bringing the host
//       service up first when a command may start audio (Android 16 mutes a
//       bare activity's AudioTrack — the service must be foreground);
//     - projects the Narrator's state onto the handful of things the reader
//       draws ([ReaderPlaybackState]).
//
//   Design point 7 (the back action returns to the sharing app while the
//   article keeps being read) holds because nothing here depends on the
//   screen: the Narrator plays on after the ViewModel is gone.
//
//   The voice (#66): picked once when the article opens, and picked again
//   whenever the primary alias changes — switched, or its voice, speed or
//   effect edited — so a held article never keeps reading in the old primary.
//   Once the user picks an alias in the reader itself ([pickAlias]), that
//   pick wins for the rest of this article and the primary no longer moves it.
// -----------------------------------------------------------------------------

/** Coarse transport state — everything the reader UI needs to draw itself. */
enum class ReaderPlaybackStatus {
    /** An article is loaded but nothing has been spoken (or playback stopped). */
    Idle,

    Playing,
    Paused,

    /** The last block finished. Play starts the article over from the top. */
    Finished,
}

/**
 * [articleKey] is the article's URL — the identity check that decides whether
 * re-entering the reader resumes an in-flight read or starts a new one.
 * [currentIndex] is always a valid index into the article (0 when idle), so
 * the "N of M" readout never has to deal with a null.
 */
data class ReaderPlaybackState(
    val articleKey: String? = null,
    val blockCount: Int = 0,
    val currentIndex: Int = 0,
    val status: ReaderPlaybackStatus = ReaderPlaybackStatus.Idle,
    /**
     * The reading session's speed — the absolute speed the voice is spoken
     * at, replacing the alias's own speed (voice, effect and language still
     * come from the alias). See [ReaderPlaybackController.setSpeed].
     *
     * Lives in memory and nowhere else — not in DataStore, not in settings.
     * It is a property of *this reading* of *this article*: each new article
     * starts back at its reading alias's own speed — the primary alias, or
     * the one [voice] picked for the article's language; 1.0 for an installed
     * voice no alias uses (passed to [ReaderPlaybackController.open]) —
     * because a speed picked to skim one long post is not a preference about
     * how the app speaks.
     */
    val speed: Float = 1.0f,
    /**
     * The speed this article was opened at — its reading alias's own speed
     * (see [speed]). Kept after the user picks another so the speed sheet can
     * always offer the way back to it, even when it isn't a curated chip.
     */
    val startingSpeed: Float = 1.0f,
    /**
     * The voice this article is read in: chosen when it was opened (see
     * lang/VoiceForLanguage.kt), chosen again when the primary alias changes,
     * or the alias the user picked in the reader ([voicePicked]).
     */
    val voice: VoiceChoice = VoiceChoice.Primary,
    /** The user picked [voice] in the reader; the primary alias no longer moves it. */
    val voicePicked: Boolean = false,
    /**
     * Why playback last stopped on its own — a failed synthesis, a missing
     * engine, audio focus refused, or a host service that wouldn't start
     * ([FailureKind.HOST_REFUSED]: Android refused it from the background) — or
     * null. Set alongside the fall back to Idle, cleared the next time
     * playback starts. A Stop the user asked for is not an error.
     */
    val lastError: FailureKind? = null,
) {
    /** True while a block is the one being read — i.e. worth highlighting. */
    val isActive: Boolean
        get() = status == ReaderPlaybackStatus.Playing || status == ReaderPlaybackStatus.Paused
}

@Singleton
class ReaderPlaybackController internal constructor(
    private val narrator: NarratorControl,
    private val host: PlaybackHost,
    private val scope: CoroutineScope,
    /**
     * The primary alias, or null for none. Every change after the first value
     * picks the held article's voice again (#66).
     */
    primary: Flow<VoiceAlias?> = emptyFlow(),
    /** The voice an article is read in, and the session speed that voice starts at. */
    private val pickVoice: suspend (ReaderArticle) -> Pair<VoiceChoice, Float> = { VoiceChoice.Primary to 1.0f },
) {

    @Inject
    constructor(
        narrator: NarratorControl,
        host: PlaybackHost,
        picker: ReaderVoicePicker,
        settings: SettingsRepository,
        aliasDao: VoiceAliasDao,
    ) : this(
        narrator = narrator,
        host = host,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        primary = combine(settings.primaryAliasId, aliasDao.getAll()) { id, aliases ->
            aliases.firstOrNull { it.id == id }
        },
        pickVoice = { article ->
            val voice = picker.voiceFor(article)
            voice to readerStartingSpeed(voice) { settings.primaryAliasId.first()?.let { aliasDao.findById(it) } }
        },
    )

    /** The article the reader holds, the voice it is read in, and its starting speed. */
    private data class Held(
        val article: ReaderArticle? = null,
        val voice: VoiceChoice = VoiceChoice.Primary,
        val startingSpeed: Float = 1.0f,
        /** The user picked [voice] in the reader ([pickAlias]). */
        val voicePicked: Boolean = false,
        /** The host service refused to start for this article's last play — see [startHost]. */
        val refused: Boolean = false,
    )

    private val held = MutableStateFlow(Held())

    val state: StateFlow<ReaderPlaybackState> =
        combine(narrator.state, held, ::project)
            .stateIn(scope, SharingStarted.Eagerly, current())

    /** The primary alias's id as last seen, for [pickAlias]. */
    @Volatile
    private var primaryId: String? = null

    init {
        // Only what can change the voice counts: renaming the primary or
        // setting its fallback must not restart the block being read.
        scope.launch {
            primary
                .map {
                    primaryId = it?.id
                    it?.copy(name = "", createdAt = 0, fallbackAliasId = null)
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { onPrimaryChanged() }
        }
    }

    /**
     * Load [article] for reading in [voice], its session speed starting at
     * [initialSpeed] — the caller passes that voice's alias's own speed, so
     * the reader begins exactly as fast as the voice is tuned to speak.
     *
     * Returns true when this is a new article — the caller's cue to start
     * playing. Returns false (and changes nothing, speed and voice included)
     * when [article] is already the one held: that is a rebind, not a new
     * reading, and playback carries on exactly as it was.
     */
    fun open(
        article: ReaderArticle,
        initialSpeed: Float = 1.0f,
        voice: VoiceChoice = VoiceChoice.Primary,
    ): Boolean {
        val blocks = article.blocks.map { it.text }
        if (article.url == held.value.article?.url && blocks.isNotEmpty()) return false
        val speed = initialSpeed.coerceIn(NarratorControl.MIN_SPEED, NarratorControl.MAX_SPEED)
        held.value = Held(article, voice, speed)
        narrator.openArticle(article.url, blocks, voice, speed, autoplay = false)
        return true
    }

    /**
     * The article held under [key], or null if we're holding a different one
     * (or none). The reader screen asks this before fetching: reopening from
     * the notification, or re-sharing a link that is already being read, must
     * not hit the network again.
     */
    fun article(key: String): ReaderArticle? = held.value.article?.takeIf { it.url == key }

    /**
     * Read the held article in [alias] from now on — the user's pick in the
     * reader, at that alias's own speed (unless they picked another speed).
     * It holds for the rest of this article: a later primary change no longer
     * moves it. The block being read restarts in the new voice — unless
     * [alias] is already the one reading, which is only pinned.
     */
    fun pickAlias(alias: VoiceAlias) {
        val h = held.value
        val article = h.article ?: return
        val reading = when (val current = h.voice) {
            VoiceChoice.Primary -> primaryId == alias.id
            is VoiceChoice.Alias -> current.aliasId == alias.id
            is VoiceChoice.Installed -> false
        }
        if (reading) {
            if (!h.voicePicked) held.value = h.copy(voicePicked = true)
            return
        }
        val voice = VoiceChoice.Alias(alias.id, alias.voiceId, alias.engine, alias.speed)
        val speed = roundedSpeed(alias.speed)
        held.value = h.copy(voice = voice, startingSpeed = speed, voicePicked = true)
        narrator.setArticleVoice(article.url, voice, speed)
    }

    /** Play, resume, or (after the end) start the article again. */
    fun play() {
        if (blockCount() == 0 || !startHost()) return
        narrator.playArticle()
    }

    fun pause() = narrator.pauseArticle()

    fun togglePlayPause() {
        if (current().status == ReaderPlaybackStatus.Playing) pause() else play()
    }

    /**
     * Read from [index] now — the tap-a-block (and contents-pick) action. Plays
     * even from Paused: tapping a paragraph is the user asking to hear it, so a
     * tap-then-press-play would be a step too many (Max, 2026-09-26).
     */
    fun playFrom(index: Int) {
        if (index !in 0 until blockCount() || !startHost()) return
        narrator.jumpTo(index, play = true)
    }

    /** Forward — the next block, or the end of the article if there isn't one. Keeps a pause. */
    fun next() {
        if (blockCount() == 0 || !startHostUnlessPaused()) return
        narrator.next()
    }

    /**
     * Backward, with music-player semantics: restart the current block, unless
     * it only just started being heard, in which case go to the previous block.
     * Keeps a pause.
     */
    fun previous() {
        if (blockCount() == 0 || !startHostUnlessPaused()) return
        narrator.previous()
    }

    /**
     * Set the session's speed — absolute, replacing the alias's own speed
     * rather than scaling it (see [ReaderPlaybackState.speed]). Applied live
     * by the Narrator: nothing stops or re-renders, playing keeps playing and
     * paused stays paused.
     */
    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(NarratorControl.MIN_SPEED, NarratorControl.MAX_SPEED)
        if (clamped == current().speed) return
        narrator.setSpeed(clamped)
    }

    /**
     * Pause because the user navigated out of the reader (the top-bar arrow or
     * system back): pause AND stop preparing ahead, so backing out during the
     * first render doesn't leave audio to start after the user has left.
     * Stays Paused with the highlight intact. A no-op when nothing of the
     * reader's is active; an app switch never routes through here, so
     * background playback is untouched.
     */
    fun pauseForNavigation() {
        if (!current().isActive) return
        narrator.pause(PauseReason.Navigation)
    }

    // -- internals ------------------------------------------------------------

    private fun blockCount(): Int = held.value.article?.blocks?.size ?: 0

    /** The primary alias changed: pick the held article's voice again, unless the user picked one. */
    private suspend fun onPrimaryChanged() {
        val article = held.value.article ?: return
        if (held.value.voicePicked) return
        val (voice, speed) = pickVoice(article)
        // The pick suspends: the article may have changed, or the user picked, meanwhile.
        val h = held.value
        if (h.article?.url != article.url || h.voicePicked) return
        held.value = h.copy(voice = voice, startingSpeed = speed)
        narrator.setArticleVoice(article.url, voice, speed)
    }

    /** The projection of what is known right now, for decisions made before [state] catches up. */
    private fun current(): ReaderPlaybackState = project(narrator.state.value, held.value)

    /**
     * Bring the host service up before a command that may start audio. When the
     * OS refuses (the app is in the background and no host is up), nothing
     * would ever play: the reader falls back to Idle and says why instead of
     * sitting on a silent Playing; Play works once the app is in front.
     */
    private fun startHost(): Boolean {
        val ok = host.start()
        if (held.value.refused == ok) held.value = held.value.copy(refused = !ok)
        return ok
    }

    /** Next/Previous keep a pause (no audio, no host needed); from anything else they play. */
    private fun startHostUnlessPaused(): Boolean =
        current().status == ReaderPlaybackStatus.Paused || startHost()

    companion object {
        private fun project(narrator: NarratorState, held: Held): ReaderPlaybackState {
            val article = held.article ?: return ReaderPlaybackState()
            // The Narrator's article view, once it has loaded ours (it may still
            // show the previous one for a moment after open()).
            val view: ArticleView? = narrator.article?.takeIf { it.key == article.url }
            val base = ReaderPlaybackState(
                articleKey = article.url,
                blockCount = article.blocks.size,
                currentIndex = view?.index ?: 0,
                speed = view?.speed ?: held.startingSpeed,
                startingSpeed = view?.startingSpeed ?: held.startingSpeed,
                voice = held.voice,
                voicePicked = held.voicePicked,
            )
            if (held.refused) return base.copy(lastError = FailureKind.HOST_REFUSED)
            return when (val s = view?.status ?: Status.Idle) {
                Status.Idle -> base
                Status.Buffering, Status.Playing -> base.copy(status = ReaderPlaybackStatus.Playing)
                // A share reading over the article: it resumes by itself when
                // the share ends, so the reader keeps showing it as playing.
                is Status.Paused -> base.copy(
                    status = if (s.by == PausedBy.Interrupt) ReaderPlaybackStatus.Playing else ReaderPlaybackStatus.Paused,
                )
                Status.Finished -> base.copy(status = ReaderPlaybackStatus.Finished)
                is Status.Failed -> base.copy(lastError = s.kind)
            }
        }
    }
}
