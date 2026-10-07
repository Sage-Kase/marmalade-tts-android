package app.marmalade.tts.engine

import java.io.File

/** Release twin of the debug source set's A/B knobs (issue #13): never any override. */
object EngineDevKnobs {

    fun get(filesDir: File, key: String): String? = null
}
