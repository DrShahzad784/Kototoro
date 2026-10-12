package org.skepsun.kototoro.backups.external

import org.skepsun.kototoro.parsers.model.ContentType
import kotlin.math.max

/** One SQLite database from a Venera backup ZIP, read through the platform's SQLite. */
interface VeneraDatabase {
    /**
     * Rows of [sql] as column name to value (Long, Double, String, ByteArray or null), with [args] bound as text.
     */
    fun query(sql: String, args: List<String> = emptyList()): List<Map<String, Any?>>
}

/**
 * Venera backups: a ZIP with `local_favorite.db` (one table per folder) and `history.db`. The rules are Android's
 * original decoder; only the SQLite access is per platform.
 */
object VeneraBackupDecoding {
    const val FAVORITES_FILE = "local_favorite.db"
    const val HISTORY_FILE = "history.db"
    val DATABASE_FILES = setOf(FAVORITES_FILE, HISTORY_FILE)
    private val FAVORITE_METADATA_TABLES = setOf("folder_order", "folder_sync", "sqlite_sequence")

    /** The databases a ZIP entry name stands for, by file name (Venera nests them in a folder). */
    fun databaseFileOf(entryName: String): String? = entryName.substringAfterLast('/').takeIf { it in DATABASE_FILES }

    fun decode(favorites: VeneraDatabase?, history: VeneraDatabase?): ExternalBackupPayload {
        if (favorites == null && history == null) throw UnsupportedExternalBackupException("Unsupported Venera backup format")
        return ExternalBackupPayload(records = merge(favorites?.let(::readFavorites).orEmpty(),
            history?.let(::readHistory).orEmpty()))
    }

    private fun readFavorites(db: VeneraDatabase): List<ExternalBackupContentRecord> {
        val folderSourceKeys = db.folderSourceKeys()
        val tables = db.tableNames().filterNot { it in FAVORITE_METADATA_TABLES || it.startsWith("sqlite_") }
        return tables.flatMap { table ->
            db.query("SELECT * FROM `$table`").mapNotNull { it.toFavoriteRecord(table, folderSourceKeys[table]) }
        }
    }

    private fun readHistory(db: VeneraDatabase): List<ExternalBackupContentRecord> {
        if (!db.hasTable("history")) return emptyList()
        return db.query("SELECT * FROM history").mapNotNull { it.toHistoryRecord() }
    }

    private fun merge(
        favorites: List<ExternalBackupContentRecord>,
        histories: List<ExternalBackupContentRecord>,
    ): List<ExternalBackupContentRecord> {
        val merged = LinkedHashMap<String, ExternalBackupContentRecord>()
        (favorites + histories).forEach { record ->
            val key = "${record.url}|${record.sourceCandidates.joinToString("|")}"
            val previous = merged[key]
            merged[key] = if (previous == null) {
                record
            } else {
                previous.copy(
                    isFavorite = previous.isFavorite || record.isFavorite,
                    favoriteTimestamp = previous.favoriteTimestamp ?: record.favoriteTimestamp,
                    historyChapterUrl = previous.historyChapterUrl ?: record.historyChapterUrl,
                    historyTimestamp = listOfNotNull(previous.historyTimestamp, record.historyTimestamp).maxOrNull(),
                    chaptersCount = max(previous.chaptersCount, record.chaptersCount),
                    readEntriesCount = max(previous.readEntriesCount, record.readEntriesCount),
                    progressPercent = previous.progressPercent ?: record.progressPercent,
                    sourceCandidates = (previous.sourceCandidates + record.sourceCandidates).distinct(),
                )
            }
        }
        return merged.values.toList()
    }

    private fun VeneraDatabase.tableNames(): List<String> =
        query("SELECT name FROM sqlite_master WHERE type='table'").mapNotNull { it["name"] as? String }

    private fun VeneraDatabase.hasTable(table: String): Boolean =
        query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", listOf(table)).isNotEmpty()

    private fun VeneraDatabase.folderSourceKeys(): Map<String, String> {
        if (!hasTable("folder_sync")) return emptyMap()
        return buildMap {
            for (row in query("SELECT folder_name, source_key FROM folder_sync")) {
                val folderName = row.stringValue("folder_name")
                val sourceKey = row.stringValue("source_key")
                if (folderName.isNotBlank() && sourceKey.isNotBlank()) put(folderName, sourceKey)
            }
        }
    }

