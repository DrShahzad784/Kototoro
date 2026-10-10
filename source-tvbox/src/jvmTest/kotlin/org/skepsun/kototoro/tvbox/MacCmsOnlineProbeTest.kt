package org.skepsun.kototoro.tvbox

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceFilter
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Opt-in probe against real sites: `-PtvboxOnlineList=<file with one URL per line>`. Writes a per-site report and
 * never fails on site availability; it fails only if no site completes the full chain.
 */
class MacCmsOnlineProbeTest {
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .proxy(java.net.ProxySelector.getDefault())
        // The JDK's h2c upgrade header makes some TVBox hosts answer with an HTML page; apps never send it.
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    private val http = TvBoxHttp { url, headers ->
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).GET().apply {
            headers.forEach { (name, value) -> if (!name.equals("Host", true)) runCatching { header(name, value) } }
            if (headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", "Mozilla/5.0")
        }.build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
        response.body()
    }

    @Test
    fun realSites() = runBlocking {
        val list = System.getProperty("tvboxOnlineList")?.takeIf(String::isNotBlank)
        assumeTrue(list != null, "Set -PtvboxOnlineList to probe real sites")
        val report = StringBuilder()
        var complete = 0
        for (url in File(list!!).readLines().map(String::trim).filter(String::isNotEmpty)) {
            val line = runCatching { withTimeout(120_000) { probe(url) } }.getOrElse { "FAIL ${it::class.simpleName}: ${it.message}" }
            if (line.startsWith("OK")) complete++
            report.appendLine("$url\n    $line")
            println("$url -> $line")
        }
        File(System.getProperty("tvboxOnlineReport") ?: "build/tvbox-online-report.txt").apply {
            parentFile?.mkdirs()
            writeText(report.toString())
        }
        check(complete > 0) { "No site completed the full chain" }
    }

    private suspend fun probe(url: String): String {
        val imported = TvBoxImport.importFromUrl(http, url)
        val sources = imported.sources
        if (sources.isEmpty()) return "FAIL import: ${imported.errors.take(2)} skippedSpider=${imported.skippedSpiderSites}"
        val summary = "sites=${sources.size} skippedSpider=${imported.skippedSpiderSites} errors=${imported.errors.size}"
        var lastFailure = ""
        // Multi-repositories: probe the first few CMS sites until one completes.
        for (source in sources.take(5)) {
            val runtime = MacCmsSourceRuntime(http) { listOf(source) }
            val result = runCatching {
                val categories = runtime.getFilterOptions(source.id).availableTags
                val first = runtime.getList(source.id, 0, MacCmsSourceRuntime.ORDER_NEWEST, null)
                check(first.isNotEmpty()) { "empty list" }
                val second = runtime.getList(source.id, 1, MacCmsSourceRuntime.ORDER_NEWEST, null)
                val category = categories.firstOrNull()?.let {
                    runtime.getList(source.id, 0, MacCmsSourceRuntime.ORDER_NEWEST, SourceFilter(tags = setOf(it))).size
                }
                val query = first.first().title.take(2)
                val found = runtime.getList(source.id, 0, MacCmsSourceRuntime.ORDER_RELEVANCE, SourceFilter(query = query))
                val details = runtime.getDetails(first.first(), SourceDetailsFetchMode.ALLOW_CACHE)
                val chapters = details.chapters.orEmpty()
                check(chapters.isNotEmpty()) { "no episodes for ${details.title}" }
                val page = runtime.getPages(chapters.first(), null).single()
                val media = http.get(page.url, page.headers.orEmpty())
                val playable = media.trimStart().startsWith("#EXTM3U") || page.url.contains(".mp4")
                val lines = chapters.mapNotNull { it.sourceData }.distinct().size
                "OK [${source.name}] categories=${categories.size} page1=${first.size} page2=${second.size} " +
                    "category=$category search('$query')=${found.size} title='${details.title}' " +
                    "cover=${details.coverUrl != null} episodes=${chapters.size} lines=$lines m3u8=$playable"
            }
            result.getOrNull()?.let { return "$it $summary" }
            lastFailure = "[${source.name}] ${result.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }}"
        }
        return "FAIL $lastFailure $summary"
    }
}
