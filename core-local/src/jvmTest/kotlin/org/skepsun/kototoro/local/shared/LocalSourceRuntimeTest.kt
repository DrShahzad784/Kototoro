package org.skepsun.kototoro.local.shared

import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceFilter
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSourceRuntimeTest {
    @TempDir
    lateinit var dir: File

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    private fun runtime() = LocalSourceRuntime(
        File(dir, "store").toOkioPath(), File(dir, "images").toOkioPath(),
        decodeLegacyText = { String(it, Charset.forName("GB18030")) },
    )

    private fun zip(name: String, entries: Map<String, ByteArray>): Path {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (entry, bytes) -> out.putNextEntry(ZipEntry(entry)); out.write(bytes); out.closeEntry() }
        }
        return file.toOkioPath()
    }

    /** Archives Kototoro writes on Android: index.json + `%08d_%04d%04d` entries for every chapter. */
    @Test
    fun kototoroArchiveKeepsItsChaptersAndMetadata() = runTest {
        val index = """{"id":1,"title":"下载的漫画","authors":["作者"],"description":"简介","source":"MANGADEX",
            |"tags":[{"key":"action","title":"Action"}],"cover_entry":"00000000_00020001.png",
            |"chapters":{"11":{"name":"第一话","number":1,"order":1,"url":"/c/1"},
            |"12":{"name":"第二话","number":2,"order":2,"url":"/c/2","entries":"00000000_0002\\d{4}"}}}""".trimMargin()
        val file = zip("downloaded.cbz", mapOf(
            "index.json" to index.encodeToByteArray(),
            "00000000_00010002.png" to png, "00000000_00010001.png" to png,
            "00000000_00020001.png" to png,
        ))
        val runtime = runtime()
        val imported = runtime.import(file, "downloaded.cbz")
        assertEquals("LOCAL", imported.source.name)
        val details = runtime.getDetails(imported, SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals("下载的漫画", details.title)
        assertEquals(setOf("作者"), details.authors)
        assertEquals(listOf("第一话", "第二话"), details.chapters!!.map { it.title })
        val pages = runtime.getPages(details.chapters!!.first(), null)
        assertEquals(listOf("00000000_00010001.png", "00000000_00010002.png"), pages.map { it.url.substringAfterLast('/') })
        val image = runtime.fetchImage(pages.first())
        assertTrue(File(dir, "images/${image.relativePath}").readBytes().contentEquals(png))
        assertEquals("image/png", image.contentType)
        assertEquals(image.sha256, runtime.fetchCover(details).sha256)
    }

    @Test
    fun plainArchiveFoldersBecomeChaptersInNaturalOrder() = runTest {
        val file = zip("folders.zip", mapOf(
            "Vol/ch10/1.jpg" to png, "Vol/ch2/10.jpg" to png, "Vol/ch2/2.jpg" to png,
            "__MACOSX/Vol/ch2/._2.jpg" to png, "notes.txt" to "x".encodeToByteArray(),
        ))
        val runtime = runtime()
        val details = runtime.getDetails(runtime.import(file, "folders.zip"), SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals("folders", details.title)
        assertEquals(listOf("ch2", "ch10"), details.chapters!!.map { it.title })
        assertEquals(listOf("2.jpg", "10.jpg"), runtime.getPages(details.chapters!!.first(), null).map { it.url.substringAfterLast('/') })
    }

    @Test
    fun epubChaptersUseTheTableOfContentsAndExtractPictures() = runTest {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>测试小说</dc:title><dc:creator>某人</dc:creator>
            |<meta name="cover" content="cover"/></metadata>
            |<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            |<item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
            |<item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
            |<item id="cover" href="images/cover.png" media-type="image/png"/></manifest>
            |<spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""".trimMargin()
        val nav = """<html><body><nav epub:type="toc"><ol><li><a href="text/c1.xhtml">第一章 开始</a></li>
            |<li><a href="text/c2.xhtml#p">第二章 &amp; 结束</a></li></ol></nav></body></html>""".trimMargin()
        val file = zip("book.epub", mapOf(
            "mimetype" to "application/epub+zip".encodeToByteArray(),
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".encodeToByteArray(),
            "OEBPS/content.opf" to opf.encodeToByteArray(),
            "OEBPS/nav.xhtml" to nav.encodeToByteArray(),
            "OEBPS/text/c1.xhtml" to "<html><body><p>你好</p><img src=\"../images/cover.png\"/></body></html>".encodeToByteArray(),
            "OEBPS/text/c2.xhtml" to "<html><body><p>再见</p></body></html>".encodeToByteArray(),
            "OEBPS/images/cover.png" to png,
        ))
        val runtime = runtime()
        val imported = runtime.import(file, "book.epub")
        assertEquals("LOCAL_NOVEL", imported.source.name)
        val details = runtime.getDetails(imported, SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals("测试小说", details.title)
        assertEquals(listOf("第一章 开始", "第二章 & 结束"), details.chapters!!.map { it.title })
        val text = runtime.getChapterContent(details.chapters!!.first(), null)
        assertTrue(text.html.contains("<p>你好</p>"), text.html)
        val image = text.images.single().url
        assertTrue(image.startsWith("file://"), image)
        assertTrue(text.html.contains(image))
        assertNotNull(runtime.fetchCover(details))
    }

    @Test
    fun legacyTextSplitsAtHeadings() = runTest {
        val body = "简介文字，介绍这本书的内容，足够长足够长。\n第一章 起点\n　　正文一。\n第二章 终点\n正文二。\n"
        val file = File(dir, "旧小说.txt").apply { writeBytes(body.toByteArray(Charset.forName("GB18030"))) }.toOkioPath()
        val runtime = runtime()
        val details = runtime.getDetails(runtime.import(file, "旧小说.txt"), SourceDetailsFetchMode.ALLOW_CACHE)
        assertEquals("旧小说", details.title)
        assertNull(details.coverUrl)
        assertEquals(listOf("前言", "第一章 起点", "第二章 终点"), details.chapters!!.map { it.title })
        assertEquals("<p>正文一。</p>", runtime.getChapterContent(details.chapters!![1], null).html)
    }

    @Test
    fun textWithoutHeadingsIsCutIntoParts() {
        val text = (1..3000).joinToString("\n") { "第$it 行的内容" }
        val parts = LocalBookReader.splitText(text)
        assertTrue(parts.size > 1)
        assertEquals(text.length, parts.last().textEnd)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.textEnd, b.textStart) }
    }

    @Test
    fun listingSearchReimportAndRemoval() = runTest {
        val runtime = runtime()
        val first = runtime.import(zip("a.cbz", mapOf("1.png" to png)), "a.cbz")
        runtime.import(zip("b.cbz", mapOf("1.png" to png)), "b.cbz")
        assertEquals(setOf("a", "b"), runtime.getList("LOCAL", 0, null, null).map { it.title }.toSet())
        assertEquals(listOf("a"), runtime.getList("LOCAL", 0, null, SourceFilter(query = "A")).map { it.title })
        assertTrue(runtime.getList("LOCAL_NOVEL", 0, null, null).isEmpty())
        // The same file again replaces the earlier copy.
        runtime.import(zip("a.cbz", mapOf("1.png" to png)), "a.cbz")
        assertEquals(2, runtime.getList("LOCAL", 0, null, null).size)
        val listed = runtime.getList("LOCAL", 0, LocalSourceRuntime.ORDER_TITLE, null)
        assertEquals(listOf("a", "b"), listed.map { it.title })
        runtime.remove(listed.first().id)
        assertEquals(listOf("b"), runtime.getList("LOCAL", 0, null, null).map { it.title })
        assertFalse(runtime.getList("LOCAL", 0, null, null).any { it.id == first.id })
    }

    @Test
    fun unsupportedOrBrokenFilesLeaveNothingBehind() = runTest {
        val runtime = runtime()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { runtime.import(zip("x.pdf", mapOf("1.png" to png)), "x.pdf") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { runtime.import(zip("empty.cbz", mapOf("readme.txt" to png)), "empty.cbz") }
        }
        assertTrue(runtime.getList("LOCAL", 0, null, null).isEmpty())
        assertTrue(File(dir, "store/items").listFiles().orEmpty().isEmpty())
    }
}
