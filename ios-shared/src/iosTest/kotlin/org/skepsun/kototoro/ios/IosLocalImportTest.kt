@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosLocalImportTest {

    @Test
    fun importedArchiveAndLegacyTextReadThroughTheSourceScreens() = runBlocking {
        val dir = NSTemporaryDirectory() + NSUUID().UUIDString
        check(NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null))
        try {
            val sources = IosSources(null, null, "$dir/local", "$dir/images")
            val catalog = sources.catalog().sources
            assertEquals(listOf("LOCAL", "LOCAL_NOVEL"), catalog.map { it.name })
            assertTrue(catalog.all { it.ecosystem == "LOCAL" })

            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
            BackupZipWriter("$dir/漫画.cbz").use { zip ->
                for (name in listOf("2.png", "10.png", "1.png")) { zip.begin(name); zip.write(png); zip.end() }
                zip.finish()
            }
            val manga = sources.details(sources.importLocal("$dir/漫画.cbz", "漫画.cbz"))
            assertEquals("漫画", manga.title)
            assertTrue(sources.isLocal(manga))
            val pages = sources.pages(manga.chapters.single())
            assertEquals(3, pages.size)
            val image = sources.image(pages.first())
            assertTrue(FileSystem.SYSTEM.exists("$dir/images/${image.relativePath}".toPath()))
            assertEquals(image.sha256, sources.cover(manga).sha256)

            val text = "第一章 开端\n　　正文。\n第二章 结尾\n完。\n"
            val gb18030 = NSString.create(string = text).dataUsingEncoding(CFStringConvertEncodingToNSStringEncoding(0x0632u))!!
            check(gb18030.writeToFile("$dir/小说.txt", true))
            val novel = sources.details(sources.importLocal("$dir/小说.txt", "小说.txt"))
            assertEquals("LOCAL_NOVEL", novel.sourceName)
            assertEquals(listOf("第一章 开端", "第二章 结尾"), novel.chapters.map { it.title })
            assertEquals("<p>正文。</p>", sources.chapterText(novel.chapters.first())?.html)

            assertEquals(listOf("漫画"), sources.page("LOCAL", 0, 0, null, null).map { it.title })
            sources.removeLocal(manga)
            assertTrue(sources.page("LOCAL", 0, 0, null, null).isEmpty())
        } finally { NSFileManager.defaultManager.removeItemAtPath(dir, null) }
    }
}
