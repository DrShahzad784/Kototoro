package org.skepsun.kototoro.tvbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** HTTP is a platform service (Ktor engine, URLSession, OkHttp); non-2xx responses must throw. */
fun interface TvBoxHttp {
    suspend fun get(url: String, headers: Map<String, String>): String
}

/**
 * One imported TVBox site, stored in the shared `json_sources` table exactly like Android stores it
 * (`JSON_TVBOX_<hash>` ID, `schemaVersion/importType/site/root/meta` config), so backups and sync stay compatible.
 */
data class TvBoxSource(val id: String, val name: String, val config: String, val iconUrl: String? = null) {
    val stored: TvBoxStoredConfig by lazy { TvBoxStoredConfig.parse(config) }
}

data class TvBoxHeaderRule(val host: String, val headers: Map<String, String>)

/** Mirrors Android's TVBoxStoredConfig fields that the CMS runtime consumes. */
class TvBoxStoredConfig(
    val siteKey: String,
    val siteName: String,
    val type: Int,
    val api: String,
    val ext: JsonElement?,
    val playUrl: String?,
    val staticHeaders: Map<String, String>,
    val siteRaw: JsonObject,
    val logo: String?,
    val wallpaper: String?,
    val headerRules: List<TvBoxHeaderRule>,
    val sourceLocator: String?,
    val sourceTitle: String?,
) {
    /** Spider sites need Android's JAR / QuickJS runtimes. */
    val requiresSpider: Boolean get() = type == 3 || type == 4 || api.startsWith("csp_", ignoreCase = true)

    companion object {
        fun parse(rawConfig: String): TvBoxStoredConfig {
            val root = TvBoxJson.parseToJsonElement(rawConfig) as? JsonObject ?: JsonObject(emptyMap())
            val site = root["site"] as? JsonObject ?: JsonObject(emptyMap())
            val rootContext = root["root"] as? JsonObject ?: JsonObject(emptyMap())
            val meta = root["meta"] as? JsonObject ?: JsonObject(emptyMap())
            return TvBoxStoredConfig(
                siteKey = site.string("key").orEmpty(),
                siteName = site.string("name").orEmpty(),
                type = site.string("type")?.toIntOrNull() ?: -1,
                api = site.string("api").orEmpty(),
                ext = site["ext"]?.takeUnless { it is JsonNull },
                playUrl = site.string("playUrl"),
                staticHeaders = site.headerMap("headers").ifEmpty { site.headerMap("header") },
                siteRaw = site,
                logo = rootContext.string("logo"),
                wallpaper = rootContext.string("wallpaper"),
                headerRules = (rootContext["headers"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val rule = item as? JsonObject ?: return@mapNotNull null
                    val host = rule.string("host") ?: return@mapNotNull null
                    rule.headerMap("header").takeIf { it.isNotEmpty() }?.let { TvBoxHeaderRule(host, it) }
                },
                sourceLocator = meta.string("sourceLocator"),
                sourceTitle = meta.string("sourceTitle"),
            )
        }
    }
}

/** Result of reading a TVBox document: CMS sites the shared runtime serves, plus what was skipped and why. */
data class TvBoxImportResult(val sources: List<TvBoxSource>, val skippedSpiderSites: Int, val errors: List<String>)

/** Android JsonSourceManager's TVBox import rules for the parts that do not need a spider runtime. */
object TvBoxImport {
    private const val MAX_REPOSITORY_DEPTH = 3
    private val macCmsApiPaths = listOf("/api.php/provide/vod", "/index.php/api/vod", "/api/vod", "/provide/vod")
    private val base64Marker = Regex("[A-Za-z0-9]{8}\\*\\*")
    private val rootContextKeys = listOf(
        "spider", "wallpaper", "logo", "lives", "parses", "flags", "ijk", "ads", "rules", "headers", "doh",
    )

    /** Imports a TVBox config / multi-repository URL or a MacCMS API URL, following child repositories. */
    suspend fun importFromUrl(http: TvBoxHttp, url: String, title: String? = null): TvBoxImportResult {
        val locator = url.trim()
        macCmsApiUrl(locator)?.let { api ->
            return collect(http, macCmsDocument(api, title), api, title ?: repositoryTitle(api), 0)
        }
        require(TvBoxUrls.isHttp(locator)) { "请输入 http(s) 地址" }
        val content = http.get(TvBoxUrls.asciiHost(locator), emptyMap())
        if (looksLikeMacCmsPayload(content)) {
            val api = locator.substringBefore('?').substringBefore('#')
            return collect(http, macCmsDocument(api, title), api, title ?: repositoryTitle(api), 0)
        }
        return collect(http, content, locator, title, 0)
    }

    /** Imports an inline TVBox document; child repositories are fetched through [http]. */
    suspend fun importDocument(http: TvBoxHttp, content: String, locator: String?, title: String?): TvBoxImportResult =
        collect(http, content, locator, title, 0)

    fun macCmsApiUrl(value: String): String? {
        val url = value.trim()
        if (!TvBoxUrls.isHttp(url)) return null
        val withoutQuery = url.substringBefore('#').substringBefore('?')
        val path = "/" + withoutQuery.substringAfter("://").substringAfter('/', "").trimStart('/').lowercase()
        return withoutQuery.takeIf { macCmsApiPaths.any(path::startsWith) || ".php/provide/vod" in path }
    }

    /** Android's `JSON_TVBOX_` + unsigned hex of the fingerprint's Java String hash. */
    fun sourceId(locator: String?, site: JsonObject): String {
        val fingerprint = listOf(
            locator?.trim().orEmpty(),
            site.rawString("key"),
            site.rawString("name"),
            site.rawString("api"),
            site["ext"]?.let(::jsonOptString).orEmpty().trim(),
        ).joinToString("|")
        return "JSON_TVBOX_" + fingerprint.hashCode().toUInt().toString(16).uppercase()
    }

    private suspend fun collect(
        http: TvBoxHttp,
        rawContent: String,
        locator: String?,
        title: String?,
        depth: Int,
        visited: MutableSet<String> = linkedSetOf(),
    ): TvBoxImportResult {
        val label = locator?.takeIf(String::isNotBlank) ?: "inline"
        val root = runCatching { parseRoot(preprocess(rawContent)) }.getOrElse {
            return TvBoxImportResult(emptyList(), 0, listOf("$label: ${it.message ?: "无效的 TVBox JSON"}"))
        }
        (root["sites"] as? JsonArray)?.let { sites ->
            val repositoryTitle = title ?: rootTitle(root, locator)
            val context = buildJsonObject { rootContextKeys.forEach { key -> root[key]?.let { put(key, it) } } }
            var skipped = 0
            val sources = sites.mapIndexedNotNull { index, element ->
                val site = element as? JsonObject ?: return@mapIndexedNotNull null
                val name = site.rawString("name").ifBlank { site.rawString("key") }
                if (name.isBlank()) return@mapIndexedNotNull null
                val config = siteConfig(site, context, locator, repositoryTitle, index)
                if (TvBoxStoredConfig.parse(config).requiresSpider) {
                    skipped++
                    return@mapIndexedNotNull null
                }
                TvBoxSource(sourceId(locator, site), name, config, site.string("icon") ?: site.string("logo"))
            }
            return TvBoxImportResult(sources, skipped, emptyList())
        }
        val children = root["urls"] as? JsonArray
            ?: return TvBoxImportResult(emptyList(), 0, listOf("$label: 不支持的 TVBox JSON，需要 sites 或 urls"))
        if (depth >= MAX_REPOSITORY_DEPTH) {
            return TvBoxImportResult(emptyList(), 0, listOf("$label: 多仓嵌套层级超过限制"))
        }
        val sources = ArrayList<TvBoxSource>()
        val errors = ArrayList<String>()
        var skipped = 0
        children.forEachIndexed { index, entry ->
            val (childUrl, childTitle) = repositoryEntry(entry) ?: run {
                errors += "$label: 第 ${index + 1} 个子仓缺少 url"
                return@forEachIndexed
            }
            if (!visited.add(childUrl)) return@forEachIndexed
            val content = runCatching { http.get(TvBoxUrls.asciiHost(childUrl), emptyMap()) }.getOrElse {
                errors += "$childUrl: 读取失败"
                return@forEachIndexed
            }
            val child = collect(http, content, childUrl, childTitle, depth + 1, visited)
            sources += child.sources
            errors += child.errors
            skipped += child.skippedSpiderSites
        }
        return TvBoxImportResult(sources.distinctBy { it.id }, skipped, errors)
    }

    private fun siteConfig(site: JsonObject, context: JsonObject, locator: String?, title: String?, index: Int) =
        buildJsonObject {
            put("schemaVersion", 1)
            put("importType", "tvbox_site")
            put("site", site)
            put("root", context)
            put("meta", buildJsonObject {
                put("sourceLocator", locator?.let(::JsonPrimitive) ?: JsonNull)
                put("sourceTitle", title?.let(::JsonPrimitive) ?: JsonNull)
                put("siteIndex", index)
                put("siteKey", site.rawString("key"))
                put("siteApi", site.rawString("api"))
            })
        }.toString()

    private fun macCmsDocument(api: String, title: String?): String {
        val name = title?.trim()?.takeIf(String::isNotEmpty) ?: repositoryTitle(api)
        val key = "MAC_CMS_" + api.hashCode().toUInt().toString(16).uppercase()
        return buildJsonObject {
            put("name", name)
            put("sites", JsonArray(listOf(buildJsonObject {
                put("key", key)
                put("name", name)
                put("type", 1)
                put("api", api)
                put("searchable", 1)
                put("quickSearch", 1)
                put("filterable", 1)
            })))
        }.toString()
    }

    private fun looksLikeMacCmsPayload(raw: String): Boolean {
        val text = raw.removePrefix("﻿").trim()
        if (text.startsWith('<')) return text.contains("<rss", true) && text.contains("<video", true)
        val root = runCatching { TvBoxJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
        return "list" in root && ("page" in root || "pagecount" in root || "total" in root || "class" in root)
    }

    private fun preprocess(raw: String): String {
        var content = raw
        base64Marker.find(content)?.let { marker ->
            // Image-wrapped configs: arbitrary bytes, the marker, then Base64 (like Android's MIME decoder, ignore
            // characters outside the alphabet and tolerate missing padding).
            content = runCatching {
                val payload = content.substring(marker.range.last + 1).filter { it.isLetterOrDigit() && it.code < 0x80 || it == '+' || it == '/' }
                kotlin.io.encoding.Base64.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT_OPTIONAL)
                    .decode(payload).decodeToString()
            }.getOrDefault(content)
        }
        content = content.trim()
        require(!content.startsWith("2423")) { "暂不支持加密的 TVBox 配置" }
        val cleaned = content.removePrefix("﻿").replace(Regex(""",(?=\s*[}\]])"""), "")
        return cleaned.lines()
            .filterNot { line -> line.trim().let { it.startsWith("//") || it.startsWith("#") } }
            .joinToString("\n")
            .trim()
    }

    private fun parseRoot(content: String): JsonObject = when (val root = TvBoxJson.parseToJsonElement(content)) {
        is JsonObject -> root
        else -> throw IllegalArgumentException("TVBox 根节点必须是 JSON 对象")
    }

    private fun repositoryEntry(raw: JsonElement): Pair<String, String?>? = when (raw) {
        is JsonObject -> raw.string("url")?.let { it to (raw.string("name") ?: raw.string("title")) }
        is JsonPrimitive -> raw.content.trim().takeIf { raw.isString && it.isNotEmpty() }?.let { it to null }
        else -> null
    }

    private fun rootTitle(root: JsonObject, locator: String?): String? =
        listOf("name", "title", "siteName").firstNotNullOfOrNull(root::string) ?: locator?.let(::repositoryTitle)

    private fun repositoryTitle(locator: String): String {
        val host = TvBoxUrls.host(locator).orEmpty()
        val tail = locator.substringBefore('?').substringBefore('#').substringAfter("://", "")
            .substringAfter('/', "").trimEnd('/').substringAfterLast('/')
        return when {
            host.isNotBlank() && tail.isNotBlank() -> "$host · $tail"
            host.isNotBlank() -> host
            else -> locator.substringAfterLast('/').ifBlank { locator }
        }
    }
}

internal val TvBoxJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    allowTrailingComma = true
    allowComments = true
}

/** org.json `optString` semantics: strings as-is, other values by their JSON text. */
internal fun jsonOptString(value: JsonElement): String? = when (value) {
    is JsonNull -> null
    is JsonPrimitive -> value.content
    else -> value.toString()
}

/** Trimmed, non-blank value like Android's `optStringOrNull`. */
internal fun JsonObject.string(key: String): String? = this[key]?.let(::jsonOptString)?.trim()?.ifBlank { null }

private fun JsonObject.rawString(key: String): String = this[key]?.let(::jsonOptString)?.trim().orEmpty()

internal fun JsonObject.headerMap(key: String): Map<String, String> {
    val value = this[key] as? JsonObject ?: return emptyMap()
    return value.entries.mapNotNull { (name, raw) ->
        jsonOptString(raw)?.trim()?.takeIf(String::isNotEmpty)?.let { name to it }
    }.toMap()
}
