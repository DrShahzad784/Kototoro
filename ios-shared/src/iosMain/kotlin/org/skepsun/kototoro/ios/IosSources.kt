package org.skepsun.kototoro.ios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.skepsun.kototoro.core.source.RoutedSourceRuntime
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceChapterNavigation
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceDescriptor
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourcePagingMode
import org.skepsun.kototoro.core.source.SourceProtocolClient
import org.skepsun.kototoro.core.source.SourceTag
import org.skepsun.kototoro.core.source.SourceTransport
import org.skepsun.kototoro.tvbox.MacCmsSourceRuntime
import platform.Foundation.NSUUID

/** Swift owns the synchronous JNI transport; shared protocol work runs off the UI thread. */
interface IosSourceTransport {
    @Throws(Exception::class)
    fun exchange(requestJson: String): String
    fun close()
}

/** [kind] is "video" for native Apple CMS sites and "extension" for embedded-JVM ecosystems. */
data class IosSource(val name: String, val title: String, val locale: String, val contentType: String, val kind: String) {
    /** CMS, MIHON, TSUNDOKU, ANIYOMI or JAR (kototoro / kotatsu / UMA parser plugins), from the wire identity. */
    val ecosystem: String get() = ecosystemOf(name)
    val isAdult: Boolean get() = contentType.startsWith("HENTAI_")
    /** MANGA, NOVEL or VIDEO without the adult marker. */
    val mediaKind: String get() = mediaKindOf(contentType)
}

internal fun ecosystemOf(name: String): String = when {
    MacCmsSourceRuntime.owns(name) -> "CMS"
    name.startsWith("MIHON_") -> "MIHON"
    name.startsWith("TSUNDOKU_") -> "TSUNDOKU"
    name.startsWith("ANIYOMI_") -> "ANIYOMI"
    else -> "JAR"
}

internal fun mediaKindOf(contentType: String): String = contentType.removePrefix("HENTAI_").let { kind ->
    if (kind == "VIDEO" || kind == "NOVEL") kind else "MANGA"
}

/** A novel chapter as HTML; [images] are the chapter's picture URLs (with the headers the source requires). */
class IosChapterText internal constructor(
    val html: String,
    val images: List<String>,
    private val imageHeaders: Map<String, Map<String, String>>,
) {
    /** Request headers the source requires for one of the chapter's pictures (Referer, cookies, ...). */
    fun headers(imageUrl: String): Map<String, String> = imageHeaders[imageUrl].orEmpty()
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
    val title: String get() = value.title?.takeIf(String::isNotBlank) ?: "第 ${value.number} 话"
    val branch: String? get() = value.branch
    val number: Float get() = value.number
    val volume: Int get() = value.volume
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
    val subtitleUrls: List<String> get() = value.externalSubtitleTracks.map { it.url }
}

data class IosSourceImage(val relativePath: String, val sha256: String, val byteSize: Long)

/**
 * Apple CMS sites run natively through the shared [MacCmsSourceRuntime]; every other source goes through the optional
 * embedded-JVM transport. Both sit behind one routed runtime, so screens do not care where a source executes.
 */
class IosSources(transport: IosSourceTransport?, private val videoSources: IosVideoSources?) {
    constructor(transport: IosSourceTransport) : this(transport, null)

    private val transport = transport
    private val extensions = transport?.let { owned ->
        SourceProtocolClient(
            SourceTransport { request -> withContext(Dispatchers.IO) { owned.exchange(request) } },
            { NSUUID().UUIDString },
        )
    }
    private val cms = videoSources?.let { store -> MacCmsSourceRuntime(store.http) { store.stored() } }
    private val runtime = RoutedSourceRuntime(
        listOfNotNull(
            cms?.let { RoutedSourceRuntime.Route(MacCmsSourceRuntime::owns, it) },
            extensions?.let { RoutedSourceRuntime.Route({ true }, it) },
        ),
    )
    private val descriptors = HashMap<String, SourceDescriptor>()
    private val descriptorsMutex = Mutex()

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
                val names = sourceTitles()
                client.getSources().map { IosSource(it.name, names[it.name] ?: it.name, it.locale, it.contentType, "extension") }
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (error: Exception) {
                extensionError = error.message ?: "扩展运行时调用失败"
                emptyList()
            }
        }.orEmpty()
        return IosSourceCatalog(video + external, extensionError)
    }

    /** Presentation metadata is optional, allowing older hosts to keep serving the source protocol. */
    private suspend fun sourceTitles(): Map<String, String> = try {
        val reply = Json.parseToJsonElement(manage("""{"managementVersion":1,"operation":"list"}""")).jsonObject
        reply["sourceTitles"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
    } catch (error: kotlin.coroutines.cancellation.CancellationException) {
        throw error
    } catch (_: Exception) { emptyMap() }

    /** Extension sources only (the embedded-JVM preview); kept for callers that predate native sources. */
    @Throws(Exception::class)
    suspend fun sources(): List<IosSource> = checkNotNull(extensions) { "此构建未包含扩展运行时" }
        .getSources().map { IosSource(it.name, it.name, it.locale, it.contentType, "extension") }

    /** Call after sites were imported or removed so cached detection is not reused. */
    @Throws(Exception::class)
    suspend fun sourcesChanged() {
        cms?.invalidate()
        descriptorsMutex.withLock { descriptors.clear() }
    }

    @Throws(Exception::class)
    suspend fun categories(sourceName: String): List<IosSourceTag> =
        runtime.getFilterOptions(sourceName).effectiveTagGroups.flatMap { it.tags }.distinctBy { it.key }.map(::IosSourceTag)

    @Throws(Exception::class)
    suspend fun list(sourceName: String, offset: Int): List<IosSourceContent> =
        runtime.getList(sourceName, offset, null, null).map(::IosSourceContent)

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
        runtime.getChapterContent(chapter.value, null)?.let { content ->
            IosChapterText(content.html, content.images.map { it.url }, content.images.associate { it.url to it.headers })
        }

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
        check(artifact.source == content.value.source && artifact.contentId == content.value.id) {
            "Cover identity mismatch"
        }
        return IosSourceImage(artifact.relativePath, artifact.sha256, artifact.byteSize)
    }

    private suspend fun descriptor(sourceName: String): SourceDescriptor =
        descriptorsMutex.withLock { descriptors[sourceName] } ?: runtime.describe(sourceName).also { described ->
            descriptorsMutex.withLock { descriptors[sourceName] = described }
        }

    fun close() {
        transport?.close()
    }
}
