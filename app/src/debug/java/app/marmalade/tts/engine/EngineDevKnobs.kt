package app.marmalade.tts.engine

import android.util.Log
import java.io.File
import java.util.Properties

/**
 * Engine session overrides for on-device A/B runs, DEBUG BUILDS ONLY
 * (issue #13). An engine reads its knobs once per load from
 * `${filesDir}/dev/engine-knobs.properties` (pushed with run-as by the bench
 * driver), so one APK can measure a change against the behaviour it replaced
 * without reinstalling. No file, no key: the shipped behaviour.
 *
 * The release source set has a twin that always answers null, so no release
 * build reads the file.
 */
object EngineDevKnobs {

    fun get(filesDir: File, key: String): String? {
        val file = File(filesDir, "dev/engine-knobs.properties")
        if (!file.isFile) return null
        val value = Properties().apply { file.inputStream().use { load(it) } }.getProperty(key)?.trim()
        if (value != null) Log.i("EngineDevKnobs", "$key=$value")
        return value
    }
}
