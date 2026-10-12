package org.skepsun.kototoro.local.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class LocalSafArchiveCacheTest {

    @TempDir
    lateinit var directory: File

    @Test
    fun `indexing multiple archives does not leave complete copies in cache`() = runTest {
        val original = archive()
        val originalBytes = original.readBytes()
        repeat(3) { index ->
            useArchive(original, "$index.cbz") { copy ->
                ZipFile(copy).use { zip ->
                    assertEquals("page", zip.getInputStream(zip.getEntry("1.jpg")).readBytes().decodeToString())
                }
            }
            assertTrue(copies().isEmpty(), "Completed indexing must release the archive copy")
        }
        assertArrayEquals(originalBytes, original.readBytes())
    }

    @Test
    fun `failed parsing releases its copy`() = runTest {
        val original = archive()
        val failure = runCatching { useArchive(original) { error("Invalid index") } }.exceptionOrNull()
        assertEquals("Invalid index", failure?.message)
        assertTrue(copies().isEmpty())
    }

    @Test
    fun `directory removal clears legacy copies but retains an active parser file`() = runTest {
        val legacy = File(directory, "local_saf/old.cbz").apply { parentFile?.mkdirs(); writeText("old") }
        val original = archive()
        useArchive(original) { active ->
            LocalSafArchiveCache.clearUnused(directory)
            assertTrue(active.isFile)
            assertTrue(!legacy.exists(), "Removing a directory must reclaim inactive legacy copies")
            ZipFile(active).use { assertEquals(1, it.size()) }
        }
        assertTrue(copies().isEmpty())
    }

    @Test
    fun `concurrent scans of one archive retain their copy until each scan completes`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val original = archive()
        val first = async {
            useArchive(original) { copy ->
                entered.complete(Unit)
                finishFirst.await()
                assertTrue(copy.isFile)
            }
        }
        entered.await()
        val second = async { useArchive(original) { copy -> assertTrue(copy.isFile) } }
        LocalSafArchiveCache.clearUnused(directory)
        finishFirst.complete(Unit)
        first.await()
        second.await()
        assertTrue(copies().isEmpty())
    }

    @Test
    fun `cancelling a directory scan also releases its active copy`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val neverFinishes = CompletableDeferred<Unit>()
        val original = archive()
        val scan = async {
            useArchive(original) {
                entered.complete(Unit)
                neverFinishes.await()
            }
        }
        entered.await()
        scan.cancelAndJoin()
        assertTrue(copies().isEmpty())
        assertTrue(original.isFile)
    }

    private suspend fun <T> useArchive(
        source: File,
        fileName: String = "source.cbz",
        action: suspend (File) -> T,
    ): T = LocalSafArchiveCache.withArchive(
        directory, fileName, source.length(), source.lastModified(), openInput = source::inputStream, action = action,
    )

    private fun copies() = File(directory, "local_saf").listFiles().orEmpty().toList()

    private fun archive() = File(directory, "original.cbz").apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("1.jpg"))
            zip.write("page".toByteArray())
            zip.closeEntry()
        }
    }
}
