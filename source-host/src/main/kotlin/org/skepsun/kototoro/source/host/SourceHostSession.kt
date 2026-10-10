package org.skepsun.kototoro.source.host

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.source.SourceEndpoint
import java.io.Closeable
import java.nio.file.Path

/** A platform and registry shared by all requests until the embedding owner closes the session. */
class SourceHostSession private constructor(
    private val endpoint: SourceEndpoint,
    private val registry: MihonJarRegistry,
    private val platform: SourceHostPlatform,
    private val preferences: FileSourcePreferenceStore?,
) : Closeable {
    private val lifecycle = Mutex()
    private var closed = false

    suspend fun exchange(request: String): String = lifecycle.withLock {
        check(!closed) { "Source host session is closed" }
        endpoint.exchange(request)
    }

    override fun close() = runBlocking {
        lifecycle.withLock {
            if (closed) return@withLock
            closed = true
            closeResources(registry, platform, preferences)
        }
    }

    companion object {
        fun open(
            config: SourceHostConfig,
            configurationDirectory: Path,
            platform: SourceHostPlatform,
        ): SourceHostSession {
            var preferences: FileSourcePreferenceStore? = null
            var registry: MihonJarRegistry? = null
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
                for (jar in config.jars) {
                    registry.load(configurationDirectory.resolve(jar.path).normalize(), jar.identity)
                }
                val images = config.imageDirectory?.let {
                    FileSourceImageStore(configurationDirectory.resolve(it).normalize())
                }
                val context = (platform as? SourceHostPreferenceUiPlatform)?.preferenceContext()
                return SourceHostSession(
                    SourceEndpoint(MihonSourceRuntime(registry, images, context)), registry, platform, preferences,
                )
            } catch (failure: Throwable) {
                try { closeResources(registry, platform, preferences) } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
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
