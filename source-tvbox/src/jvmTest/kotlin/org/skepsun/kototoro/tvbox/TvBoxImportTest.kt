package org.skepsun.kototoro.tvbox

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvBoxImportTest {
    private fun http(vararg pages: Pair<String, String>) = TvBoxHttp { url, _ ->
        pages.toMap()[url] ?: throw IllegalStateException("HTTP 404")
    }

    @Test
    fun importsCmsSitesWithAndroidCompatibleIdsAndConfig() = runTest {
        val document = """
            // comment line
            {"spider":"./jar/spider.jar","logo":"https://repo.example/logo.png","lives":[],
             "sites":[
               {"key":"cms","name":"影视CMS","type":1,"api":"https://cms.example/api.php/provide/vod/","header":{"User-Agent":"tv"}},
               {"key":"xml","name":"XML站","type":0,"api":"https://xml.example/api.php/provide/vod/at/xml"},
               {"key":"csp_A","name":"爬虫","type":3,"api":"csp_A"},
               {"key":"js","name":"QuickJS","type":4,"api":"https://js.example/a.js"},
             ]}
        """.trimIndent()
        val result = TvBoxImport.importDocument(http(), document, "https://repo.example/tv.json", null)
        assertEquals(listOf("影视CMS", "XML站"), result.sources.map { it.name })
        assertEquals(2, result.skippedSpiderSites)
        val first = result.sources.first()
        val fingerprint = "https://repo.example/tv.json|cms|影视CMS|https://cms.example/api.php/provide/vod/|"
        assertEquals("JSON_TVBOX_" + fingerprint.hashCode().toUInt().toString(16).uppercase(), first.id)
        val config = TvBoxJson.parseToJsonElement(first.config).jsonObject
        assertEquals(setOf("schemaVersion", "importType", "site", "root", "meta"), config.keys)
        assertEquals(setOf("spider", "logo", "lives"), (config["root"] as JsonObject).keys)
        assertEquals("tv", first.stored.staticHeaders["User-Agent"])
        assertEquals("https://repo.example/tv.json", first.stored.sourceLocator)
    }

    @Test
    fun followsMultiRepositoriesAndBase64Wrappers() = runTest {
        val child = """{"sites":[{"key":"a","name":"A","type":1,"api":"https://a.example/api.php/provide/vod"}]}"""
        // Real repositories hide the config after JPEG bytes; MIME-style line breaks may appear in the payload.
        val wrapped = "\u00FF\u00D8\u00FFJFIF\uFFFD binary abcdEFGH**" +
            java.util.Base64.getMimeEncoder().encodeToString(child.toByteArray()).trimEnd('=')
        val root = """{"urls":[{"url":"https://repo.example/a.json","name":"仓A"},"https://repo.example/a.json"]}"""
        val result = TvBoxImport.importFromUrl(
            http("https://repo.example/multi.json" to root, "https://repo.example/a.json" to wrapped),
            "https://repo.example/multi.json",
        )
        assertEquals(listOf("A"), result.sources.map { it.name })
        assertEquals("仓A", result.sources.single().stored.sourceTitle)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun macCmsApiUrlBecomesSingleSite() = runTest {
        assertEquals(
            "https://cms.example/api.php/provide/vod/",
            TvBoxImport.macCmsApiUrl("https://cms.example/api.php/provide/vod/?ac=list"),
        )
        assertNull(TvBoxImport.macCmsApiUrl("https://repo.example/tv.json"))
        val result = TvBoxImport.importFromUrl(http(), "https://cms.example/api.php/provide/vod/?ac=list", "我的源")
        val source = result.sources.single()
        assertEquals("我的源", source.name)
        assertEquals(1, source.stored.type)
        assertEquals("https://cms.example/api.php/provide/vod/", source.stored.api)
        // A non-CMS-looking URL that answers with a CMS payload is also accepted.
        val payload = """{"code":1,"page":1,"list":[]}"""
        val detected = TvBoxImport.importFromUrl(http("https://x.example/vod" to payload), "https://x.example/vod")
        assertEquals("https://x.example/vod", detected.sources.single().stored.api)
    }
}
