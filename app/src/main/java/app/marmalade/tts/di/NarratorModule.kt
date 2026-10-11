package app.marmalade.tts.di

import android.os.SystemClock
import android.util.Log
import app.marmalade.tts.engine.EngineRegistry
import app.marmalade.tts.playback.EngineLookup
import app.marmalade.tts.playback.KeepalivePort
import app.marmalade.tts.playback.Narrator
import app.marmalade.tts.playback.NarratorConfig
import app.marmalade.tts.playback.NarratorControl
import app.marmalade.tts.service.AudioFocusPort
import app.marmalade.tts.service.AudioTrackOutput
import app.marmalade.tts.service.EngineResidency
import app.marmalade.tts.service.KeepaliveCoordinator
import app.marmalade.tts.service.NarratorPreparer
import app.marmalade.tts.service.NarratorResolver
import app.marmalade.tts.service.NarratorStats
import app.marmalade.tts.service.NarratorWakeLock
import app.marmalade.tts.service.PlaybackHost
import app.marmalade.tts.service.ServicePlaybackHost
import app.marmalade.tts.service.ServiceHost
import app.marmalade.tts.service.TopAppHold
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

/**
 * The process's one [Narrator] — the owner of all in-app speech (reader,
 * Speak screen, shares, Tasker) — wired to its device ports. System TTS
 * (MarmaladeTtsService) does not go through it.
 */
@Module
@InstallIn(SingletonComponent::class)
object NarratorModule {

    @Provides
    @Singleton
    fun provideNarrator(
        engines: EngineRegistry,
        resolver: NarratorResolver,
        preparer: NarratorPreparer,
        output: AudioTrackOutput,
        focus: AudioFocusPort,
        host: ServiceHost,
        residency: EngineResidency,
        keepalive: KeepaliveCoordinator,
        stats: NarratorStats,
        wakeLock: NarratorWakeLock,
        topApp: TopAppHold,
    ): Narrator = Narrator(
        engines = EngineLookup { name -> engines[name] },
        resolver = resolver,
        preparer = preparer,
        output = output,
        focus = focus,
        host = host,
        residency = residency,
        // P-K: a session starting is a synth for the keepalive's purposes.
        keepalive = KeepalivePort { keepalive.onSynthCompleted() },
        stats = stats,
        clock = SystemClock::elapsedRealtime,
        loopDispatcher = Narrator.newLoopDispatcher(),
        workDispatcher = Dispatchers.Default,
        // Today's read-ahead depth for every voice until step G (system TTS
        // goes first): a Pocket render parked ahead would hold its engine
        // lock for a whole utterance and block TalkBack.
        config = NarratorConfig(todaysDepthOnly = true),
        log = { Log.d(NARRATOR_TAG, it) },
    ).also {
        focus.shell = it
        wakeLock.follow(it.state)
        topApp.follow(it.state)
    }

    @Provides
    fun provideNarratorControl(narrator: Narrator): NarratorControl = narrator

    /** MarmaladeSynthService as the reader's foreground host. An interface so the reader's tests can refuse it. */
    @Provides
    fun providePlaybackHost(impl: ServicePlaybackHost): PlaybackHost = impl

    /** Logcat tag of the Narrator's decision log (plan §5.4). */
    private const val NARRATOR_TAG = "Narrator"
}
