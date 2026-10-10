@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IosLibraryTest {
    @Test
    fun categoriesSurviveReopeningAndBlankTitlesDoNotWrite() = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        val path = "$directory/library.db"
        try {
            val first = IosLibrary(path)
            val savedId = try {
                assertEquals(84, first.schemaVersion)
                assertEquals(emptyList(), first.categories())
                first.addCategory("  我的漫画  ")
                assertFailsWith<IllegalArgumentException> { first.addCategory(" \n ") }
                first.categories().single().id
            } finally {
                first.close()
            }
            val reopened = IosLibrary(path)
            try {
                assertEquals(listOf(IosCategory(savedId, "我的漫画")), reopened.categories())
                reopened.addCategory("待读")
                assertEquals(listOf("我的漫画", "待读"), reopened.categories().map { it.title })
            } finally {
                reopened.close()
            }
        } finally {
            files.removeItemAtPath(directory, null)
        }
    }

    @Test
    fun relativePathsAreRejected() {
        assertFailsWith<IllegalArgumentException> { IosLibrary("library.db") }
    }
}
