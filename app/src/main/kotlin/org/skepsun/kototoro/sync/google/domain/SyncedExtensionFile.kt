package org.skepsun.kototoro.sync.google.domain

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun writeSyncedExtension(target: File, bytes: ByteArray, expectedSize: Long, readOnly: Boolean = false) {
    require(bytes.isNotEmpty() && (expectedSize <= 0 || bytes.size.toLong() == expectedSize)) {
        "Incomplete extension payload"
    }
    val staging = File.createTempFile(".sync-extension-", ".tmp", target.parentFile)
    try {
        staging.outputStream().use { output ->
            if (readOnly) check(staging.setReadOnly()) { "Cannot make restored APK read-only" }
            output.write(bytes)
            output.fd.sync()
        }
        // Open readers keep the old inode until the complete replacement is ready.
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        staging.delete()
    }
}
