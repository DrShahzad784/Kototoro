package org.skepsun.kototoro.source.host

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import java.nio.file.Path

class SourceHostSessionTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `managed installs verify identity preserve previous install on failure and reopen`() = runBlocking {
        val fixture = RuntimeJarFixture(directory)
        val source = fixture.sourceJar()
        val incoming = java.nio.file.Files.createDirectories(directory.resolve("incoming"))
        val archive = java.nio.file.Files.copy(source, incoming.resolve("source.jar"))
        val identity = fixture.jars.identity(source)
        fun open(): SourceHostSession {
            val loader = fixture.jars.compatibilityLoader()
            return SourceHostSession.open(SourceHostConfig("fixture"), directory, object : SourceHostPlatform {
                override fun initialize(): ClassLoader = loader
                override fun close() = loader.close()
            })
        }
        fun request(operation: String, version: Long = identity.versionCode) = SourceProtocolJson.encodeToString(
            HostManagementRequest(1, operation, archive.toString(), identity.sha256, identity.packageName, version, "MIHON"))
        val session = open()
        try {
            val result = SourceProtocolJson.decodeFromString<HostManagementResult>(session.exchange(request("install")))
            assertEquals(identity.packageName, result.installed.single().packageName)
            assertFalse(result.installed.single().bundled)
            val metadata = MihonJarInspector().inspect(source)
            assertEquals(metadata.displayName, result.installed.single().displayName)
            assertEquals(metadata.versionName, result.installed.single().versionName)
            assertEquals(metadata.isNsfw, result.installed.single().isAdult)
            assertEquals(listOf("zh"), result.installed.single().languages)
            assertEquals(result.sourceTitles.values.toList(), result.installed.single().sourceNames)
            assertTrue(result.sourceTitles.getValue("MIHON_9007199254740993").isNotBlank())
            assertEquals(listOf("MIHON_9007199254740993"), result.installed.single().sourceIds)
            val persisted = java.nio.file.Files.readString(directory.resolve("installed.json"))
            assertThrows(SourceJarException::class.java) { runBlocking { session.exchange(request("install", 999)) } }
            assertEquals(persisted, java.nio.file.Files.readString(directory.resolve("installed.json")))
            assertEquals(1, SourceProtocolClient(SourceTransport { session.exchange(it) }) { "probe" }.getSources().size)
            val invalid = SourceProtocolJson.encodeToString(HostManagementRequest(1, "install", source.toString(),
                identity.sha256, identity.packageName, identity.versionCode, "MIHON"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { session.exchange(invalid) } }
        } finally { session.close() }
        val reopened = open()
        try {
            val client = SourceProtocolClient(SourceTransport { reopened.exchange(it) }) { "reopened" }
            val restored = SourceProtocolJson.decodeFromString<HostManagementResult>(reopened.exchange(request("list")))
            assertEquals(MihonJarInspector().inspect(source).displayName, restored.installed.single().displayName)
            assertTrue(restored.installed.single().sourceNames.isNotEmpty())
            assertEquals("MIHON_9007199254740993", client.getSources().single().name)
            reopened.exchange(request("uninstall"))
            assertTrue(client.getSources().isEmpty())
            assertTrue(SourceHostManagement.readInstalled(directory).jars.isEmpty())
            // User-selected local archives omit catalog ecosystem/version and derive identity from the manifest.
            val localInstall = SourceProtocolJson.encodeToString(
                HostManagementRequest(1, "install", archive.toString(), identity.sha256, "local-fallback", 0, ""),
            )
            val localResult = SourceProtocolJson.decodeFromString<HostManagementResult>(reopened.exchange(localInstall))
            assertEquals(identity.packageName, localResult.installed.single().packageName)
            assertEquals(identity.versionCode, localResult.installed.single().versionCode)
            assertEquals("MIHON", localResult.installed.single().ecosystem)
            assertEquals("MIHON_9007199254740993", client.getSources().single().name)
        } finally { reopened.close() }
    }

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
    fun `one session routes manga anime and platform plugin runtimes and closes plugins first`() = runBlocking {
        val fixture = RuntimeJarFixture(directory)
        val loader = fixture.jars.compatibilityLoader()
        val events = mutableListOf<String>()
        val pluginSource = SourceRef("PLUGIN_SITE", "en", "MANGA")
        var receivedPlugins: List<SourceHostPlugin> = emptyList()
        val platform = object : SourceHostRuntimePlatform {
            override fun initialize(): ClassLoader = loader
            override fun close() { events += "platform"; loader.close() }
            override fun openRuntimes(plugins: List<SourceHostPlugin>, images: SourceImageStore?,
                preferences: SourcePreferenceStore?): SourceHostRuntimes {
                receivedPlugins = plugins
                val runtime = object : SourceRuntime {
                    override suspend fun getSources() = listOf(pluginSource)
                    override suspend fun describe(sourceName: String) = throw SourceOperationUnsupportedException()
                    override suspend fun getFilterOptions(sourceName: String) = throw SourceOperationUnsupportedException()
                    override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?) =
                        listOf(SourceContent(1, "plugin item", emptySet(), "/p", "/p", 0f, null, null, emptySet(), null,
                            emptySet(), pluginSource))
                    override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode) = content
                    override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?) = emptyList<SourcePage>()
                    override suspend fun getPageUrl(page: SourcePage) = page.url
                    override suspend fun getRelated(content: SourceContent) = emptyList<SourceContent>()
                }
                return SourceHostRuntimes(listOf(RoutedSourceRuntime.Route({ it == pluginSource.name }, runtime)),
                    { mapOf(pluginSource.name to "插件站点") }) {
                    events += "plugins"
                }
            }
        }
        val manga = fixture.sourceJar()
        val anime = fixture.animeJar()
        val config = SourceHostConfig("fixture",
            listOf(SourceHostJar(manga.toString(), fixture.jars.identity(manga)),
                SourceHostJar(anime.toString(), fixture.jars.identity(anime))),
            plugins = listOf(SourceHostPlugin("plugins/site.jar", "site", "a".repeat(64))))
        val session = SourceHostSession.open(config, directory, platform)
        try {
            var sequence = 0
            val client = SourceProtocolClient(SourceTransport { session.exchange(it) }) { "s${sequence++}" }
            assertEquals(setOf("MIHON_9007199254740993", "ANIYOMI_777", "PLUGIN_SITE"),
                client.getSources().map { it.name }.toSet())
            assertEquals("popular 1", client.getList("MIHON_9007199254740993", 0, null, null).single().title)
            assertTrue(client.getList("ANIYOMI_777", 0, null, null).isNotEmpty())
            assertEquals("plugin item", client.getList("PLUGIN_SITE", 0, null, null).single().title)
            assertEquals(directory.resolve("plugins/site.jar").toString(), receivedPlugins.single().path)
            // Plugin titles reach the management reply that iOS uses for readable source names.
            val titles = kotlinx.serialization.json.Json.parseToJsonElement(
                session.exchange("""{"managementVersion":1,"operation":"list"}""")).jsonObject["sourceTitles"]!!.jsonObject
            assertEquals("插件站点", titles["PLUGIN_SITE"]!!.jsonPrimitive.content)
        } finally {
            session.close()
        }
        assertEquals(listOf("plugins", "platform"), events)
    }

    @Test
    fun `requests run concurrently and close waits for running ones`() = runBlocking {
        val fixture = RuntimeJarFixture(directory)
        val loader = fixture.jars.compatibilityLoader()
        val source = SourceRef("SLOW", "en", "MANGA")
        val entered = java.util.concurrent.CountDownLatch(2)
        val release = CompletableDeferred<Unit>()
        var pluginsClosed = false
        val platform = object : SourceHostRuntimePlatform {
            override fun initialize(): ClassLoader = loader
            override fun close() = loader.close()
            override fun openRuntimes(plugins: List<SourceHostPlugin>, images: SourceImageStore?,
                preferences: SourcePreferenceStore?): SourceHostRuntimes {
                val runtime = object : SourceRuntime {
                    override suspend fun getSources() = listOf(source)
                    override suspend fun describe(sourceName: String) = throw SourceOperationUnsupportedException()
                    override suspend fun getFilterOptions(sourceName: String) = throw SourceOperationUnsupportedException()
                    override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?):
                        List<SourceContent> {
                        entered.countDown()
                        release.await()
                        return emptyList()
                    }
                    override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode) = content
                    override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?) = emptyList<SourcePage>()
                    override suspend fun getPageUrl(page: SourcePage) = page.url
                    override suspend fun getRelated(content: SourceContent) = emptyList<SourceContent>()
                }
                return SourceHostRuntimes(listOf(RoutedSourceRuntime.Route({ it == source.name }, runtime))) {
                    pluginsClosed = true
                }
            }
        }
        val session = SourceHostSession.open(SourceHostConfig("fixture"), directory, platform)
        val list = SourceProtocolJson.encodeToString(SourceRequest(SOURCE_PROTOCOL_VERSION, "l", SourceCall.ListContent("SLOW", 0)))
        // Two JNI-style callers on their own threads: both must be inside the runtime at the same time.
        val callers = List(2) { Thread { runBlocking { session.exchange(list) } }.apply { start() } }
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS), "requests were serialized")
        val closer = Thread { session.close() }.apply { start() }
        closer.join(300)
        assertTrue(closer.isAlive, "close must wait for running requests")
        assertFalse(pluginsClosed)
        assertThrows(IllegalStateException::class.java) { runBlocking { session.exchange(list) } }
        release.complete(Unit)
        callers.forEach { it.join(10_000) }
        closer.join(10_000)
        assertFalse(closer.isAlive)
        assertTrue(pluginsClosed)
    }

    @Test
    fun `plugin installs update live runtimes in place and prune stale artifacts`() = runBlocking {
        val fixture = RuntimeJarFixture(directory)
        val loader = fixture.jars.compatibilityLoader()
        val extensions = java.nio.file.Files.createDirectories(directory.resolve("extensions"))
        // Left by a process stopped mid-conversion.
        val interrupted = java.nio.file.Files.createFile(extensions.resolve(".convert-1.tmp"))
        val staging = java.nio.file.Files.createFile(extensions.resolve("conversion-2.jar"))
        var opens = 0
        val updates = mutableListOf<List<String>>()
        val platform = object : SourceHostRuntimePlatform {
            override fun initialize(): ClassLoader = loader
            override fun close() = loader.close()
            override fun openRuntimes(plugins: List<SourceHostPlugin>, images: SourceImageStore?,
                preferences: SourcePreferenceStore?): SourceHostRuntimes {
                opens++
                return SourceHostRuntimes(emptyList(),
                    sources = { mapOf("fixture.plugin" to listOf(SourceRef("PLUGIN_JA", "ja", "MANGA"), SourceRef("PLUGIN_ALL", "", "MANGA"))) },
                    update = { next, publish -> publish(); updates += next.map { it.sha256.orEmpty() }; true }) {}
            }
        }
        val session = SourceHostSession.open(SourceHostConfig("fixture"), directory, platform)
        try {
            assertFalse(java.nio.file.Files.exists(interrupted))
            assertFalse(java.nio.file.Files.exists(staging))
            val incoming = java.nio.file.Files.createDirectories(directory.resolve("incoming"))
            suspend fun install(content: String): String {
                val archive = incoming.resolve("plugin.jar")
                java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(archive)).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("plugin.txt")); zip.write(content.toByteArray()); zip.closeEntry()
                }
                val sha = SourceHostManagement.hash(archive)
                session.exchange(SourceProtocolJson.encodeToString(
                    HostManagementRequest(1, "install", archive.toString(), sha, "fixture.plugin", 1, "JAR")))
                return sha
            }
            val first = install("one")
            val second = install("two")
            val listed = SourceProtocolJson.decodeFromString<HostManagementResult>(session.exchange(SourceProtocolJson.encodeToString(
                HostManagementRequest(1, "list", "", "", "", 0, "")))).installed.single { it.ecosystem == "JAR" }
            assertEquals(listOf("PLUGIN_JA", "PLUGIN_ALL"), listed.sourceIds)
            assertEquals(listOf("ja", ""), listed.languages)
            // The plugin set changed twice without reopening every runtime.
            assertEquals(1, opens)
            assertEquals(listOf(listOf(first), listOf(second)), updates)
            // Only the artifact the install record references stays on disk.
            assertEquals(listOf("$second.jar"), java.nio.file.Files.list(extensions).use { it.map { f -> f.fileName.toString() }.toList() })
        } finally { session.close() }
    }

    @Test
    fun `plugins without a runtime platform are rejected`() {
        val fixture = RuntimeJarFixture(directory)
        val loader = fixture.jars.compatibilityLoader()
        val platform = object : SourceHostPlatform {
            override fun initialize(): ClassLoader = loader
            override fun close() = loader.close()
        }
        val config = SourceHostConfig("fixture", plugins = listOf(SourceHostPlugin("p.jar", "p")))
        assertThrows(IllegalArgumentException::class.java) { SourceHostSession.open(config, directory, platform) }
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

    @Test
    fun `relocated container path in installed json resolves to current extensions directory and drops missing jars`() = runBlocking {
        val fixture = RuntimeJarFixture(directory)
        val source = fixture.sourceJar()
        val identity = fixture.jars.identity(source)
        val extensionsDir = java.nio.file.Files.createDirectories(directory.resolve("extensions"))
        java.nio.file.Files.copy(source, extensionsDir.resolve("${identity.sha256}.jar"))
        val staleInstall = SourceHostConfig(
            platformClass = "",
            jars = listOf(
                SourceHostJar(
                    path = "/Users/test/Library/Developer/CoreSimulator/Devices/DEV/data/Containers/Data/Application/OLD-UUID/Library/Application Support/Kototoro/sources/extensions/${identity.sha256}.jar",
                    identity = identity,
                ),
                SourceHostJar(
                    path = "/Users/test/Library/Developer/CoreSimulator/Devices/DEV/data/Containers/Data/Application/OLD-UUID/Library/Application Support/Kototoro/sources/extensions/${"f".repeat(64)}.jar",
                    identity = identity.copy(packageName = "missing.extension", sha256 = "f".repeat(64)),
                ),
            ),
        )
        java.nio.file.Files.writeString(
            directory.resolve("installed.json"),
            SourceProtocolJson.encodeToString(staleInstall),
        )
        val loader = fixture.jars.compatibilityLoader()
        val session = SourceHostSession.open(SourceHostConfig("fixture"), directory, object : SourceHostPlatform {
            override fun initialize(): ClassLoader = loader
            override fun close() = loader.close()
        })
        try {
            val client = SourceProtocolClient(SourceTransport { session.exchange(it) }) { "relocated" }
            assertEquals(listOf("MIHON_9007199254740993"), client.getSources().map { it.name })
        } finally {
            session.close()
        }
    }
}
