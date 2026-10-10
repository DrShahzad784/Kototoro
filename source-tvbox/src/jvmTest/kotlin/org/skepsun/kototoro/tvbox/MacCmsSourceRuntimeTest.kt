package org.skepsun.kototoro.tvbox

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceEndpoint
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceProtocolClient
import org.skepsun.kototoro.core.source.SourceUnavailableException

class MacCmsSourceRuntimeTest {
    private val api = "https://cms.example/api.php/provide/vod/"

    private val listJson = """
        {"code":1,"page":1,"pagecount":3,"total":2,
         "class":[{"type_id":1,"type_name":"电影"},{"type_id":2,"type_name":"连续剧"}],
         "list":[
           {"vod_id":42,"vod_name":"星际 旅程","type_id":2,"type_name":"连续剧","vod_pic":"/upload/42.jpg",
            "vod_remarks":"更新至2集","vod_play_from":"线路A${'$'}${'$'}${'$'}线路B",
            "vod_play_url":"第1集${'$'}https://v.example/42/1.m3u8#第2集${'$'}https://v.example/42/2.m3u8${'$'}${'$'}${'$'}第1集${'$'}https://b.example/42/1.mp4"},
           {"vod_id":"7","vod_name":"Alpha","type_id":1,"type_name":"电影","vod_pic":"https://img.example/7.jpg"}
         ]}
    """.trimIndent()

    private val detailJson = """
        {"list":[{"vod_id":7,"vod_name":"Alpha","type_id":1,"type_name":"电影","vod_content":"<p>简介</p>",
          "vod_play_from":"m3u8","vod_play_url":"正片${'$'}https://v.example/7.m3u8#网页${'$'}https://web.example/play/7"}]}
    """.trimIndent()

