package org.skepsun.kototoro.source.host

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Reader
import java.io.Writer
import java.nio.file.Path

/** One JVM/registry for a local JSONL session. Input/output remain owned by the caller; EOF closes the runtime. */
object SourceHostJsonSession {
    suspend fun run(
        config: SourceHostConfig,
        configurationDirectory: Path,
        input: Reader,
        output: Writer,
        platform: SourceHostPlatform,
    ) {
        SourceHostSession.open(config, configurationDirectory, platform).use { session ->
            val lines = input.buffered()
            val responses = output.buffered()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = lines.readLine() ?: break
                if (line.isBlank()) continue
                responses.write(session.exchange(line))
                responses.newLine()
                responses.flush()
            }
        }
    }
}
