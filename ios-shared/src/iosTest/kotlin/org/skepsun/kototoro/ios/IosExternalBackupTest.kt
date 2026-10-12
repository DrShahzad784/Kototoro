@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package org.skepsun.kototoro.ios

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.FileSystem
import okio.GzipSink
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import org.skepsun.kototoro.backups.external.AniyomiBackup
import org.skepsun.kototoro.backups.external.AniyomiBackupAnime
import org.skepsun.kototoro.backups.external.ExternalBackupApp
import org.skepsun.kototoro.backups.external.ExternalBackupContentRecord
import org.skepsun.kototoro.backups.external.ExternalLibraryImport
import org.skepsun.kototoro.backups.external.MihonBackup
import org.skepsun.kototoro.backups.external.MihonBackupCategory
import org.skepsun.kototoro.backups.external.MihonBackupChapter
import org.skepsun.kototoro.backups.external.MihonBackupHistory
import org.skepsun.kototoro.backups.external.MihonBackupManga
import org.skepsun.kototoro.backups.external.MihonBackupSource
import org.skepsun.kototoro.parsers.model.ContentType
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosExternalBackupTest {

    /** Android computes ids with Java's String.hashCode; Kotlin/Native must give the same rows for the same backup. */
    @Test
    fun importedIdsMatchAndroid() {
        val record = ExternalBackupContentRecord(ExternalBackupApp.MIHON, "MIHON_123", ContentType.MANGA, "/manga/追更",
            "", null, null, emptyList(), null, "", null, false, null, emptyList(), 0, 0, null, null, null)
        assertEquals(1295391419L, ExternalLibraryImport.generateContentId(record))
        assertEquals(1566393934L, ExternalLibraryImport.generateChapterId(record.copy(sourceName = "MANGADEX"), "venera:abc::3"))
    }

    @Test
    fun mihonBackupImportsFavouritesCategoriesAndHistory() = runBlocking {
        withDirectory { dir ->
            val backup = MihonBackup(
                backupManga = listOf(
                    MihonBackupManga(source = 123, url = "/manga/追更", title = "追更作品", genre = listOf("Action"),
                        dateAdded = 1_700_000_000_000, favorite = true, categories = listOf(1),
                        chapters = listOf(MihonBackupChapter("/c/1", "第1话", read = true), MihonBackupChapter("/c/2", "第2话")),
                        history = listOf(MihonBackupHistory("/c/1", 1_700_000_100_000))),
                    MihonBackupManga(source = 5, url = "/manga/installed", title = "已安装来源作品", favorite = true),
                ),
                backupCategories = listOf(MihonBackupCategory("追更", order = 1, id = 7)),
                backupSources = listOf(MihonBackupSource("Some Source", 123)),
            )
            val path = "$dir/library.tachibk"
            FileSystem.SYSTEM.write(path.toPath()) { write(gzip(ProtoBuf.encodeToByteArray(MihonBackup.serializer(), backup))) }
            val library = IosLibrary("$dir/a.db")
            try {
                assertFalse(library.isKototoroBackup(path))
                val preview = library.previewExternalBackup(path)
                assertEquals("Mihon", preview.app)
                assertEquals(2, preview.favourites)
                assertEquals(1, preview.history)
                assertEquals(1, preview.categories)
                val result = library.importExternalBackup(preview, listOf(IosSource("MIHON_5", "Five", "zh", "MANGA", "extension")))
                assertEquals(2, result.favourites)
                assertEquals(1, result.history)
                assertEquals(listOf("Some Source（1）"), result.uninstalledSources)
                assertEquals(setOf("追更作品", "已安装来源作品"), library.favourites().map { it.content.title }.toSet())
                assertEquals(listOf("追更作品"), library.history().map { it.content.title })
                assertTrue(library.categories().any { it.title == "追更" })
                // Importing again merges into the same rows.
                library.importExternalBackup(library.previewExternalBackup(path), emptyList())
                assertEquals(2, library.favourites().size)
            } finally { library.close() }
        }
    }

    @Test
    fun aniyomiBackupIsDetectedByItsAnime() = runBlocking {
        withDirectory { dir ->
            val backup = AniyomiBackup(backupAnime = listOf(AniyomiBackupAnime(source = 42, url = "/anime/1", title = "动画")))
            val path = "$dir/anime.tachibk"
            FileSystem.SYSTEM.write(path.toPath()) { write(ProtoBuf.encodeToByteArray(AniyomiBackup.serializer(), backup)) }
            val library = IosLibrary("$dir/a.db")
            try {
                val preview = library.previewExternalBackup(path)
                assertEquals("Aniyomi", preview.app)
                assertEquals(1, library.importExternalBackup(preview, emptyList()).favourites)
                assertEquals(listOf("VIDEO"), library.favourites().map { it.content.contentType })
            } finally { library.close() }
        }
    }

    @Test
    fun veneraBackupMatchesInstalledSourcesByName() = runBlocking {
        withDirectory { dir ->
            val favourites = "$dir/local_favorite.db"
            BundledSQLiteDriver().open(favourites).also { db ->
                db.execSQL("CREATE TABLE `默认` (id TEXT, name TEXT, author TEXT, type INTEGER, tags TEXT, cover_path TEXT, time INTEGER)")
                db.execSQL("INSERT INTO `默认` VALUES ('md-1', 'MangaDex 作品', '作者', 577718694, 'a,b', 'https://c/1.jpg', 1700000000)")
                db.execSQL("INSERT INTO `默认` VALUES ('x-1', '未知来源作品', NULL, 12345, '', NULL, 1700000000)")
            }.close()
            val history = "$dir/history.db"
            BundledSQLiteDriver().open(history).also { db ->
                db.execSQL("CREATE TABLE history (id TEXT, title TEXT, subtitle TEXT, cover TEXT, time INTEGER, type INTEGER, ep INTEGER, page INTEGER, max_page INTEGER)")
                db.execSQL("INSERT INTO history VALUES ('md-1', 'MangaDex 作品', '', '', 1700000500000, 577718694, 3, 1, 10)")
            }.close()
            val path = "$dir/venera.zip"
            BackupZipWriter(path).use { zip ->
                for ((name, file) in listOf("data/local_favorite.db" to favourites, "data/history.db" to history)) {
                    zip.begin(name)
                    zip.write(FileSystem.SYSTEM.read(file.toPath()) { readByteArray() })
                    zip.end()
                }
                zip.finish()
            }
            val library = IosLibrary("$dir/a.db")
            try {
                assertFalse(library.isKototoroBackup(path))
                val preview = library.previewExternalBackup(path)
                assertEquals("Venera", preview.app)
                val result = library.importExternalBackup(preview, listOf(IosSource("MANGADEX", "MangaDex", "", "MANGA", "extension")))
                assertEquals(1, result.favourites)
                assertEquals(1, result.history)
                assertEquals(listOf("未知来源作品"), result.failedTitles)
                val entry = library.favourites().single()
                assertEquals("MANGADEX", entry.content.sourceName)
                assertEquals(listOf("MangaDex 作品"), library.history().map { it.content.title })
            } finally { library.close() }
        }
    }

    @Test
    fun unknownFilesAreRejected() = runBlocking {
        withDirectory { dir ->
            val path = "$dir/random.bin"
            FileSystem.SYSTEM.write(path.toPath()) { writeUtf8("not a backup at all") }
            val library = IosLibrary("$dir/a.db")
            try {
                val error = runCatching { library.previewExternalBackup(path) }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException, "$error")
            } finally { library.close() }
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = Buffer()
        GzipSink(output).buffer().use { it.write(bytes) }
        return output.readByteArray()
    }

    private suspend fun withDirectory(block: suspend (String) -> Unit) {
        val dir = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(dir, true, null, null))
        try { block(dir) } finally { files.removeItemAtPath(dir, null) }
    }
}
