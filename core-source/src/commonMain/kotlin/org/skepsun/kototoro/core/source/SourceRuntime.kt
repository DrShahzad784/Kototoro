package org.skepsun.kototoro.core.source

/** Parser operations and host-owned image artifacts; cookies and browser clearance remain platform responsibilities. */
interface SourceRuntime {
    suspend fun getSources(): List<SourceRef>
    suspend fun describe(sourceName: String): SourceDescriptor
    suspend fun getFilterOptions(sourceName: String): SourceFilterOptions
    suspend fun getDynamicFilters(sourceName: String): SourceDynamicFilters = throw SourceOperationUnsupportedException()
    suspend fun getPreferences(sourceName: String): SourcePreferenceScreen = throw SourceOperationUnsupportedException()
    suspend fun updatePreference(
        sourceName: String, revision: String, nodeId: String, value: SourcePreferenceValue,
    ): SourcePreferenceUpdate = throw SourceOperationUnsupportedException()
    suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?): List<SourceContent>
    suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode): SourceContent
    suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?): List<SourcePage>
    suspend fun getPageUrl(page: SourcePage): String
    /** The chapter as text (novels); null when the source has no text for it. */
    suspend fun getChapterContent(chapter: SourceChapter, nextChapterUrl: String?): SourceChapterContent? =
        throw SourceOperationUnsupportedException()
    suspend fun fetchImage(page: SourcePage): SourceImageArtifact = throw SourceOperationUnsupportedException()
    suspend fun fetchCover(content: SourceContent, large: Boolean = false): SourceCoverArtifact =
        throw SourceOperationUnsupportedException()
    suspend fun getRelated(content: SourceContent): List<SourceContent>
    /** The site to verify in a browser and the User-Agent to verify it with (clearance is bound to it). */
    suspend fun browserSession(sourceName: String): SourceBrowserSession =
        describe(sourceName).homepage?.let { SourceBrowserSession(it) } ?: throw SourceOperationUnsupportedException()
    /** Stores browser cookies for [url] in the cookie jar the source's requests use; returns how many were taken. */
    suspend fun importCookies(sourceName: String, url: String, cookies: List<String>): Int =
        throw SourceOperationUnsupportedException()
}

class SourceUnavailableException(val sourceName: String) : Exception("Source unavailable: $sourceName")

class SourceInvalidArgumentException : Exception("Invalid source argument")

class SourceOperationUnsupportedException : Exception("Unsupported source operation")

/**
 * A request met a browser challenge. Runtimes throw it (or hosts recognise their libraries' own exceptions, see
 * [SourceEndpoint]); only the [origin] crosses the wire.
 */
class SourceChallengeException(url: String?) : Exception("Browser verification required") {
    val origin: String? = url?.let(::originOf)

    companion object {
        /** `https://example.com` from any URL of that site; null for anything that is not http(s). */
        fun originOf(url: String): String? {
            val scheme = url.substringBefore("://", "").lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@')
            return if (host.isEmpty()) null else "$scheme://$host"
        }
    }
}
