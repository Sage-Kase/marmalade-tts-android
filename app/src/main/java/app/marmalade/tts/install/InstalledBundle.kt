package app.marmalade.tts.install

/**
 * Which catalog download an engine or voice pack on disk came from, next to
 * the one the catalog offers now. Read by [EngineInstaller.installedBundles]
 * and logged by `MarmaladeTtsService` on every start, so a test run can tell
 * from logcat what it actually measured: the release gate refuses to time an
 * engine whose files aren't the catalog's (#51). The rc build isn't
 * debuggable, so its `.install_meta.json` can't be read any other way.
 *
 * @property name The engine name, or `<engine>:<pack id>` for a voice pack.
 * @property installedUrl / [installedSha256] From `.install_meta.json`; null
 *           when it is missing or unreadable (never stamped here, unlike
 *           [EngineInstaller.verify]).
 */
data class InstalledBundle(
    val name: String,
    val installedUrl: String?,
    val installedSha256: String?,
    val catalog: EngineArchive,
) {
    val isCurrent: Boolean get() = catalog.holdsSameFiles(installedSha256)

    /**
     * One logcat line under [LOG_TAG]; the release gate parses it
     * (scripts/release-smoke/release-smoke.py, `BUNDLE_RE`), so keep the shape.
     */
    fun logLine(): String =
        "$name installed=${releaseOf(installedUrl)} catalog=${releaseOf(catalog.url)} " +
            (if (isCurrent) "current" else "OUTDATED") + " sha=${installedSha256?.take(12) ?: "?"}"

    companion object {
        const val LOG_TAG: String = "EngineBundle"

        private val RELEASE = Regex("""/releases/download/(v\d+)/""")

        /** The bundle release in a download URL (`v26`), or `?`. */
        fun releaseOf(url: String?): String =
            url?.let { RELEASE.find(it)?.groupValues?.get(1) } ?: "?"
    }
}
