package org.skepsun.kototoro.ios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.skepsun.kototoro.core.source.RoutedSourceRuntime
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceChapterContent
import org.skepsun.kototoro.core.source.SourceChapterNavigation
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceDescriptor
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourcePagingMode
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourceProtocolClient
import org.skepsun.kototoro.core.source.SourceTag
import org.skepsun.kototoro.core.source.SourceTransport
import org.skepsun.kototoro.tvbox.MacCmsSourceRuntime
import org.skepsun.kototoro.local.shared.LocalSourceRuntime
import okio.Path.Companion.toPath
import platform.Foundation.NSUUID

/** Swift owns the synchronous JNI transport; shared protocol work runs off the UI thread. */
interface IosSourceTransport {
    @Throws(Exception::class)
    fun exchange(requestJson: String): String
    fun close()
}

/** [kind] is "video" for native Apple CMS sites and "extension" for embedded-JVM ecosystems. */
data class IosSource(
    val name: String,
    val title: String,
    val locale: String,
    val contentType: String,
    val kind: String,
    /** The extension package or parser plugin providing this source; empty for native sites and older hosts. */
    val group: String = "",
    /** Distinct languages in [group]: above one, only the user's language is enabled by default. */
    val groupLanguages: Int = 1,
) {
    /** CMS, MIHON, TSUNDOKU, ANIYOMI or JAR (kototoro / kotatsu / UMA parser plugins), from the wire identity. */
    val ecosystem: String get() = ecosystemOf(name)
    val isAdult: Boolean get() = contentType.startsWith("HENTAI_")
    /** MANGA, NOVEL or VIDEO without the adult marker. */
    val mediaKind: String get() = mediaKindOf(contentType)
}

/** 12f → "12", 12.5f → "12.5", as Android formats chapter numbers. */
internal fun chapterNumber(number: Float): String =
    if (number == number.toLong().toFloat()) number.toLong().toString() else number.toString()

internal fun ecosystemOf(name: String): String = when {
    MacCmsSourceRuntime.owns(name) -> "CMS"
    LocalSourceRuntime.owns(name) -> "LOCAL"
    name.startsWith("MIHON_") -> "MIHON"
    name.startsWith("TSUNDOKU_") -> "TSUNDOKU"
    name.startsWith("ANIYOMI_") -> "ANIYOMI"
    else -> "JAR"
}

internal fun mediaKindOf(contentType: String): String = contentType.removePrefix("HENTAI_").let { kind ->
    if (kind == "VIDEO" || kind == "NOVEL") kind else "MANGA"
}

/** A novel chapter as HTML; [images] are the chapter's picture URLs (with the headers the source requires). */
class IosChapterText internal constructor(internal val value: SourceChapterContent) {
    val html: String get() = value.html
    val images: List<String> get() = value.images.map { it.url }

    /** Request headers the source requires for one of the chapter's pictures (Referer, cookies, ...). */
    fun headers(imageUrl: String): Map<String, String> = value.images.firstOrNull { it.url == imageUrl }?.headers.orEmpty()
}

/** Native sources are always listed; an extension runtime failure is reported without hiding them. */
data class IosSourceCatalog(val sources: List<IosSource>, val extensionError: String?)

class IosSourceTag internal constructor(internal val value: SourceTag) {
    val title: String get() = value.title
    val key: String get() = value.key
}

/** Keep opaque extension fields inside Kotlin, including when Swift pushes a detail/reader screen. */
class IosSourceContent internal constructor(internal val value: SourceContent) {
    val id: String get() = value.id.toString()
    val sourceName: String get() = value.source.name
    val contentType: String get() = value.source.contentType
    val mediaKind: String get() = mediaKindOf(value.source.contentType)
    val title: String get() = value.title
    val url: String get() = value.url
    val publicUrl: String get() = value.publicUrl
    val authors: List<String> get() = value.authors.toList()
    val altTitles: List<String> get() = value.altTitles.filter { it.isNotBlank() && it != value.title }
    /** 0…1 as on Android; negative when the source has no rating. */
    val rating: Float get() = value.rating
    /** ONGOING / FINISHED / ABANDONED / PAUSED / UPCOMING / RESTRICTED, when known. */
    val state: String? get() = value.state?.takeIf(String::isNotBlank)
    val contentRating: String? get() = value.contentRating?.takeIf(String::isNotBlank)
    val tags: List<String> get() = value.tags.map { it.title }
    val summary: String? get() = value.description?.takeIf(String::isNotBlank)
    val coverUrl: String? get() = (value.largeCoverUrl ?: value.coverUrl)?.takeIf(String::isNotBlank)
    val hasCover: Boolean get() = coverUrl != null
    val chapters: List<IosSourceChapter> get() = value.chapters.orEmpty().map(::IosSourceChapter)