    private fun Map<String, Any?>.toFavoriteRecord(tableName: String, folderSourceKey: String?): ExternalBackupContentRecord? {
        val title = stringValue("name", "title").ifBlank { return null }
        val url = stringValue("id", "url", "comic_id").ifBlank { title }
        val sourceCandidates = sourceCandidates(folderSourceKey, tableName)
        return ExternalBackupContentRecord(
            app = ExternalBackupApp.VENERA,
            sourceName = sourceCandidates.firstOrNull().orEmpty(),
            contentType = ContentType.MANGA,
            url = url,
            title = title,
            authors = stringValue("author", "subtitle").ifBlank { null },
            description = stringValue("description", "intro").ifBlank { null },
            tags = stringValue("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            coverUrl = stringValue("cover_path", "cover", "cover_url", "image").ifBlank { null },
            publicUrl = url,
            state = null,
            isFavorite = true,
            favoriteTimestamp = longValue("time", "created_at", "updated_at").takeIf { it > 0L }?.let(::normalizeTimestamp),
            favoriteCategoryOrders = emptyList(),
            chaptersCount = intValue("max_page", "episode_count", "chapter_count").coerceAtLeast(0),
            readEntriesCount = 0,
            progressPercent = null,
            historyChapterUrl = null,
            historyTimestamp = null,
            sourceCandidates = sourceCandidates,
        )
    }

    private fun Map<String, Any?>.toHistoryRecord(): ExternalBackupContentRecord? {
        val title = stringValue("title", "name").ifBlank { return null }
        val url = stringValue("id", "url", "comic_id").ifBlank { title }
        val sourceCandidates = sourceCandidates()
        val chapterIndex = intValue("ep", "episode", "chapter", "page")
        val maxPage = intValue("max_page", "maxPage", "chapters")
        return ExternalBackupContentRecord(
            app = ExternalBackupApp.VENERA,
            sourceName = sourceCandidates.firstOrNull().orEmpty(),
            contentType = ContentType.MANGA,
            url = url,
            title = title,
            authors = stringValue("subtitle", "author").ifBlank { null },
            description = null,
            tags = emptyList(),
            coverUrl = stringValue("cover", "cover_url", "image").ifBlank { null },
            publicUrl = url,
            state = null,
            isFavorite = false,
            favoriteTimestamp = null,
            favoriteCategoryOrders = emptyList(),
            chaptersCount = maxPage.coerceAtLeast(0),
            readEntriesCount = chapterIndex.coerceAtLeast(0),
            progressPercent = calculateProgressPercent(maxPage, chapterIndex),
            historyChapterUrl = "venera:$url:${stringValue("chapter_group", "group")}:$chapterIndex",
            historyTimestamp = longValue("time", "updated_at", "last_read").takeIf { it > 0L }?.let(::normalizeTimestamp),
            sourceCandidates = sourceCandidates,
        )
    }

    private fun Map<String, Any?>.sourceCandidates(vararg extraCandidates: String?): List<String> {
        val type = intValue("type").takeIf { containsKey("type") }
        return listOfNotNull(
            stringValue("source_key", "sourceKey", "source").takeIf { it.isNotBlank() },
            stringValue("source_name", "sourceName").takeIf { it.isNotBlank() },
            type?.let(::legacySourceKey),
            type?.toString(),
            *extraCandidates,
        ).filter { it.isNotBlank() }.distinct()
    }

    private fun Map<String, Any?>.stringValue(vararg names: String): String {
        for (name in names) {
            if (!containsKey(name)) continue
            return when (val value = get(name) ?: continue) {
                is ByteArray -> value.decodeToString()
                is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
                else -> value.toString()
            }
        }
        return ""
    }

    private fun Map<String, Any?>.longValue(vararg names: String): Long {
        for (name in names) {
            if (!containsKey(name)) continue
            return when (val value = get(name) ?: continue) {
                is Long -> value
                is Int -> value.toLong()
                is Double -> value.toLong()
                is String -> value.toLongOrNull() ?: 0L
                else -> 0L
            }
        }
        return 0L
    }

    private fun Map<String, Any?>.intValue(vararg names: String): Int = longValue(*names).toInt()

    private fun legacySourceKey(type: Int): String? = when (type) {
        0 -> "picacg"
        1 -> "ehentai"
        2 -> "jm"
        3 -> "hitomi"
        4 -> "wnacg"
        5, 6 -> "nhentai"
        233488852 -> "baozi"
        29663848 -> "hot_manga"
        42816288 -> "manwaba"
        11995058 -> "lanraragi"
        150465061 -> "zaimanhua"
        236897507 -> "hcomic"
        258019538 -> "hitomi"
        264196719 -> "nhentai"
        331263271 -> "shonen_jump_plus"
        385625716 -> "ehentai"
        553570794 -> "picacg"
        550146035 -> "goda"
        557997769 -> "copy_manga"
        577341847 -> "mh1234"
        577718694 -> "manga_dex"
        631413104 -> "manhuaren"
        635587041 -> "komga"
        637999886 -> "Komiic"
        716010982 -> "ikmmh"
        740690276 -> "jcomic"
        771282371 -> "mxs"
        778108598 -> "mh18"
        798816513 -> "ykmh"
        807338462 -> "ccc"
        823512256 -> "wnacg"
        875043938 -> "kavita"
        893043064 -> "comic_walker"
        964788560 -> "comick"
        977805693 -> "happy"
        981441865 -> "ManHuaGui"
        769844263 -> "jm"
        else -> null
    }
}
