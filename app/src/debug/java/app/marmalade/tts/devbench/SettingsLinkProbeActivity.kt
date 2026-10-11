package app.marmalade.tts.devbench

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import app.marmalade.tts.ui.SettingsLink
import app.marmalade.tts.ui.SettingsLinks
import app.marmalade.tts.ui.SystemSettings

/**
 * Debug-only probe for the Settings links (#53). Logs, under tag
 * SettingsLinkProbe, whether each target of every link resolves on this
 * phone, then opens `--es link <TTS|BATTERY>` the way the app does and
 * finishes (so no bounce fallback runs from here):
 * `adb shell am start -n app.marmalade.tts.debug/app.marmalade.tts.devbench.SettingsLinkProbeActivity --es link TTS`
 */
class SettingsLinkProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "sdk=${Build.VERSION.SDK_INT} manufacturer=${Build.MANUFACTURER} model=${Build.MODEL}")
        for (link in SettingsLink.entries) {
            val targets = SettingsLinks.targets(link, Build.VERSION.SDK_INT)
            for (target in targets) {
                Log.i(TAG, "$link ${if (SystemSettings.canOpen(this, target, target == targets.last())) "OK  " else "SKIP"} $target")
            }
            Log.i(TAG, "$link resolves to ${SystemSettings.resolve(this, link)?.page}")
        }
        intent.getStringExtra("link")?.let { name ->
            val landed = SystemSettings.open(this, SettingsLink.valueOf(name))
            Log.i(TAG, "$name opened ${landed?.page} via $landed")
        }
        finish()
    }

    private companion object {
        const val TAG = "SettingsLinkProbe"
    }
}
