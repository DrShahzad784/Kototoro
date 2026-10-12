package org.skepsun.kototoro.backups.external

import android.content.Context
import androidx.room.withTransaction
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.R
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.extensions.GlobalExtensionManager
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.mihon.MihonExtensionManager
import javax.inject.Inject

/**
 * Android wiring of the shared [ExternalLibraryImport]: the installed sources come from the extension managers and
 * the JSON source table, the transaction is Room's `withTransaction`, NSFW follows [ContentSource.isNsfw].
 */
@Reusable
class ExternalBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangaDatabase,
    private val mihonExtensionManager: MihonExtensionManager,
    private val aniyomiExtensionManager: AniyomiExtensionManager,
) {

    suspend fun import(payload: ExternalBackupPayload): ExternalBackupImportSummary = ExternalLibraryImport(
        database = database,
        catalog = ::candidates,
        transaction = object : ExternalImportTransaction {
            override suspend fun <R> run(block: suspend () -> R): R = database.withTransaction { block() }
        },
        defaultCategoryTitle = context.getString(R.string.favourites),
        isNsfwSource = { ContentSource(it).isNsfw() },
    ).import(payload)

    private suspend fun candidates(): List<ExternalSourceCandidate> = buildList {
        GlobalExtensionManager.contentSources.value.forEach { source ->
            add(ExternalSourceCandidate(source.name, source.name, ExternalSourceKind.NATIVE))
            add(ExternalSourceCandidate(source.name, source.getTitle(context), ExternalSourceKind.NATIVE))
        }
        GlobalExtensionManager.mangaSources.value.forEach { source ->
            val wrapped = org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserSource(source)
            add(ExternalSourceCandidate(wrapped.name, wrapped.name, ExternalSourceKind.NATIVE))
            add(ExternalSourceCandidate(wrapped.name, wrapped.title, ExternalSourceKind.NATIVE))
        }
        mihonExtensionManager.getMihonMangaSources().forEach { source ->
            add(ExternalSourceCandidate(source.name, source.name, ExternalSourceKind.MIHON))
            add(ExternalSourceCandidate(source.name, source.displayName, ExternalSourceKind.MIHON))
        }
        aniyomiExtensionManager.getAniyomiAnimeSources().forEach { source ->
            add(ExternalSourceCandidate(source.name, source.name, ExternalSourceKind.ANIYOMI))
            add(ExternalSourceCandidate(source.name, source.displayName, ExternalSourceKind.ANIYOMI))
        }
        database.getJsonSourceDao().observeEnabledSummaries().first().forEach { source ->
            add(ExternalSourceCandidate(source.id, source.id, ExternalSourceKind.JSON))
            add(ExternalSourceCandidate(source.id, source.name, ExternalSourceKind.JSON))
        }
    }
}
