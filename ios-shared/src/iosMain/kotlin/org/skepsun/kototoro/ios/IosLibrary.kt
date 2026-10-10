package org.skepsun.kototoro.ios

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.skepsun.kototoro.core.db.DATABASE_VERSION
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.core.library.SourceLibrary
import org.skepsun.kototoro.core.library.SourceTracker
import org.skepsun.kototoro.history.domain.recoverHistoryChapterId
import org.skepsun.kototoro.core.source.SourceRef
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

data class IosCategory(val id: Int, val title: String)

/** App-lifetime database owner. Swift supplies a persistent, app-private file path. */
@OptIn(ExperimentalAtomicApi::class)
class IosLibrary {
    internal val database: MangaDatabase
    private val sourceRefs = AtomicReference<Map<String, SourceRef>>(emptyMap())
    private val records by lazy { SourceLibrary(database) { sourceRefs.load()[it] } }

    fun updateSources(sources: List<IosSource>) {
        sourceRefs.store(sources.associate { it.name to SourceRef(it.name, it.locale, it.contentType) })
    }

    @Throws(Exception::class)
    constructor(databasePath: String) {
        require(databasePath.startsWith('/') && !databasePath.endsWith('/')) {
            "Database path must be an absolute file path"
        }
        database = Room.databaseBuilder<MangaDatabase>(name = databasePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    val schemaVersion: Int get() = DATABASE_VERSION

    @Throws(Exception::class)
    suspend fun categories(): List<IosCategory> = database.getFavouriteCategoriesDao().findAll().map {
        IosCategory(id = it.categoryId, title = it.title)
    }

    @Throws(Exception::class)
    suspend fun addCategory(title: String) {
        val normalized = title.trim()
        require(normalized.isNotEmpty()) { "Category title must not be blank" }
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val categories = database.getFavouriteCategoriesDao()
                categories.insert(
                    FavouriteCategoryEntity(
                        categoryId = 0,
                        createdAt = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                        sortKey = categories.getNextSortKey(),
                        title = normalized,
                        order = ListSortOrder.NEWEST.name,
                        track = true,
                        isVisibleInLibrary = true,
                        deletedAt = 0,
                    ),
                )
            }
        }
    }

    @Throws(Exception::class)
    suspend fun favourites(): List<IosLibraryEntry> = records.snapshot().entries.map(::IosLibraryEntry)

    @Throws(Exception::class)
    suspend fun history(): List<IosLibraryEntry> = records.snapshot(history = true).entries.map(::IosLibraryEntry)

    @Throws(Exception::class)
    suspend fun isFavourite(content: IosSourceContent): Boolean = records.isFavourite(content.value.id)

    @Throws(Exception::class)
    suspend fun setFavourite(content: IosSourceContent, favourite: Boolean) {
        if (favourite) records.addFavourite(content.value) else records.removeFavourite(content.value.id)
    }

    @Throws(Exception::class)
    suspend fun setCategories(content: IosSourceContent, categoryIds: List<Int>) =
        records.setCategories(content.value, categoryIds.map(Int::toLong).toSet())

    @Throws(Exception::class)
    suspend fun favouriteCategories(content: IosSourceContent): List<Int> =
        database.getFavouritesDao().findCategories(content.value.id).map(Long::toInt)

    @Throws(Exception::class)
    suspend fun removeHistory(content: IosSourceContent) = records.removeHistory(content.value.id)

    @Throws(Exception::class)
    suspend fun saveDetails(content: IosSourceContent) = records.save(content.value)

    @Throws(Exception::class)
    suspend fun position(content: IosSourceContent): IosReadingPosition? {
        val history = records.progress(content.value.id) ?: return null
        val ids = content.value.chapters.orEmpty().map { it.id }
        val recovered = recoverHistoryChapterId(false, history.chapterId, history.parentChapterId, history.percent, ids)
        val chapterId = recovered ?: history.chapterId
        if (chapterId !in ids) return null
        return IosReadingPosition(chapterId.toString(), if (recovered == null) history.page else 0,
            if (recovered == null) history.scroll else 0f, history.percent)
    }

    @Throws(Exception::class)
    suspend fun recordPosition(content: IosSourceContent, chapter: IosSourceChapter, index: Int,
        count: Int, scroll: Float) = recordPosition(content, chapter, index, count, index, scroll)

    @Throws(Exception::class)
    suspend fun recordPosition(content: IosSourceContent, chapter: IosSourceChapter, index: Int,
        count: Int, lastVisible: Int, scroll: Float) =
        records.recordPage(content.value, chapter.value, index, count, lastVisible, scroll)

    @Throws(Exception::class)
    suspend fun bookmarks(content: IosSourceContent): List<IosBookmark> = records.bookmarks(content.value.id).map(::IosBookmark)

    @Throws(Exception::class)
    suspend fun toggleBookmark(content: IosSourceContent, chapter: IosSourceChapter, page: IosSourcePage,
        index: Int, count: Int, scroll: Float) = records.toggleBookmark(content.value, chapter.value, page.value, index, count, scroll)

    @Throws(Exception::class)
    suspend fun toggleNovelBookmark(content: IosSourceContent, chapter: IosSourceChapter, index: Int,
        count: Int, preview: String) = records.toggleNovelBookmark(content.value, chapter.value, index, count, preview)

    @Throws(Exception::class)
    suspend fun removeBookmark(content: IosSourceContent, bookmark: IosBookmark) = records.removeBookmark(content.value, bookmark.value)

    @Throws(Exception::class)
    suspend fun updates(): List<IosUpdate> = database.getTracksDao().findAllIds().toList().mapNotNull { id ->
        val track = database.getTracksDao().find(id) ?: return@mapNotNull null
        if (track.newChapters <= 0) return@mapNotNull null
        records.find(id)?.let { IosUpdate(IosSourceContent(it), track.newChapters, track.lastCheckTime) }
    }.sortedByDescending { it.checkedAt }

    @Throws(Exception::class)
    suspend fun checkUpdates(sources: IosSources): IosUpdateReport {
        val tracker = SourceTracker(database, records,
            { sources.refreshDetails(IosSourceContent(it)).value }, { it.chapters?.firstOrNull()?.branch })
        val result = tracker.checkAll()
        return IosUpdateReport(result.checked, result.withUpdates, result.failed, result.newChapters)
    }

    @Throws(Exception::class)
    suspend fun markUpdateRead(content: IosSourceContent) = SourceTracker(database, records,
        { it }, { it.chapters?.firstOrNull()?.branch }).markRead(content.value.id)

    fun close() = database.close()
}
