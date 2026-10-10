package org.skepsun.kototoro.source.host

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import java.nio.file.Path

class SourceHostSessionTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `embedded session retains a registry across requests and closes only once`() = runBlocking {
        val fixture = RuntimeJarFixture(directory)
        val loader = fixture.jars.compatibilityLoader()
        var initialized = 0
        var closed = 0
        val platform = object : SourceHostPlatform {
            override fun initialize(): ClassLoader { initialized++; return loader }
            override fun close() { closed++; loader.close() }
        }
        val jar = fixture.sourceJar()
        val config = SourceHostConfig("fixture", listOf(SourceHostJar(jar.toString(), fixture.jars.identity(jar))))
        val session = SourceHostSession.open(config, directory, platform)
        try {
            val calls = listOf(SourceCall.Sources,
                SourceCall.ListContent("MIHON_9007199254740993", 0, null, null))
            for ((index, call) in calls.withIndex()) {
                val request = SourceRequest(SOURCE_PROTOCOL_VERSION, "中文 🐈\u0000 $index", call)
                val response = SourceProtocolJson.decodeFromString<SourceResponse>(
                    session.exchange(SourceProtocolJson.encodeToString(request)))
                assertEquals(request.requestId, response.requestId)
                assertNull(response.error)
                if (index == 1) {
                    assertEquals("popular 1", (response.result as SourceResult.ListContent).content.single().title)
                }
            }
            assertEquals(1, initialized)
            assertEquals(0, closed)
        } finally {
            session.close()
        }
        session.close()
        assertEquals(1, closed)
        assertThrows(IllegalStateException::class.java) { runBlocking { session.exchange("{}") } }
        Unit
    }

    @Test
    fun `JNI factory has a static ABI and rejects relative storage`() {
        val open = EmbeddedSourceHost::class.java.getMethod("open", String::class.java, String::class.java)
        assertTrue(java.lang.reflect.Modifier.isStatic(open.modifiers))
        assertEquals(EmbeddedSourceHost::class.java, open.returnType)
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedSourceHost.open("{}", "relative")
        }
        val config = SourceProtocolJson.encodeToString(SourceHostConfig(CliFixturePlatform::class.java.name))
        EmbeddedSourceHost.open(config, directory.toString()).use { host ->
            val request = SourceRequest(SOURCE_PROTOCOL_VERSION, "iOS 中文 🐈", SourceCall.Sources)
            val reply = SourceProtocolJson.decodeFromString<SourceResponse>(
                host.exchange(SourceProtocolJson.encodeToString(request)))
            assertEquals(request.requestId, reply.requestId)
            assertEquals(emptyList<SourceRef>(), (reply.result as SourceResult.Sources).sources)
        }
    }

    @Test
    fun `cleanup preserves primary failure and attempts every owner in order`() {
        val platform = object : SourceHostPreferencePlatform {
            override fun initialize(): ClassLoader = error("unused")
            override fun initialize(preferences: SourcePreferenceStore): ClassLoader = error("initialization failed")
            override fun close() = error("shutdown failed")
        }
        val failure = assertThrows(IllegalStateException::class.java) {
            SourceHostSession.open(SourceHostConfig("fixture", preferenceDirectory = "prefs"), directory, platform)
        }
        assertEquals("initialization failed", failure.message)
        assertEquals("shutdown failed", failure.suppressed.single().message)
        FileSourcePreferenceStore(directory.resolve("prefs")).close()
    }
}
