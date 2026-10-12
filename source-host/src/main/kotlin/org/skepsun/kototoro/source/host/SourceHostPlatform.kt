package org.skepsun.kototoro.source.host

import java.io.Closeable
import org.skepsun.kototoro.core.source.RoutedSourceRuntime
import org.skepsun.kototoro.core.source.SourcePreferenceStore

/** Desktop embedding owns compatibility initialization; the session closes it after all extension loaders. */
interface SourceHostPlatform : Closeable {
    fun initialize(): ClassLoader
}

/** Optional SPI. Bind Application.getSharedPreferences before returning, before any extension constructor runs. */
interface SourceHostPreferencePlatform : SourceHostPlatform {
    fun initialize(preferences: SourcePreferenceStore): ClassLoader
}

/** Optional settings capability: returns a Context from the already initialized parent AndroidCompat runtime. */
interface SourceHostPreferenceUiPlatform : SourceHostPreferencePlatform {
    fun preferenceContext(): Any
}

/**
 * Optional SPI for ecosystems beyond the Tachiyomi ABI (parser plugin JARs). Called once after the Mihon registry is
 * loaded; the returned routes are consulted after Mihon/Tsundoku and Aniyomi, and [SourceHostRuntimes.close] runs
 * before the registry and platform close. [plugins] carry absolute paths.
 */
interface SourceHostRuntimePlatform : SourceHostPlatform {
    fun openRuntimes(
        plugins: List<SourceHostPlugin>,
        images: SourceImageStore?,
        preferences: SourcePreferenceStore?,
    ): SourceHostRuntimes
}

class SourceHostRuntimes(
    val routes: List<RoutedSourceRuntime.Route>,
    /** Readable source titles keyed by wire name, for host management replies. */
    val titles: () -> Map<String, String> = { emptyMap() },
    /** Sources each loaded plugin provides (by plugin id, including ones another plugin's copy shadows). */
    val sources: () -> Map<String, List<org.skepsun.kototoro.core.source.SourceRef>> = { emptyMap() },
    /**
     * Applies a new plugin set to these live runtimes, calling the publish callback before the change becomes
     * visible; false asks the host to open a full replacement instead. Loading only the changed plugin keeps
     * install time and memory flat when large parser plugins are already loaded.
     */
    val update: ((List<SourceHostPlugin>, () -> Unit) -> Boolean)? = null,
    private val owner: Closeable,
) : Closeable {
    override fun close() = owner.close()
}
