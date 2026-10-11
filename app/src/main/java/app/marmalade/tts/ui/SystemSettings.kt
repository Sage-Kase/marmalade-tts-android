package app.marmalade.tts.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Open the system Text-to-Speech settings so the user can pick Marmalade as
 * the preferred engine. Returns false if no Settings page opened. Targets per
 * Android version: [SettingsLinks]; how they're opened: [SystemSettings].
 */
fun openSystemTtsSettings(context: Context): Boolean =
    SystemSettings.open(context, SettingsLink.TTS) != null

/**
 * Returns true if Marmalade is currently the system's default
 * text-to-speech engine. Reads the `tts_default_synth` secure setting
 * (readable without any permission); it holds the package name of the
 * preferred engine. Composables can poll this via lifecycle events to
 * refresh after the user returns from the system TTS settings page.
 */
fun isDefaultSystemTts(context: Context): Boolean {
    val engine = Settings.Secure.getString(
        context.contentResolver,
        "tts_default_synth",
    )
    return engine == context.packageName
}

/**
 * Returns true if Android is currently allowing this app to run
 * unrestricted in the background (i.e. the user has exempted us from
 * battery optimisations). Composables can poll this via lifecycle
 * events to refresh after the user returns from the settings prompt.
 *
 * Always false on devices without a PowerManager (defensive — every
 * real device has one).
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Ask Android to exempt this app from battery optimisation, so our
 * foreground synthesis service isn't paused mid-utterance when the screen
 * sleeps or the device enters Doze. Opens the direct yes/no dialog (we
 * declare REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; Play Console accepts it for
 * TTS engines that need uninterrupted foreground audio), else the app list,
 * else App info ([SettingsLinks]). Returns false if nothing opened.
 */
fun openBatteryOptimizationRequest(context: Context): Boolean =
    SystemSettings.open(context, SettingsLink.BATTERY) != null

/**
 * Opens [SettingsLinks]' screens (#53, ported from marmalade-stt-android
 * #79). Each target is resolved before it is launched: it must exist on this
 * phone, be exported, need no permission we lack, and match one activity (an
 * action matching several would show a chooser; only the last fallback,
 * Settings' home, may). The manifest's `<queries>` makes handlers outside
 * the Settings app visible to these lookups.
 */
object SystemSettings {

    private const val TAG = "SystemSettings"

    /**
     * Settings' extra naming the row to scroll to and highlight
     * ([SettingsTarget.highlight]). Up to 16 Settings reads it only from the
     * fragment-arguments bundle; 17 also reads it from the intent.
     */
    private const val HIGHLIGHT_KEY = ":settings:fragment_args_key"
    private const val FRAGMENT_ARGS = ":settings:show_fragment_args"

    /** How soon a return to our screen counts as the page bouncing (STT #79: Settings' crash took ~0.5 s). */
    private const val BOUNCE_MS = 1_500L

    /** The first of [link]'s targets this phone can open, or null if none. */
    fun resolve(context: Context, link: SettingsLink): SettingsTarget? {
        val targets = SettingsLinks.targets(link, Build.VERSION.SDK_INT)
        return targets.firstOrNull { canOpen(context, it, last = it == targets.last()) }
    }

    /**
     * Open [link]'s best screen, trying its targets from index [from], and
     * return where it landed, or null if no screen opened.
     *
     * Settings always opens in its own task, fresh (CLEAR_TASK, so Back
     * doesn't walk pages left from an earlier visit; it returns to us). In
     * our task, a Settings page that crashes takes our screen down with it
     * (STT #79: Android 17's Language & region did). If the page bounces
     * straight back anyway ([watchForBounce]), the next target opens, so
     * the user always gets somewhere in Settings.
     */
    fun open(context: Context, link: SettingsLink, from: Int = 0): SettingsTarget? {
        val targets = SettingsLinks.targets(link, Build.VERSION.SDK_INT)
        val index = (from until targets.size).firstOrNull { i ->
            canOpen(context, targets[i], last = i == targets.lastIndex) && start(context, intentFor(context, targets[i]))
        }
        val landed = index?.let { targets[it] }
        Log.i(TAG, "$link landed on ${landed?.page}")
        if (index != null && index < targets.lastIndex) watchForBounce(context, link, index)
        return landed
    }

    /**
     * A page that crashes or refuses us closes at once and our screen comes
     * back within [BOUNCE_MS] of leaving; a person using it takes longer.
     * Then the targets after [index] are tried, unless the link's job got
     * done: the battery dialog closes at once when we're already exempt, and
     * a quick Allow is a success, not a bounce. (A quick Deny opens the app
     * list: it can't be told apart from a phone that refuses the dialog.)
     * Activities that finish as they open Settings aren't watched: they
     * don't come back.
     */
    private fun watchForBounce(context: Context, link: SettingsLink, index: Int) {
        val owner = context.findActivity() as? LifecycleOwner ?: return
        val started = SystemClock.elapsedRealtime()
        var left = false
        owner.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                val elapsed = SystemClock.elapsedRealtime() - started
                when {
                    elapsed > BOUNCE_MS || event == Lifecycle.Event.ON_DESTROY -> source.lifecycle.removeObserver(this)
                    event == Lifecycle.Event.ON_PAUSE -> left = true
                    event == Lifecycle.Event.ON_RESUME && left -> {
                        source.lifecycle.removeObserver(this)
                        val activity = source as? Activity ?: return
                        if (link.isDone(activity)) return
                        Log.w(TAG, "$link bounced back after $elapsed ms; trying the next page")
                        open(activity, link, from = index + 1)
                    }
                }
            }
        })
    }

    private fun SettingsLink.isDone(context: Context): Boolean = when (this) {
        SettingsLink.TTS -> isDefaultSystemTts(context)
        SettingsLink.BATTERY -> isIgnoringBatteryOptimizations(context)
    }

    fun intentFor(context: Context, target: SettingsTarget): Intent {
        val intent = when (target) {
            is SettingsTarget.Component -> Intent(target.action).setClassName(target.packageName, target.className)
            is SettingsTarget.Action -> Intent(target.action).apply {
                if (target.forApp) data = Uri.fromParts("package", context.packageName, null)
            }
        }
        target.highlight?.let { key ->
            intent.putExtra(HIGHLIGHT_KEY, key)
            // 17 reads the top-level extra; the bundle is checked only up to 16.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.BAKLAVA) {
                intent.putExtra(FRAGMENT_ARGS, Bundle().apply { putString(HIGHLIGHT_KEY, key) })
            }
        }
        return intent
    }

    /** Whether [target] resolves to one activity we may start ([last]: a chooser will do). */
    fun canOpen(context: Context, target: SettingsTarget, last: Boolean = false): Boolean {
        val matches = query(context.packageManager, intentFor(context, target))
            .map { SettingsMatch(it.activityInfo.exported, it.activityInfo.permission) }
        val ok = SettingsLinks.canStart(matches, last) {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (!ok) Log.i(TAG, "Skipping $target: ${matches.size} matches")
        return ok
    }

    private fun query(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }

    private fun start(context: Context, intent: Intent): Boolean =
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No settings page for ${intent.action ?: intent.component}", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Settings page not open to us: ${intent.action ?: intent.component}", e)
            false
        }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
