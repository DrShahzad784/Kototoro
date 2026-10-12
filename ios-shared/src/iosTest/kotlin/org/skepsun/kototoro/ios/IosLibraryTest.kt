@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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

    @Test
    fun bulkCategoriesPreserveDetailsAndUntouchedMembershipsAndRollBackCollisions() = runBlocking {
        val files = NSFileManager.defaultManager
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        check(files.createDirectoryAtPath(directory, true, null, null))
        val path = "$directory/library.db"
        val source = SourceRef("MIHON_test", "zh", "MANGA")
        val first = SourceContent(101, "第一部", emptySet(), "/first", "https://fixture.invalid/first",
            0f, null, null, emptySet(), null, emptySet(), source, description = "保留完整详情")
        val second = first.copy(id = 102, title = "第二部", url = "/second", publicUrl = "https://fixture.invalid/second")
        try {
            val library = IosLibrary(path)
            try {
                for (title in listOf("待读", "在读", "漫画")) library.addCategory(title)
                val ids = library.categories().map { it.id }
                library.setCategories(IosSourceContent(first), listOf(ids[0]))
                library.setCategories(IosSourceContent(second), listOf(ids[1]))
                val sparse = listOf(first, second).map { IosSourceContent(it.copy(description = null)) }
                library.updateCategories(sparse, listOf(ids[2]), emptyList())
                assertEquals(setOf(ids[0], ids[2]), library.favouriteCategories(sparse[0]).toSet())
                assertEquals(setOf(ids[1], ids[2]), library.favouriteCategories(sparse[1]).toSet())
                assertEquals("保留完整详情", library.favourites().first { it.content.id == "101" }.content.summary)
                assertFailsWith<IllegalArgumentException> {
                    library.updateCategories(sparse, listOf(Int.MAX_VALUE), emptyList())
                }
                val new = first.copy(id = 103, url = "/new", publicUrl = "https://fixture.invalid/new")
                val collision = first.copy(source = SourceRef("MIHON_other", "zh", "MANGA"))
                assertFailsWith<IllegalArgumentException> {
                    library.updateCategories(listOf(IosSourceContent(new), IosSourceContent(collision)),
                        listOf(ids[2]), emptyList())
                }
                assertFalse(library.isFavourite(IosSourceContent(new)), "The first batch row must roll back")
                library.updateCategories(sparse, emptyList(), listOf(ids[0]))
                assertEquals(listOf(ids[2]), library.favouriteCategories(sparse[0]))
                assertEquals(setOf(ids[1], ids[2]), library.favouriteCategories(sparse[1]).toSet())
                library.updateCategories(listOf(sparse[0]), emptyList(), listOf(ids[2]))
                assertFalse(library.isFavourite(sparse[0]))
            } finally { library.close() }
            val reopened = IosLibrary(path)
            try {
                assertEquals("102", reopened.favourites().single().content.id)
                assertEquals(2, reopened.favourites().single().categoryIds.size)
            } finally { reopened.close() }
        } finally { files.removeItemAtPath(directory, null) }
    }
}
