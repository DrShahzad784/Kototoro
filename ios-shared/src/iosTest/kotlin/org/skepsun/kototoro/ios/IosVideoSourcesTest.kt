@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.tvbox.TvBoxHttp
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosVideoSourcesTest {
    private val api = "https://cms.example/api.php/provide/vod/"
    private val list = """
        {"code":1,"page":1,"pagecount":1,"class":[{"type_id":1,"type_name":"电影"}],
         "list":[{"vod_id":5,"vod_name":"示例影片","type_id":1,"type_name":"电影","vod_pic":"https://img.example/5.jpg",
           "vod_play_from":"高清${'$'}${'$'}${'$'}备用",
           "vod_play_url":"正片${'$'}https://v.example/5.m3u8${'$'}${'$'}${'$'}正片${'$'}https://b.example/5.mp4"}]}
    """.trimIndent()
    private val http = TvBoxHttp { url, _ -> if (url.startsWith(api)) list else throw IllegalStateException("HTTP 404") }

    private fun withLibrary(block: suspend (String) -> Unit) = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        try {
            block("$directory/library.db")
        } finally {
            files.removeItemAtPath(directory, null)
        }
    }

    @Test
    fun siteSettingsMoveTheApiAndSetTheUserAgent() = withLibrary { path ->
        val moved = "https://mirror.example/api.php/provide/vod/"
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val recording = TvBoxHttp { url, headers ->
            requests += url to headers
            if (url.startsWith(api) || url.startsWith(moved)) list else throw IllegalStateException("HTTP 404")
        }
        val library = IosLibrary(path)
        try {
            val store = IosVideoSources(library, recording)
            store.importFrom("$api?ac=list", "示例站")
            val id = store.list().single().id
            val initial = store.settings(id)
            assertTrue(initial.api.startsWith(api), initial.api)
            assertEquals("", initial.userAgent)
            assertFailsWith<IllegalArgumentException> { store.updateSettings(id, "ftp://x", "") }
            store.updateSettings(id, moved, "KototoroTest/1.0")
            assertEquals(IosVideoSiteSettings(moved, "KototoroTest/1.0"), store.settings(id))
            val sources = IosSources(null, store)
            requests.clear()
            sources.reloadVideoSites()
            sources.page(id, 0, 0, null, null)
            val (url, headers) = requests.first()
            assertTrue(url.startsWith(moved), url)
            assertEquals("KototoroTest/1.0", headers["User-Agent"])
            // A blank User-Agent removes the override again.
            store.updateSettings(id, moved, " ")
            assertEquals(IosVideoSiteSettings(moved, ""), store.settings(id))
        } finally {
            library.close()
        }
    }

    @Test
    fun importedCmsSitesPersistAndPlayWithoutTheJvm() = withLibrary { path ->
        val first = IosLibrary(path)
        val sourceId = try {
            val summary = IosVideoSources(first, http).importFrom("$api?ac=list", "示例站")
            assertEquals(IosImportSummary(1, 0, emptyList()), summary)
            // Android-imported spider sites share the table but are not offered on iOS.
            first.database.getJsonSourceDao().insert(
                JsonSourceEntity("JSON_TVBOX_SPIDER", "爬虫", JsonSourceType.TVBOX,
                    """{"site":{"type":3,"api":"csp_A"}}""", createdAt = 0, updatedAt = 0),
            )
            IosVideoSources(first, http).list().single().id
        } finally {
            first.close()
        }

        val reopened = IosLibrary(path)
        try {
            val store = IosVideoSources(reopened, http)
            val sources = IosSources(null, store)
            assertEquals(false, sources.hasExtensionRuntime)
            val catalog = sources.catalog()
            assertEquals(listOf(IosSource(sourceId, "示例站", "", "VIDEO", "video")), catalog.sources)
            assertNull(catalog.extensionError)
            assertEquals(listOf("电影"), sources.categories(sourceId).map { it.title })

            val item = sources.page(sourceId, 0, 0, null, null).single()
            assertEquals("https://img.example/5.jpg", item.coverUrl)
            assertEquals(false, sources.fetchesCovers(item))
            val details = sources.details(item)
            assertEquals(listOf("高清", "备用"), details.chapters.map { it.line })
            val stream = sources.pages(details.chapters.last()).single()
            assertEquals("https://b.example/5.mp4", stream.url)
            // Like Android, the API request headers (with the site Referer) are inherited by its streams.
            assertEquals("https://cms.example/", stream.headers["Referer"])
            assertEquals(details.chapters.first().id, details.adjacentChapter(details.chapters.last(), false)?.id)

            store.delete(sourceId)
            sources.sourcesChanged()
            assertEquals(emptyList(), sources.catalog().sources)
            assertFailsWith<Exception> { sources.page(sourceId, 0, 0, null, null) }
        } finally {
            reopened.close()
        }
    }

    @Test
    fun spiderOnlyConfigsAreRejectedWithAReason() = withLibrary { path ->
        val library = IosLibrary(path)
        try {
            val config = """{"sites":[{"key":"a","name":"A","type":3,"api":"csp_A"}]}"""
            val store = IosVideoSources(library, TvBoxHttp { _, _ -> config })
            val error = assertFailsWith<IllegalArgumentException> { store.importFrom("https://repo.example/tv.json", null) }
            assertEquals(true, error.message.orEmpty().contains("type 3/4"))
            assertEquals(emptyList(), store.list())
        } finally {
            library.close()
        }
    }
}
