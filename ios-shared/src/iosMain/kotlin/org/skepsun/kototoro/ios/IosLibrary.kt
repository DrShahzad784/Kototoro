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

data class IosCategory(val id: Int, val title: String)

/** App-lifetime database owner. Swift supplies a persistent, app-private file path. */
class IosLibrary {
    private val database: MangaDatabase

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

    fun close() = database.close()
}
