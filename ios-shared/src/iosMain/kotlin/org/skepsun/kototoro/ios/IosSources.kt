package org.skepsun.kototoro.ios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceChapterNavigation
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourceProtocolClient
import org.skepsun.kototoro.core.source.SourceTransport
import platform.Foundation.NSUUID

/** Swift owns the synchronous JNI transport; shared protocol work runs off the UI thread. */
interface IosSourceTransport {
    @Throws(Exception::class)
    fun exchange(requestJson: String): String
    fun close()
}

data class IosSource(val name: String, val locale: String, val contentType: String)

/** Keep opaque extension fields inside Kotlin, including when Swift pushes a detail/reader screen. */
class IosSourceContent internal constructor(internal val value: SourceContent) {
    val id: String get() = value.id.toString()
    val title: String get() = value.title
    val url: String get() = value.url
    val authors: List<String> get() = value.authors.toList()
    val tags: List<String> get() = value.tags.map { it.title }
    val hasCover: Boolean get() = !value.coverUrl.isNullOrBlank() || !value.largeCoverUrl.isNullOrBlank()
    val chapters: List<IosSourceChapter> get() = value.chapters.orEmpty().map(::IosSourceChapter)

    fun adjacentChapter(chapter: IosSourceChapter, forward: Boolean): IosSourceChapter? =
        SourceChapterNavigation.adjacent(value.chapters.orEmpty(), chapter.value.id, forward)?.let(::IosSourceChapter)
}

class IosSourceChapter internal constructor(internal val value: SourceChapter) {
    val id: String get() = value.id.toString()
    val title: String get() = value.title?.takeIf(String::isNotBlank) ?: "第 ${value.number} 话"
}

class IosSourcePage internal constructor(internal val value: SourcePage) {
    val id: String get() = value.id.toString()
}

data class IosSourceImage(val relativePath: String, val sha256: String, val byteSize: Long)

class IosSources(private val transport: IosSourceTransport) {
    private val client = SourceProtocolClient(
        SourceTransport { request -> withContext(Dispatchers.IO) { transport.exchange(request) } },
        { NSUUID().UUIDString },
    )

    @Throws(Exception::class)
    suspend fun sources(): List<IosSource> = client.getSources().map { IosSource(it.name, it.locale, it.contentType) }

    @Throws(Exception::class)
    suspend fun list(sourceName: String, offset: Int): List<IosSourceContent> =
        client.getList(sourceName, offset, null, null).map(::IosSourceContent)

    @Throws(Exception::class)
    suspend fun details(content: IosSourceContent): IosSourceContent =
        IosSourceContent(client.getDetails(content.value, SourceDetailsFetchMode.ALLOW_CACHE))

    @Throws(Exception::class)
    suspend fun pages(chapter: IosSourceChapter): List<IosSourcePage> =
        client.getPages(chapter.value, null).map(::IosSourcePage)

    @Throws(Exception::class)
    suspend fun image(page: IosSourcePage): IosSourceImage {
        val artifact = client.fetchImage(page.value)
        check(artifact.source == page.value.source && artifact.pageId == page.value.id) { "Image identity mismatch" }
        return IosSourceImage(artifact.relativePath, artifact.sha256, artifact.byteSize)
    }

    @Throws(Exception::class)
    suspend fun cover(content: IosSourceContent): IosSourceImage {
        val artifact = client.fetchCover(content.value, true)
        check(artifact.source == content.value.source && artifact.contentId == content.value.id) {
            "Cover identity mismatch"
        }
        return IosSourceImage(artifact.relativePath, artifact.sha256, artifact.byteSize)
    }

    fun close() = transport.close()
}
