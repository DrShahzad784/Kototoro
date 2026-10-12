package org.skepsun.kototoro.sync.google.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.data.model.SyncExtensionPackage

class GoogleDriveSyncExtensionRestoreTest {

    @Test
    fun `upload retains packages skipped or failed during restore across repeated syncs`() {
        val restored = extension("restored", payload = "YQ==")
        val tooLarge = extension("too-large", size = 6 * 1024 * 1024L)
        val failed = extension("failed", payload = "Yg==")
        val remote = GoogleDriveSyncSnapshot(extensions = listOf(restored, tooLarge, failed))
        val applied = GoogleDriveSyncSnapshot(extensions = listOf(restored))
        var upload = remote

        repeat(2) {
            val merged = GoogleDriveSyncMerger.mergeSnapshots(applied, upload)
            upload = GoogleDriveSyncMerger.buildUploadSnapshot(applied, merged)
            assertEquals(setOf("restored", "too-large", "failed"), upload.extensions.map { it.packageId }.toSet())
            assertSame(tooLarge, upload.extensions.single { it.packageId == "too-large" })
            assertEquals("Yg==", upload.extensions.single { it.packageId == "failed" }.payloadBase64)
        }
    }

    @Test
    fun `upload keeps local newer package and does not downgrade newer remote metadata`() {
        val applied = GoogleDriveSyncSnapshot(extensions = listOf(
            extension("local-newer", version = 3, payload = "YQ=="),
            extension("remote-newer", version = 1, payload = "Yg=="),
        ))
        val remote = GoogleDriveSyncSnapshot(extensions = listOf(
            extension("local-newer", version = 2, payload = "Yw=="),
            extension("remote-newer", version = 4, size = 6 * 1024 * 1024L),
        ))

        val upload = GoogleDriveSyncMerger.buildUploadSnapshot(
            applied,
            GoogleDriveSyncMerger.mergeSnapshots(applied, remote),
        )

        assertEquals(3L, upload.extensions.single { it.packageId == "local-newer" }.versionCode)
        assertEquals(4L, upload.extensions.single { it.packageId == "remote-newer" }.versionCode)
    }

    private fun extension(
        id: String,
        version: Long = 1,
        payload: String? = null,
        size: Long = 1,
    ) = SyncExtensionPackage(
        packageId = id,
        name = id,
        kind = "jar",
        versionCode = version,
        sizeBytes = size,
        payloadBase64 = payload,
        isPayloadIncluded = payload != null,
    )
}
