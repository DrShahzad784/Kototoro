package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.SourceCall
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceChapterContent
import org.skepsun.kototoro.core.source.SourceContentImage
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceCoverArtifact
import org.skepsun.kototoro.core.source.SourceImageArtifact
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourcePageRequestContext
import org.skepsun.kototoro.core.source.SourceProtocolJson
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceRequest
import org.skepsun.kototoro.core.source.SourceResponse
import org.skepsun.kototoro.core.source.SourceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IosSourcesTest {
    private val source = SourceRef("MIHON_9007199254740993", "zh", "MANGA")
    private val chapter = SourceChapter(Long.MAX_VALUE, "第一话", 1f, 0, "/chapter/1", null, 0, null, source,
        sourceData = "{\"chapterToken\":\"opaque\"}")
    private val content = SourceContent(Long.MIN_VALUE, "漫画", emptySet(), "/manga", "https://fixture.invalid/manga",
        0f, null, "https://fixture.invalid/cover", emptySet(), null, setOf("作者"), source,
        sourceData = "{\"memo\":\"opaque\"}")
    private val page = SourcePage(9007199254740993, "mihon://image", null, source,
        headers = mapOf("Referer" to "https://fixture.invalid/chapter"),
        requestContext = SourcePageRequestContext(
            7, "https://fixture.invalid/chapter", "https://fixture.invalid/p#key",
        ),
    )
    private val hash = "a".repeat(64)

    @Test
    fun fullModelsSurviveSwiftProjectionAndImageIdentityIsChecked() = runBlocking {
        var closed = false
        val runtime = IosSources(object : IosSourceTransport {
            override fun exchange(requestJson: String): String {
                val request = SourceProtocolJson.decodeFromString<SourceRequest>(requestJson)
                val result = when (val call = request.call) {
                    is SourceCall.ListContent -> SourceResult.ListContent(listOf(content))
                    is SourceCall.Details -> {
                        assertEquals(content, call.content)
                        SourceResult.Details(content.copy(chapters = listOf(chapter)))
                    }
                    is SourceCall.Pages -> {
                        assertEquals(chapter, call.chapter)
                        SourceResult.Pages(listOf(page))
                    }
                    is SourceCall.Image -> {
                        assertEquals(page, call.page)
                        SourceResult.Image(SourceImageArtifact(source, page.id, "$hash.img", hash, 123, "image/png"))
                    }
                    is SourceCall.Cover -> {
                        assertEquals(content.copy(chapters = listOf(chapter)), call.content)
                        SourceResult.Cover(SourceCoverArtifact(source, content.id, "$hash.img", hash, 123, "image/png"))
                    }
                    else -> error("Unexpected call: $call")
                }
                return SourceProtocolJson.encodeToString(SourceResponse(1, request.requestId, result))
            }
            override fun close() { closed = true }
        })
        try {
            val listed = runtime.list(source.name, 0).single()
            assertEquals(Long.MIN_VALUE.toString(), listed.id)
            val details = runtime.details(listed)
            assertEquals(listOf("作者"), details.authors)
            val selected = details.chapters.single()
            assertEquals(Long.MAX_VALUE.toString(), selected.id)
            assertNull(details.adjacentChapter(selected, true))
            val selectedPage = runtime.pages(selected).single()
            assertEquals(page.id.toString(), selectedPage.id)
            assertEquals(IosSourceImage("$hash.img", hash, 123), runtime.image(selectedPage))
            assertEquals(IosSourceImage("$hash.img", hash, 123), runtime.cover(details))
        } finally {
            runtime.close()
        }
        assertEquals(true, closed)
    }

    @Test
    fun novelTextAndEcosystemsAreProjectedForSwift() = runBlocking {
        val novel = SourceRef("TSUNDOKU_42", "en", "HENTAI_NOVEL")
        val runtime = IosSources(object : IosSourceTransport {
            override fun exchange(requestJson: String): String {
                val request = SourceProtocolJson.decodeFromString<SourceRequest>(requestJson)
                val call = request.call as SourceCall.ChapterContent
                assertEquals(novel, call.chapter.source)
                val result = SourceResult.ChapterContent(SourceChapterContent("<p>第一段</p>",
                    listOf(SourceContentImage("https://fixture.invalid/i.png", mapOf("Referer" to "x")))))
                return SourceProtocolJson.encodeToString(SourceResponse(1, request.requestId, result))
            }
            override fun close() {}
        })
        val text = runtime.chapterText(IosSourceChapter(chapter.copy(source = novel)))!!
        assertEquals("<p>第一段</p>", text.html)
        assertEquals(listOf("https://fixture.invalid/i.png"), text.images)
        assertEquals(mapOf("Referer" to "x"), text.headers("https://fixture.invalid/i.png"))
        val listed = IosSource(novel.name, novel.name, "en", novel.contentType, "extension")
        assertEquals("TSUNDOKU", listed.ecosystem)
        assertEquals("NOVEL", listed.mediaKind)
        assertEquals(true, listed.isAdult)
        assertEquals("JAR", IosSource("MANGADEX", "", "", "MANGA", "extension").ecosystem)
        assertEquals("ANIYOMI", IosSource("ANIYOMI_1", "", "", "VIDEO", "extension").ecosystem)
        assertEquals("CMS", IosSource("JSON_TVBOX_A", "", "", "VIDEO", "video").ecosystem)
        assertEquals("VIDEO", IosSourceContent(content.copy(source = SourceRef("ANIYOMI_1", "", "HENTAI_VIDEO"))).mediaKind)
    }

    @Test
    fun imagesFromAnotherContentOrSourceAreRejected() = runBlocking {
        val runtime = IosSources(object : IosSourceTransport {
            override fun exchange(requestJson: String): String {
                val request = SourceProtocolJson.decodeFromString<SourceRequest>(requestJson)
                val result = when (request.call) {
                    is SourceCall.Image -> SourceResult.Image(
                        SourceImageArtifact(source.copy(name = "OTHER"), page.id, "$hash.img", hash, 1, "image/png"),
                    )
                    is SourceCall.Cover -> SourceResult.Cover(
                        SourceCoverArtifact(source, content.id + 1, "$hash.img", hash, 1, "image/png"),
                    )
                    else -> error("Unexpected call")
                }
                return SourceProtocolJson.encodeToString(SourceResponse(1, request.requestId, result))
            }
            override fun close() {}
        })
        try {
            assertFailsWith<IllegalStateException> { runtime.image(IosSourcePage(page)) }
            assertFailsWith<IllegalStateException> { runtime.cover(IosSourceContent(content)) }
            Unit
        } finally {
            runtime.close()
        }
    }
}
