package org.skepsun.kototoro.source.host

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.SourceProtocolJson
import java.io.Closeable
import java.nio.file.Path

/** Stable, synchronous JNI ABI. The native owner retains this instance for its entire session. */
class EmbeddedSourceHost private constructor(private val session: SourceHostSession) : Closeable {
    fun exchange(requestJson: String): String = runBlocking { session.exchange(requestJson) }

    override fun close() = session.close()

    companion object {
        @JvmStatic
        fun open(configurationJson: String, configurationDirectory: String): EmbeddedSourceHost {
            val directory = Path.of(configurationDirectory)
            require(directory.isAbsolute) { "Source host configuration directory must be absolute" }
            val config = SourceProtocolJson.decodeFromString<SourceHostConfig>(configurationJson)
            val type = Class.forName(config.platformClass).asSubclass(SourceHostPlatform::class.java)
            val platform = type.getDeclaredConstructor().newInstance()
            return EmbeddedSourceHost(SourceHostSession.open(config, directory.normalize(), platform))
        }
    }
}
