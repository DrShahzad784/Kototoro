package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.tvbox.TvBoxHttp
import org.skepsun.kototoro.tvbox.TvBoxImport
import org.skepsun.kototoro.tvbox.TvBoxSource

data class IosVideoSource(val id: String, val name: String, val api: String, val iconUrl: String?)

data class IosImportSummary(val imported: Int, val skippedSpiderSites: Int, val errors: List<String>)

/** TVBox / Apple CMS sites in the shared `json_sources` table, written in Android's storage format. */
class IosVideoSources internal constructor(private val library: IosLibrary, internal val http: TvBoxHttp) {
    constructor(library: IosLibrary) : this(library, IosHttp)

    private val dao get() = library.database.getJsonSourceDao()

    @Throws(Exception::class)
    suspend fun list(): List<IosVideoSource> = stored().map { IosVideoSource(it.id, it.name, it.stored.api, it.iconUrl) }

    /** Accepts a TVBox config / multi-repository URL or a MacCMS API URL. Existing sites keep enabled state. */
    @Throws(Exception::class)
    suspend fun importFrom(url: String, title: String?): IosImportSummary {
        val result = TvBoxImport.importFromUrl(http, url, title?.trim()?.ifEmpty { null })
        if (result.sources.isEmpty()) {
            // A readable repository full of spider sites is the common case for public TVBox lists; say so rather
            // than surfacing whichever child repository happened to fail.
            val reason = when {
                result.skippedSpiderSites > 0 ->
                    "该配置的 ${result.skippedSpiderSites} 个站点都需要爬虫运行时（type 3/4），iOS 暂不支持"
                result.errors.isNotEmpty() -> result.errors.first()
                else -> "没有找到可用的苹果 CMS 站点"
            }
            throw IllegalArgumentException(reason)
        }
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        dao.insertAll(result.sources.map { source ->
            val existing = dao.getById(source.id)
            JsonSourceEntity(
                id = source.id,
                name = source.name,
                type = JsonSourceType.TVBOX,
                config = source.config,
                enabled = existing?.enabled ?: true,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                lastUsedAt = existing?.lastUsedAt ?: 0,
                isPinned = existing?.isPinned ?: false,
                iconUrl = source.iconUrl ?: existing?.iconUrl,
            )
        })
        return IosImportSummary(result.sources.size, result.skippedSpiderSites, result.errors)
    }

    @Throws(Exception::class)
    suspend fun delete(id: String) = dao.deleteById(id)

    /** Enabled CMS sites; spider sites imported on Android stay in the table but are not served here. */
    internal suspend fun stored(): List<TvBoxSource> = dao.findAll()
        .filter { it.type == JsonSourceType.TVBOX && it.enabled }
        .map { TvBoxSource(it.id, it.name, it.config, it.iconUrl) }
        .filterNot { runCatching { it.stored.requiresSpider }.getOrDefault(true) }
}