    private val xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE rss>
        <rss version="5.1"><list page="1" pagecount="1" pagesize="20" recordcount="1">
          <video><last>2026-10-10</last><id>9</id><tid>3</tid><name><![CDATA[动画 & 冒险]]></name>
            <type>动漫</type><pic>https://img.example/9.jpg</pic><note><![CDATA[完结]]></note>
            <dl><dd flag="ckm3u8"><![CDATA[第01集${'$'}https://v.example/9/1.m3u8#第02集${'$'}https://v.example/9/2.m3u8]]></dd></dl>
            <des><![CDATA[一部 &lt;动画&gt;]]></des></video>
        </list><class><ty id="3">动漫</ty></class></rss>
    """.trimIndent()

    private val source = TvBoxImport.sourceId("https://repo.example/tv.json", kotlinx.serialization.json.buildJsonObject {})
        .let { TvBoxSource(it, "示例影视", siteConfig(api)) }

    private fun siteConfig(api: String, type: Int = 1) = """
        {"schemaVersion":1,"importType":"tvbox_site",
         "site":{"key":"cms","name":"示例影视","type":$type,"api":"$api","header":{"User-Agent":"okhttp/3"}},
         "root":{"headers":[{"host":"v.example","header":{"Referer":"https://cms.example/"}}]},
         "meta":{"sourceLocator":"https://repo.example/tv.json"}}
    """.trimIndent()

    private class FakeHttp(private val responses: (String) -> String?) : TvBoxHttp {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun get(url: String, headers: Map<String, String>): String {
            requests += url to headers
            return responses(url) ?: throw IllegalStateException("HTTP 404")
        }
    }

    private fun runtime(http: TvBoxHttp, vararg sources: TvBoxSource) = MacCmsSourceRuntime(http) { sources.toList() }

    @Test
    fun listsDetailsAndPlaysJsonCms() = runTest {
        val http = FakeHttp { url ->
            when {
                url == api -> listJson
                "ac=videolist" in url && "pg=1" in url -> listJson
                "ac=detail" in url && "ids=7" in url -> detailJson
                else -> null
            }
        }
        val runtime = runtime(http, source)
        val filters = runtime.getFilterOptions(source.id)
        assertEquals(listOf("电影", "连续剧"), filters.availableTags.map { it.title })
        assertEquals("cms_type:1", filters.availableTags.first().key)

        val list = runtime.getList(source.id, 0, MacCmsSourceRuntime.ORDER_NEWEST, null)
        assertEquals(listOf("星际 旅程", "Alpha"), list.map { it.title })
        val series = list.first()
        assertEquals("tvbox://item/cms:42?id=42", series.url)
        assertEquals(MacCmsSourceRuntime.identity(source.id, "item", "cms:42"), series.id)
        assertEquals("https://cms.example/upload/42.jpg", series.coverUrl)
        // Default order is Android's alphabetical order.
        assertEquals(listOf("Alpha", "星际 旅程"), runtime.getList(source.id, 0, null, null).map { it.title })

        // Items listed with play URLs are served from the cache, keeping line names for the UI.
        val details = runtime.getDetails(series, SourceDetailsFetchMode.ALLOW_CACHE)
        val chapters = details.chapters.orEmpty()
        assertEquals(listOf("线路A - 第1集", "线路A - 第2集", "线路B - 第1集"), chapters.map { it.title })
        assertEquals("""{"line":"线路B"}""", chapters[2].sourceData)
        val streamToken = MacCmsParser.positiveHash("星际 旅程|线路A - 第1集|https://v.example/42/1.m3u8").toString()
        assertEquals(MacCmsSourceRuntime.identity(source.id, "chapter", "cms:42|$streamToken|0"), chapters[0].id)
        assertEquals(
            "tvbox://chapter/cms:42/$streamToken?id=42&play=https%3A%2F%2Fv.example%2F42%2F1.m3u8",
            chapters[0].url,
        )
        val page = runtime.getPages(chapters[0], null).single()
        assertEquals("https://v.example/42/1.m3u8", page.url)
        assertEquals("https://cms.example/", page.headers?.get("Referer"))
        assertEquals("okhttp/3", page.headers?.get("User-Agent"))
        assertEquals(MacCmsSourceRuntime.identity(source.id, "page", chapters[0].url), page.id)

        // Items without play URLs fetch details; non-media links are dropped like Android.
        val movie = runtime.getDetails(list[1], SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals(listOf("正片"), movie.chapters.orEmpty().map { it.title })
        assertEquals("""{"line":"m3u8"}""", movie.chapters!!.single().sourceData)
        assertEquals("<p>简介</p>", movie.description)
    }

    @Test
    fun identicalExternalIdsFromDifferentSitesHaveIndependentRecordIdentities() = runTest {
        val other = TvBoxSource("JSON_TVBOX_OTHER", "另一站点", siteConfig("https://other.example/api/"))
        val runtime = runtime(FakeHttp { listJson }, source, other)
        val first = runtime.getList(source.id, 0, null, null).first { it.title == "星际 旅程" }
        val second = runtime.getList(other.id, 0, null, null).first { it.title == first.title }
        assertEquals(first.url, second.url)
        assertTrue(first.id != second.id)
        val firstChapter = runtime.getDetails(first, SourceDetailsFetchMode.ALLOW_CACHE).chapters!!.first()
        val secondChapter = runtime.getDetails(second, SourceDetailsFetchMode.ALLOW_CACHE).chapters!!.first()
        assertTrue(firstChapter.id != secondChapter.id)
        assertTrue(runtime.getPages(firstChapter, null).single().id != runtime.getPages(secondChapter, null).single().id)
        assertEquals(first.id, runtime.getList(source.id, 0, null, null).first { it.title == first.title }.id)
    }

    @Test
    fun searchAndCategoryUseCmsParametersAndClientFiltering() = runTest {
        val http = FakeHttp { url -> if (url == api || "wd=" in url || "t=2" in url) listJson else null }
        val runtime = runtime(http, source)
        val found = runtime.getList(source.id, 1, MacCmsSourceRuntime.ORDER_RELEVANCE, SourceFilter(query = "星际 旅程"))
        assertEquals(listOf("星际 旅程"), found.map { it.title })
        assertTrue(http.requests.any { it.first == "${api}?pg=2&wd=%E6%98%9F%E9%99%85%20%E6%97%85%E7%A8%8B" })

        val series = runtime.getFilterOptions(source.id).availableTags.first { it.title == "连续剧" }
        val category = runtime.getList(source.id, 0, null, SourceFilter(tags = setOf(series)))
        assertEquals(listOf("星际 旅程"), category.map { it.title })
        assertTrue(http.requests.any { it.first == "${api}?ac=videolist&pg=1&t=2" })
    }

    @Test
    fun xmlCmsAndProtocolRoundTrip() = runTest {
        val xmlApi = "https://xml.example/api.php/provide/vod/at/xml/"
        val xmlSource = TvBoxSource("JSON_TVBOX_XML", "XML", siteConfig(xmlApi, type = 0))
        val runtime = runtime(FakeHttp { if (it.startsWith(xmlApi)) xml else null }, xmlSource)
        val client = SourceProtocolClient(SourceEndpoint(runtime)) { "r" + System.nanoTime() }
        val item = client.getList(xmlSource.id, 0, null, null).single()
        assertEquals("动画 & 冒险", item.title)
        assertEquals("完结", item.description)
        assertEquals("动漫", item.tags.single().title)
        val details = client.getDetails(item, SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals(listOf("第01集", "第02集"), details.chapters.orEmpty().map { it.title })
        assertEquals("https://v.example/9/2.m3u8", client.getPages(details.chapters!![1], null).single().url)
        assertEquals("VIDEO", client.getSources().single().contentType)
    }

    @Test
    fun transientPageFailurePropagatesInsteadOfSilentlyEndingPagination() = runTest {
        var pageTwoAvailable = false
        val pageTwoJson = """{"code":1,"page":2,"pagecount":2,"list":[{"vod_id":99,"vod_name":"第二页影片","type_id":1,"type_name":"电影"}]}"""
        val http = FakeHttp { url ->
            when {
                url == api || "pg=1" in url -> listJson
                "pg=2" in url && pageTwoAvailable -> pageTwoJson
                else -> null
            }
        }
        val runtime = runtime(http, source)
        assertEquals(2, runtime.getList(source.id, 0, null, null).size)
        assertThrows<IllegalStateException> { runtime.getList(source.id, 1, null, null) }
        pageTwoAvailable = true
        assertEquals(listOf("第二页影片"), runtime.getList(source.id, 1, null, null).map { it.title })
    }

    @Test
    fun spiderSitesAndUnknownNamesAreUnavailable() = runTest {
        val spider = TvBoxSource("JSON_TVBOX_SPIDER", "Spider", siteConfig("csp_Demo", type = 3))
        val runtime = runtime(FakeHttp { null }, spider)
        assertTrue(runtime.getSources().isEmpty())
        assertThrows<SourceUnavailableException> { runtime.describe(spider.id) }
        assertThrows<SourceUnavailableException> { runtime.describe("JSON_TVBOX_MISSING") }
    }
}