    fun adjacentChapter(chapter: IosSourceChapter, forward: Boolean): IosSourceChapter? =
        SourceChapterNavigation.adjacent(value.chapters.orEmpty(), chapter.value.id, forward)?.let(::IosSourceChapter)
}

class IosSourceChapter internal constructor(internal val value: SourceChapter) {
    val id: String get() = value.id.toString()
    val title: String get() = value.title?.takeIf(String::isNotBlank) ?: "第 ${chapterNumber(value.number)} 话"
    val branch: String? get() = value.branch
    val number: Float get() = value.number
    val volume: Int get() = value.volume
    val scanlator: String? get() = value.scanlator?.takeIf(String::isNotBlank)
    /** Epoch milliseconds; 0 when unknown. */
    val uploadDate: Long get() = value.uploadDate
    /** The playback line (Apple CMS `vod_play_from`) this episode belongs to, when the source names it. */
    val line: String? get() = value.sourceData?.let { data ->
        runCatching { (Json.parseToJsonElement(data) as? JsonObject)?.get("line") as? JsonPrimitive }
            .getOrNull()?.takeIf { it.isString }?.content
    }
}

class IosSourcePage internal constructor(internal val value: SourcePage) {
    val id: String get() = value.id.toString()
    val url: String get() = value.url
    val headers: Map<String, String> get() = value.headers.orEmpty()
    val label: String? get() = value.playbackLabel
    val quality: Int get() = value.playbackQuality ?: 0
    val subtitles: List<IosExternalTrack> get() = value.externalSubtitleTracks.map(::IosExternalTrack)
}

data class IosSourceImage(val relativePath: String, val sha256: String, val byteSize: Long)

/**
 * Apple CMS sites run natively through the shared [MacCmsSourceRuntime]; every other source goes through the optional
 * embedded-JVM transport. Both sit behind one routed runtime, so screens do not care where a source executes.
 */
