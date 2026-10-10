package org.skepsun.kototoro.tvbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceDescriptor
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceFilterCapabilities
import org.skepsun.kototoro.core.source.SourceFilterOptions
import org.skepsun.kototoro.core.source.SourceInvalidArgumentException
import org.skepsun.kototoro.core.source.SourceOperationUnsupportedException
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourcePagingMode
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceRuntime
import org.skepsun.kototoro.core.source.SourceTag
import org.skepsun.kototoro.core.source.SourceTagGroup
import org.skepsun.kototoro.core.source.SourceUnavailableException

/**
 * Apple CMS (MacCMS v8/v10, JSON or XML) TVBox sites as a [SourceRuntime]. Source names are the stored
 * `JSON_TVBOX_<hash>` IDs; list offsets are page indexes, like Android's PAGE_INDEX paging for CMS sites.
 * Spider sites are not served here and report [SourceUnavailableException].
 */
class MacCmsSourceRuntime(
    private val http: TvBoxHttp,
    private val sources: suspend () -> List<TvBoxSource>,
) : SourceRuntime {
    private class Provider(val url: String, val headers: Map<String, String>, val categories: List<CmsCategory>)

    private class SiteState(val source: TvBoxSource) {
        val parser = MacCmsParser(source.stored)
        val ref = SourceRef(source.id, "", CONTENT_TYPE)
        val mutex = Mutex()
        var provider: Provider? = null
        val items = LinkedHashMap<String, CmsItem>()
    }

    private val statesMutex = Mutex()
    private val states = HashMap<String, SiteState>()

    /** Forget cached detection and items, e.g. after the site list was edited. */
    suspend fun invalidate() = statesMutex.withLock { states.clear() }

    override suspend fun getSources(): List<SourceRef> =
        sources().filterNot { it.stored.requiresSpider }.map { SourceRef(it.id, "", CONTENT_TYPE) }

    override suspend fun describe(sourceName: String): SourceDescriptor = SourceDescriptor(
        source = state(sourceName).ref,
        sortOrders = setOf(ORDER_ALPHABETICAL, ORDER_RELEVANCE, ORDER_NEWEST),
        defaultSortOrder = ORDER_ALPHABETICAL,
        filterCapabilities = SourceFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isYearSupported = false,
            isYearRangeSupported = false,
            isOriginalLocaleSupported = false,
            isAuthorSearchSupported = false,
        ),
        pagingMode = SourcePagingMode.PAGE_INDEX,
    )

    override suspend fun getFilterOptions(sourceName: String): SourceFilterOptions {
        val state = state(sourceName)
        val tags = provider(state).categories.mapTo(linkedSetOf()) { tag(state, it.name, it.id) }
        return SourceFilterOptions(
            availableTags = tags,
            tagGroups = if (tags.isEmpty()) emptyList() else listOf(SourceTagGroup("分类", tags)),
            availableStates = emptySet(),
            availableContentRating = emptySet(),
            availableContentTypes = emptySet(),
            availableDemographics = emptySet(),
            availableLocales = emptySet(),
        )
    }

    override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?): List<SourceContent> {
        if (offset < 0) throw SourceInvalidArgumentException()
        val state = state(sourceName)
        val provider = provider(state)
        val query = filter?.query?.trim().orEmpty()
        val includeKeys = filter?.tags.orEmpty().mapTo(hashSetOf()) { it.key }
        val excludeKeys = filter?.tagsExclude.orEmpty().mapTo(hashSetOf()) { it.key }
        val categoryId = includeKeys.firstNotNullOfOrNull(::categoryIdOf)
        val items = fetchItems(state, provider, MacCmsParser.listUrls(provider.url, offset + 1, query, categoryId))
            .filter { query.isEmpty() || it.title.contains(query, true) || it.description.orEmpty().contains(query, true) }
            .filter { includeKeys.isEmpty() || tagKey(it) in includeKeys }
            .filter { excludeKeys.isEmpty() || tagKey(it) !in excludeKeys }
        state.mutex.withLock { items.forEach { state.items[it.token] = it } }
        return sort(items, order ?: ORDER_ALPHABETICAL, query).map { content(state, it) }
    }

    override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode): SourceContent {
        val state = state(content.source.name)
        val locator = itemLocator(content) ?: return content
        val cached = state.mutex.withLock { state.items[locator.token] }
        val item = cached?.takeIf { it.streams.isNotEmpty() && fetchMode == SourceDetailsFetchMode.ALLOW_CACHE }
            ?: locator.externalId?.let { detailItem(state, it) }
            ?: return content
        return merge(state, content, item)
    }

    override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?): List<SourcePage> {
        val state = state(chapter.source.name)
        val locator = TvBoxLocator.parse(chapter.url)?.takeIf { it.host == "chapter" && it.segments.size >= 2 }
            ?: throw SourceInvalidArgumentException()
        val itemToken = locator.segments[0]
        val streamToken = locator.segments[1]
        val stream = state.mutex.withLock { state.items[itemToken] }?.streams?.firstOrNull { it.token == streamToken }
            ?: locator.query["id"]?.let { id -> detailItem(state, id)?.streams?.firstOrNull { it.token == streamToken } }
            ?: locator.query["play"]?.let { direct ->
                CmsStream(streamToken, chapter.title.orEmpty(), direct, state.parser.headersFor(direct, emptyMap()))
            }
            ?: throw SourceOperationUnsupportedException()
        return listOf(
            SourcePage(
                id = identity(chapter.source.name, "page", chapter.url),
                url = stream.url,
                preview = null,
                source = state.ref,
                headers = stream.headers,
                playbackLabel = stream.title,
            ),
        )
    }

    override suspend fun getPageUrl(page: SourcePage): String = page.url

    override suspend fun getRelated(content: SourceContent): List<SourceContent> {
        val query = content.title.trim().takeIf(String::isNotEmpty) ?: return emptyList()
        return runCatching {
            getList(content.source.name, 0, ORDER_RELEVANCE, SourceFilter(query = query))
        }.getOrElse { if (it is CancellationException) throw it else emptyList() }.filter { it.id != content.id }
    }

    private suspend fun state(sourceName: String): SiteState = statesMutex.withLock {
        states[sourceName] ?: run {
            val source = sources().firstOrNull { it.id == sourceName }?.takeUnless { it.stored.requiresSpider }
                ?: throw SourceUnavailableException(sourceName)
            SiteState(source).also { states[sourceName] = it }
        }
    }

    /** Android's detection: the first http candidate whose response parses as categories or items. */
    private suspend fun provider(state: SiteState): Provider = state.mutex.withLock {
        state.provider ?: run {
            for (candidate in candidates(state)) {
                val content = try {
                    http.get(candidate.first, candidate.second)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    continue
                }
                val parsed = state.parser.parse(candidate.first, candidate.second, content)
                if (parsed.categories.isNotEmpty() || parsed.items.isNotEmpty()) {
                    return@run Provider(candidate.first, candidate.second, parsed.categories)
                }
            }
            throw SourceOperationUnsupportedException()
        }.also { state.provider = it }
    }

    private fun candidates(state: SiteState): List<Pair<String, Map<String, String>>> {
        val config = state.source.stored
        val result = LinkedHashMap<String, Map<String, String>>()
        fun add(raw: String?, extra: Map<String, String> = emptyMap()) {
            val url = raw?.let(TvBoxUrls::primaryLocator)?.takeIf(TvBoxUrls::isHttp) ?: return
            if (url !in result) result[url] = state.parser.headersFor(url, extra)
        }
        fun addFrom(value: JsonElement?, inherited: Map<String, String>) {
            when (value) {
                null, is JsonNull -> Unit
                is JsonPrimitive -> add(value.content, inherited)
                is JsonArray -> value.forEach { addFrom(it, inherited) }
                is JsonObject -> {
                    val headers = inherited + value.headerMap("headers").ifEmpty { value.headerMap("header") }
                    listOf("url", "api", "playUrl", "link", "file", "m3u", "m3u8", "json").forEach { add(value.string(it), headers) }
                    value.forEach { (key, child) ->
                        if (!key.equals("header", true) && !key.equals("headers", true)) addFrom(child, headers)
                    }
                }
            }
        }
        add(config.api)
        listOf("url", "link", "file", "m3u", "m3u8").forEach { add(config.siteRaw.string(it)) }
        add(config.playUrl)
        addFrom(config.ext, emptyMap())
        return result.toList()
    }

    private suspend fun fetchItems(state: SiteState, provider: Provider, urls: List<String>): List<CmsItem> {
        var lastError: Exception? = null
        var hadResponse = false
        for (url in urls) {
            val content = try {
                http.get(url, provider.headers)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                continue
            }
            hadResponse = true
            val items = state.parser.parse(url, provider.headers, content).items
            if (items.isNotEmpty()) return items
        }
        if (!hadResponse && lastError != null) throw lastError
        return emptyList()
    }

    private suspend fun detailItem(state: SiteState, externalId: String): CmsItem? {
        val provider = provider(state)
        val items = fetchItems(state, provider, MacCmsParser.detailUrls(provider.url, externalId))
        val item = items.firstOrNull { it.externalId == externalId } ?: items.firstOrNull() ?: return null
        state.mutex.withLock { state.items[item.token] = item }
        return item
    }

    private fun content(state: SiteState, item: CmsItem) = SourceContent(
        id = identity(state.ref.name, "item", item.token),
        title = item.title,
        altTitles = emptySet(),
        url = item.url,
        publicUrl = item.url,
        rating = -1f,
        contentRating = null,
        coverUrl = item.coverUrl,
        tags = tags(state, item),
        state = null,
        authors = emptySet(),
        source = state.ref,
        largeCoverUrl = item.coverUrl,
        description = item.description,
    )

    private fun merge(state: SiteState, content: SourceContent, item: CmsItem): SourceContent = content.copy(
        title = item.title.ifBlank { content.title },
        coverUrl = item.coverUrl ?: content.coverUrl,
        largeCoverUrl = item.coverUrl ?: content.largeCoverUrl,
        publicUrl = item.url,
        description = item.description ?: content.description,
        tags = tags(state, item).ifEmpty { content.tags },
        chapters = item.streams.mapIndexed { index, stream ->
            SourceChapter(
                id = identity(state.ref.name, "chapter", "${item.token}|${stream.token}|$index"),
                title = stream.title,
                number = (index + 1).toFloat(),
                volume = 0,
                url = MacCmsParser.chapterUrl(item.token, stream.token, item.externalId, stream.url),
                scanlator = item.group,
                uploadDate = 0,
                branch = item.group,
                source = state.ref,
                sourceData = stream.line?.let { line -> buildJsonObject { put("line", line) }.toString() },
            )
        },
    )

    private fun tags(state: SiteState, item: CmsItem): Set<SourceTag> = when {
        item.group == null && item.typeId == null -> emptySet()
        else -> setOf(tag(state, item.group ?: item.typeId.orEmpty(), item.typeId))
    }

    private fun tag(state: SiteState, title: String, typeId: String?) =
        SourceTag(title, "cms_type:${typeId ?: title.lowercase()}", state.ref)

    private fun tagKey(item: CmsItem): String? =
        if (item.group == null && item.typeId == null) null else "cms_type:${item.typeId ?: item.group.orEmpty().lowercase()}"

    private fun categoryIdOf(key: String): String? =
        key.takeIf { it.startsWith("cms_type:") }?.substringAfter("cms_type:")?.trim()?.ifEmpty { null }

    private fun itemLocator(content: SourceContent): ItemLocator? =
        sequenceOf(content.url, content.publicUrl).mapNotNull(TvBoxLocator::parse)
            .firstOrNull { it.host == "item" && it.segments.isNotEmpty() }
            ?.let { ItemLocator(it.segments[0], it.query["id"]) }

    private data class ItemLocator(val token: String, val externalId: String?)

    private fun sort(items: List<CmsItem>, order: String, query: String): List<CmsItem> = when (order) {
        ORDER_ALPHABETICAL -> items.sortedBy { it.title.lowercase() }
        ORDER_RELEVANCE -> if (query.isEmpty()) items else items.sortedBy { item ->
            when {
                item.title.equals(query, true) -> 0
                item.title.startsWith(query, true) -> 1
                item.title.contains(query, true) -> 2
                item.description.orEmpty().contains(query, true) -> 3
                else -> 4
            }
        }
        else -> items
    }

    companion object {
        /** CMS ids are local to a site; database identities must include the strict source key. */
        fun identity(source: String, kind: String, token: String): Long =
            "$source|$kind|$token".fold(1125899906842597L) { hash, char -> 31 * hash + char.code } and Long.MAX_VALUE
        const val CONTENT_TYPE = "VIDEO"
        const val ORDER_ALPHABETICAL = "ALPHABETICAL"
        const val ORDER_RELEVANCE = "RELEVANCE"
        /** Keeps the site's own order (usually latest updates first). */
        const val ORDER_NEWEST = "NEWEST"

        fun owns(sourceName: String): Boolean = sourceName.startsWith("JSON_TVBOX_")
    }
}
