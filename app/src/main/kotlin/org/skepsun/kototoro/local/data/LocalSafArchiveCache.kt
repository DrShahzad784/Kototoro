package org.skepsun.kototoro.local.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.util.MultiMutex
import java.io.File
import java.io.InputStream

/** Owns the local copies needed while a SAF archive parser requires random access. */
internal object LocalSafArchiveCache {

    private val locks = MultiMutex<File>()
    private val activeFiles = mutableSetOf<File>()

    suspend fun <T> withArchive(
        cacheDir: File,
        fileName: String,
        sourceLength: Long,
        sourceModified: Long,
        forceRefresh: Boolean = false,
        openInput: () -> InputStream,
        action: suspend (File) -> T,
    ): T {
        val target = File(File(cacheDir, "local_saf"), fileName).absoluteFile
        return locks.withLock(target) {
            try {
                runInterruptible(Dispatchers.IO) {
                    synchronized(activeFiles) {
                        activeFiles += target
                        target.parentFile?.mkdirs()
                        clearUnused(cacheDir)
                    }
                    if (forceRefresh || !target.isFile ||
                        sourceLength >= 0 && target.length() != sourceLength ||
                        sourceModified > 0 && target.lastModified() != sourceModified
                    ) {
                        openInput().use { input -> target.outputStream().use(input::copyTo) }
                        require(sourceLength <= 0 || target.length() == sourceLength) { "Incomplete SAF archive copy" }
                        if (sourceModified > 0) target.setLastModified(sourceModified)
                    }
                }
                action(target)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    synchronized(activeFiles) {
                        target.delete()
                        activeFiles -= target
                    }
                }
            }
        }
    }

    fun clearUnused(cacheDir: File) = synchronized(activeFiles) {
        File(cacheDir, "local_saf").listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.absoluteFile !in activeFiles) file.delete()
        }
    }
}