class IosSources(
    transport: IosSourceTransport?,
    private val videoSources: IosVideoSources?,
    /** Where imported local works are stored and where reader pictures go (`<sha256>.img`); null disables them. */
    localDirectory: String?,
    imageDirectory: String?,
) {
    constructor(transport: IosSourceTransport?, videoSources: IosVideoSources?) : this(transport, videoSources, null, null)
    constructor(transport: IosSourceTransport) : this(transport, null)

    private val transport = transport
    private val extensions = transport?.let { owned ->
        SourceProtocolClient(
            SourceTransport { request -> withContext(Dispatchers.IO) { owned.exchange(request) } },
            { NSUUID().UUIDString },
        )
    }
    private val cms = videoSources?.let { store -> MacCmsSourceRuntime(store.http) { store.stored() } }
    private val local = if (localDirectory == null || imageDirectory == null) null else
        LocalSourceRuntime(localDirectory.toPath(), imageDirectory.toPath(), decodeLegacyText = ::decodeLegacyText)
    private val runtime = RoutedSourceRuntime(
        listOfNotNull(
            local?.let { RoutedSourceRuntime.Route(LocalSourceRuntime::owns, it) },
            cms?.let { RoutedSourceRuntime.Route(MacCmsSourceRuntime::owns, it) },
            extensions?.let { RoutedSourceRuntime.Route({ true }, it) },
        ),
    )
    private val descriptors = HashMap<String, SourceDescriptor>()
    private val descriptorsMutex = Mutex()
    private val panels = mutableMapOf<String, IosFilterPanel>()

    val hasExtensionRuntime: Boolean get() = extensions != null

    /** Versioned host management is separate from the source invocation protocol. */
    @Throws(Exception::class)
    suspend fun manage(requestJson: String): String = withContext(Dispatchers.IO) {
        checkNotNull(transport) { "此构建未包含扩展运行时" }.exchange(requestJson)
    }

    @Throws(Exception::class)
    suspend fun catalog(): IosSourceCatalog {
        val video = videoSources?.list().orEmpty().map { IosSource(it.id, it.name, "", MacCmsSourceRuntime.CONTENT_TYPE, "video") }
        var extensionError: String? = null
        val external = extensions?.let { client ->
            try {
                val info = managementInfo()
                client.getSources().map {
                    val group = info.groups[it.name]
                    IosSource(it.name, info.titles[it.name] ?: it.name, it.locale, it.contentType, "extension",
                        group?.first.orEmpty(), group?.second ?: 1)
                }
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (error: Exception) {
                extensionError = error.message ?: "扩展运行时调用失败"
                emptyList()
            }
        }.orEmpty()
        val imported = if (local == null) emptyList() else listOf(
            IosSource(LocalSourceRuntime.MANGA.name, "本地漫画", "", LocalSourceRuntime.MANGA.contentType, "local"),
            IosSource(LocalSourceRuntime.NOVEL.name, "本地小说", "", LocalSourceRuntime.NOVEL.contentType, "local"),
        )
        return IosSourceCatalog(imported + video + external, extensionError)
    }

    /** True when local import is available in this build. */
    val supportsLocalImport: Boolean get() = local != null

    /** Imports a CBZ/ZIP, EPUB or TXT file (copied into the app); [fileName] decides the format and default title. */
    @Throws(Exception::class)
    suspend fun importLocal(path: String, fileName: String): IosSourceContent =
        IosSourceContent(checkNotNull(local) { "本地导入不可用" }.import(path.toPath(), fileName))

    fun isLocal(content: IosSourceContent): Boolean = LocalSourceRuntime.owns(content.value.source.name)

    /** Deletes the imported file; favourites and history of the work stay like those of any removed source. */
    @Throws(Exception::class)
    suspend fun removeLocal(content: IosSourceContent) {
        checkNotNull(local) { "本地导入不可用" }.remove(content.value.id)
    }

    private class ManagementInfo(val titles: Map<String, String>, val groups: Map<String, Pair<String, Int>>)

    /** Presentation metadata is optional, allowing older hosts to keep serving the source protocol. */
    private suspend fun managementInfo(): ManagementInfo = try {
        val reply = Json.parseToJsonElement(manage("""{"managementVersion":1,"operation":"list"}""")).jsonObject
        val titles = reply["sourceTitles"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val groups = HashMap<String, Pair<String, Int>>()
        reply["installed"]?.jsonArray.orEmpty().forEach { element ->
            val item = element.jsonObject
            val packageName = item["packageName"]?.jsonPrimitive?.content ?: return@forEach
            val languages = item["languages"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.distinct().size
            item["sourceIds"]?.jsonArray.orEmpty().forEach { id ->
                groups.getOrPut(id.jsonPrimitive.content) { packageName to maxOf(languages, 1) }
            }
        }
        ManagementInfo(titles, groups)
    } catch (error: kotlin.coroutines.cancellation.CancellationException) {
        throw error
    } catch (_: Exception) { ManagementInfo(emptyMap(), emptyMap()) }

    /** Extension sources only (the embedded-JVM preview); kept for callers that predate native sources. */
    @Throws(Exception::class)
    suspend fun sources(): List<IosSource> = checkNotNull(extensions) { "此构建未包含扩展运行时" }
        .getSources().map { IosSource(it.name, it.name, it.locale, it.contentType, "extension") }

    /** Call after sites were imported or removed so cached detection is not reused. */
    @Throws(Exception::class)
    suspend fun sourcesChanged() {
        cms?.invalidate()
        descriptorsMutex.withLock { descriptors.clear(); panels.clear() }
    }

    @Throws(Exception::class)
    suspend fun categories(sourceName: String): List<IosSourceTag> =
        runtime.getFilterOptions(sourceName).effectiveTagGroups.flatMap { it.tags }.distinctBy { it.key }.map(::IosSourceTag)

    @Throws(Exception::class)
    suspend fun list(sourceName: String, offset: Int): List<IosSourceContent> =
        runtime.getList(sourceName, offset, null, null).map(::IosSourceContent)

    @Throws(Exception::class)
    suspend fun browserSession(sourceName: String): IosBrowserSession =
        runtime.browserSession(sourceName).let { IosBrowserSession(it.url, it.userAgent) }

    /** Cookies from the in-app browser (`Set-Cookie` lines) for the source's own requests; returns how many were kept. */
    @Throws(Exception::class)
    suspend fun importCookies(sourceName: String, url: String, cookies: List<String>): Int =
        runtime.importCookies(sourceName, url, cookies)

    /** Drops cached CMS site state after its settings change, so the next request uses the new address. */
    @Throws(Exception::class)
    suspend fun reloadVideoSites() { cms?.invalidate() }

    /** The source's settings; sources without any (or whose runtime has none) throw an unsupported error. */
    @Throws(Exception::class)
    suspend fun preferences(sourceName: String): IosPreferenceScreen = IosPreferenceScreen(runtime.getPreferences(sourceName))

    @Throws(Exception::class)
    suspend fun updateText(screen: IosPreferenceScreen, node: IosPreferenceNode, text: String): IosPreferenceResult =
        update(screen, node, SourcePreferenceValue.Text(text))

    @Throws(Exception::class)
    suspend fun updateToggle(screen: IosPreferenceScreen, node: IosPreferenceNode, on: Boolean): IosPreferenceResult =
        update(screen, node, SourcePreferenceValue.Toggle(on))

    @Throws(Exception::class)
    suspend fun updateChoice(screen: IosPreferenceScreen, node: IosPreferenceNode, choice: String): IosPreferenceResult =
        update(screen, node, node.choiceValue(choice))

    @Throws(Exception::class)
    suspend fun updateChoices(screen: IosPreferenceScreen, node: IosPreferenceNode, choices: Set<String>): IosPreferenceResult =
        update(screen, node, SourcePreferenceValue.TextSet(choices))

    private suspend fun update(screen: IosPreferenceScreen, node: IosPreferenceNode, value: SourcePreferenceValue): IosPreferenceResult {
        val result = runtime.updatePreference(screen.sourceName, screen.revision, node.id, value)
        return IosPreferenceResult(result.status.label(), IosPreferenceScreen(result.screen))
    }

    @Throws(Exception::class)
    suspend fun filterPanel(sourceName: String): IosFilterPanel {
        val descriptor = descriptor(sourceName)
        val options = runtime.getFilterOptions(sourceName)
        // Extensions with their own FilterList keep text/select/sort controls; the tag projection stays as fallback.
        val dynamic = if (!descriptor.isDynamicFilteringSupported) null else try {
            runtime.getDynamicFilters(sourceName).takeIf { it.source.name == sourceName }
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        } catch (_: Exception) { null }
        // Apple CMS listings keep the site's newest-first order; relevance only applies to search.
        val panel = if (MacCmsSourceRuntime.owns(sourceName)) IosFilterPanel(descriptor, options,
            MacCmsSourceRuntime.ORDER_NEWEST, setOf(MacCmsSourceRuntime.ORDER_RELEVANCE))
            else IosFilterPanel(descriptor, options, dynamic = dynamic)
        descriptorsMutex.withLock { panels[sourceName] = panel }
        return panel
    }

    /** Listing with the browse screen's filter panel selection; search text wins when the source cannot combine. */
    @Throws(Exception::class)
    suspend fun filteredPage(
        sourceName: String,
        page: Int,
        loadedCount: Int,
        query: String?,
        selection: IosFilterSelection?,
    ): List<IosSourceContent> {
        val descriptor = descriptor(sourceName)
        val offset = if (descriptor.pagingMode == SourcePagingMode.PAGE_INDEX) page else loadedCount
        val search = query?.trim()?.takeIf(String::isNotEmpty)
        val panel = descriptorsMutex.withLock { panels[sourceName] }
        val combined = search == null || descriptor.filterCapabilities.isSearchWithFiltersSupported
        val filter = if (combined) selection?.toFilter(panel, search) ?: search?.let { SourceFilter(query = it) }
            else SourceFilter(query = search)
        val order = when {
            MacCmsSourceRuntime.owns(sourceName) -> if (search != null) MacCmsSourceRuntime.ORDER_RELEVANCE
                else selection?.order ?: MacCmsSourceRuntime.ORDER_NEWEST
            else -> selection?.order?.takeIf { it in descriptor.sortOrders }
        }
        return runtime.getList(sourceName, offset, order, filter).map(::IosSourceContent)
    }

    /**
     * One page of a listing or search. [loadedCount] is what the screen already shows; the source's paging mode
     * decides whether the request offset is the page index or the item count.
     */
    @Throws(Exception::class)
    suspend fun page(
        sourceName: String,
        page: Int,
        loadedCount: Int,
        query: String?,
        tag: IosSourceTag?,
    ): List<IosSourceContent> {
        val descriptor = descriptor(sourceName)
        val offset = if (descriptor.pagingMode == SourcePagingMode.PAGE_INDEX) page else loadedCount
        val search = query?.trim()?.takeIf(String::isNotEmpty)
        val filter = if (search == null && tag == null) null else SourceFilter(query = search, tags = setOfNotNull(tag?.value))
        // Apple CMS sites keep their own order (latest first) unless searching; other sources use their default.
        val order = when {
            !MacCmsSourceRuntime.owns(sourceName) -> null
            search != null -> MacCmsSourceRuntime.ORDER_RELEVANCE
            else -> MacCmsSourceRuntime.ORDER_NEWEST
        }
        return runtime.getList(sourceName, offset, order, filter).map(::IosSourceContent)
    }

    @Throws(Exception::class)
    suspend fun details(content: IosSourceContent): IosSourceContent =
        IosSourceContent(runtime.getDetails(content.value, SourceDetailsFetchMode.ALLOW_CACHE))

    @Throws(Exception::class)
    suspend fun refreshDetails(content: IosSourceContent): IosSourceContent =
        IosSourceContent(runtime.getDetails(content.value, SourceDetailsFetchMode.FORCE_REFRESH))

    @Throws(Exception::class)
    suspend fun pages(chapter: IosSourceChapter): List<IosSourcePage> =
        runtime.getPages(chapter.value, null).map(::IosSourcePage)

    @Throws(Exception::class)
    suspend fun chapterText(chapter: IosSourceChapter): IosChapterText? =
        runtime.getChapterContent(chapter.value, null)?.let(::IosChapterText)

    /** Extension covers are downloaded by the source's own client; native sources expose plain cover URLs. */
    fun fetchesCovers(content: IosSourceContent): Boolean = !MacCmsSourceRuntime.owns(content.value.source.name)

    @Throws(Exception::class)
    suspend fun image(page: IosSourcePage): IosSourceImage {
        val artifact = runtime.fetchImage(page.value)
        check(artifact.source == page.value.source && artifact.pageId == page.value.id) { "Image identity mismatch" }
        return IosSourceImage(artifact.relativePath, artifact.sha256, artifact.byteSize)
    }

    @Throws(Exception::class)
    suspend fun cover(content: IosSourceContent): IosSourceImage {
        val artifact = runtime.fetchCover(content.value, true)
        // Same source by name: a work restored from the library carries the stored locale/content type, which can
        // differ from the runtime's descriptor (the continue-reading cover failed for exactly those works).
        check(artifact.source.name == content.value.source.name && artifact.contentId == content.value.id) {
            "Cover identity mismatch"
        }
        return IosSourceImage(artifact.relativePath, artifact.sha256, artifact.byteSize)
    }

    /** The source site's base address for its icon; null when the runtime has none or describe fails. */
    @Throws(Exception::class)
    suspend fun homepage(sourceName: String): String? = try { descriptor(sourceName).homepage } catch (
        error: kotlin.coroutines.cancellation.CancellationException) { throw error } catch (_: Exception) { null }

    private suspend fun descriptor(sourceName: String): SourceDescriptor =
        descriptorsMutex.withLock { descriptors[sourceName] } ?: runtime.describe(sourceName).also { described ->
            descriptorsMutex.withLock { descriptors[sourceName] = described }
        }

    fun close() {
        transport?.close()
    }
}
