package org.skepsun.kototoro.source.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.RoutedSourceRuntime
import org.skepsun.kototoro.core.source.SourceEndpoint
import org.skepsun.kototoro.core.source.SourceRequest
import java.io.Closeable
import java.nio.file.Path

/** A platform and registry shared by all requests until the embedding owner closes the session. */
class SourceHostSession private constructor(
    private val endpoint: SourceEndpoint,
    private val registry: MihonJarRegistry,
    private val platform: SourceHostPlatform,
    private val preferences: FileSourcePreferenceStore?,
    private val extraRuntimes: SourceHostRuntimes?,
    private val management: SourceHostManagement,
    private val retiredRuntimes: MutableList<SourceHostRuntimes>,
) : Closeable {
    private val lifecycle = Any()
    private var closed = false
    private var active = 0
    private var idle: CompletableDeferred<Unit>? = null

    /**
     * Requests run concurrently (JNI callers use their own threads); ecosystem runtimes serialize per source where
     * extension state requires it. [close] stops new requests and waits for running ones before releasing resources.
     */
    suspend fun exchange(request: String): String {
        synchronized(lifecycle) {
            check(!closed) { "Source host session is closed" }
            active++
        }
        try {
            return if (SourceHostManagement.isManagement(request)) management.exchange(request) else endpoint.exchange(request)
        } finally {
            synchronized(lifecycle) {
                active--
                if (active == 0) idle?.complete(Unit)
            }
        }
    }

    override fun close() {
        val running = synchronized(lifecycle) {
            if (closed) return
            closed = true
            if (active > 0) CompletableDeferred<Unit>().also { idle = it } else null
        }
        running?.let { runBlocking { it.await() } }
        closeResources(*(retiredRuntimes + listOf(extraRuntimes, registry, platform, preferences)).toTypedArray())
    }

    companion object {
        fun open(
            config: SourceHostConfig,
            configurationDirectory: Path,
            platform: SourceHostPlatform,
        ): SourceHostSession {
            var preferences: FileSourcePreferenceStore? = null
            var registry: MihonJarRegistry? = null
            var extra: SourceHostRuntimes? = null
            try {
                // Preferences remain available during the platform's shutdown callbacks.
                preferences = config.preferenceDirectory?.let {
                    require(platform is SourceHostPreferencePlatform) {
                        "Platform does not support persistent preferences"
                    }
                    FileSourcePreferenceStore(configurationDirectory.resolve(it).normalize())
                }
                val loader = if (preferences != null) {
                    (platform as SourceHostPreferencePlatform).initialize(preferences)
                } else platform.initialize()
                registry = MihonJarRegistry(loader)
                val installed = SourceHostManagement.readInstalled(configurationDirectory)
                for (jar in config.jars) {
                    if (installed.jars.any { it.identity.packageName == jar.identity.packageName }) continue
                    registry.load(configurationDirectory.resolve(jar.path).normalize(), jar.identity)
                }
                for (jar in installed.jars) {
                    registry.load(SourceHostManagement.resolveInstalledPath(configurationDirectory, jar.path), jar.identity)
                }
                val images = config.imageDirectory?.let {
                    FileSourceImageStore(configurationDirectory.resolve(it).normalize())
                }
                val context = (platform as? SourceHostPreferenceUiPlatform)?.preferenceContext()
                // Same routing as the desktop session: one registry serves manga/novels and anime separately.
                val mihon = MihonSourceRuntime(registry, images, context)
                val anime = AniyomiSourceRuntime(registry, images, context)
                val seedPlugins = config.plugins.map { it.copy(path = configurationDirectory.resolve(it.path).normalize().toString()) }
                val installedPlugins = installed.plugins.map {
                    it.copy(path = SourceHostManagement.resolveInstalledPath(configurationDirectory, it.path).toString())
                }
                val plugins = seedPlugins.filterNot { seed -> installedPlugins.any { it.id == seed.id } } + installedPlugins
                if (plugins.isNotEmpty()) {
                    require(platform is SourceHostRuntimePlatform) { "Platform does not support plugin runtimes" }
                }
                extra = (platform as? SourceHostRuntimePlatform)?.openRuntimes(plugins, images, preferences)
                val primaryRoutes = listOf(
                    RoutedSourceRuntime.Route(mihon::owns, mihon),
                    RoutedSourceRuntime.Route(anime::owns, anime),
                )
                // Old parser owners live until session close, so requests already using them can finish safely.
                val retired = mutableListOf<SourceHostRuntimes>()
                val pluginRoutes = java.util.concurrent.atomic.AtomicReference(extra?.routes.orEmpty())
                val ownedRegistry = registry
                val ownedPreferences = preferences
                val manager = SourceHostManagement(configurationDirectory, config.copy(plugins = seedPlugins), registry) { next, publish ->
                    require(platform is SourceHostRuntimePlatform)
                    val replacement = platform.openRuntimes(next, images, ownedPreferences)
                    try { publish() } catch (failure: Throwable) { replacement.close(); throw failure }
                    retired.add(replacement)
                    pluginRoutes.set(replacement.routes)
                }
                return SourceHostSession(
                    SourceEndpoint(RoutedSourceRuntime { primaryRoutes + pluginRoutes.get() }, ::reportFailure),
                    ownedRegistry, platform, preferences, extra, manager, retired,
                )
            } catch (failure: Throwable) {
                try { closeResources(extra, registry, platform, preferences) } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        /**
         * Development diagnostics (`-Dkototoro.source.diagnostics=stderr`): the wire protocol deliberately carries no
         * causes, so probes need the host-side stack trace. Off by default; may include site URLs.
         */
        private fun reportFailure(request: SourceRequest, error: Exception) {
            if (System.getProperty("kototoro.source.diagnostics") != "stderr") return
            System.err.println("SOURCE_FAILURE ${request.requestId} ${request.call::class.simpleName}")
            error.printStackTrace()
        }

        private fun closeResources(vararg resources: Closeable?) {
            var failure: Throwable? = null
            for (resource in resources) {
                try { resource?.close() } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                }
            }
            failure?.let { throw it }
        }
    }
}
