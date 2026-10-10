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

class SourceHostRuntimes(val routes: List<RoutedSourceRuntime.Route>, private val owner: Closeable) : Closeable {
    override fun close() = owner.close()
}
