package app.marmalade.tts.reader

import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.lang.LanguageVoiceSelector
import app.marmalade.tts.lang.VoiceChoice
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Picks the voice an article is read in — when it is loaded, before the
 * first block, and again when the primary alias changes (#66,
 * ReaderPlaybackController); a rebind to an article already held keeps it.
 * The rule is shared with the share route: see
 * app/marmalade/tts/lang/VoiceForLanguage.kt.
 *
 * An interface so the reader's ViewModel tests can fake the choice.
 */
fun interface ReaderVoicePicker {
    suspend fun voiceFor(article: ReaderArticle): VoiceChoice
}

@Singleton
class LanguageAwareReaderVoicePicker @Inject constructor(
    private val selector: LanguageVoiceSelector,
) : ReaderVoicePicker {

    override suspend fun voiceFor(article: ReaderArticle): VoiceChoice =
        selector.select(detectionSample(article), subject = "article", logTag = TAG)

    companion object {
        private const val TAG = "ReaderVoice"

        /**
         * How much article text language detection looks at. Plenty for the
         * script check and the trigram model, and it keeps a very long
         * article from costing anything noticeable.
         */
        const val DETECTION_SAMPLE_CHARS = 2_000

        /** The start of [article] — title, then blocks in order — capped at [DETECTION_SAMPLE_CHARS]. */
        fun detectionSample(article: ReaderArticle): String = buildString {
            article.title?.let { append(it).append('\n') }
            for (block in article.blocks) {
                if (length >= DETECTION_SAMPLE_CHARS) break
                append(block.text).append('\n')
            }
        }.take(DETECTION_SAMPLE_CHARS)
    }
}

/**
 * The speed the reader starts an article at: the own speed of the alias it is
 * read in, so reading begins exactly as fast as that voice is tuned to speak.
 * For [VoiceChoice.Primary] that is [primaryAlias] — the same lookup as
 * [app.marmalade.tts.service.TtsRouter.resolveAlias] with no caller package.
 * 1.0 when there is no primary alias, and for an installed voice no alias uses.
 */
suspend fun readerStartingSpeed(voice: VoiceChoice, primaryAlias: suspend () -> VoiceAlias?): Float {
    val speed = when (voice) {
        VoiceChoice.Primary -> primaryAlias()?.speed
        is VoiceChoice.Alias -> voice.speed
        is VoiceChoice.Installed -> null
    } ?: return 1.0f
    return roundedSpeed(speed)
}

/**
 * [speed] rounded to hundredths: the alias slider stores values like
 * 1.1000001f, which would otherwise match no speed chip and label the extra
 * chip with float noise. The difference is far below anything audible.
 */
fun roundedSpeed(speed: Float): Float = (speed * 100).roundToInt() / 100f
