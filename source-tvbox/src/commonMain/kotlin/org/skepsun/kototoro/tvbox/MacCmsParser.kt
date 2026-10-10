package org.skepsun.kototoro.tvbox

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class CmsCategory(val id: String, val name: String)

internal data class CmsStream(
    val token: String,
    val title: String,
    val url: String,
    val headers: Map<String, String>,
    /** The `vod_play_from` line this episode belongs to, when the site names its lines. */
    val line: String? = null,
)

internal data class CmsItem(
    val id: Long,
    val token: String,
    val title: String,
    val url: String,
    val description: String?,
    val coverUrl: String?,
    val group: String?,
    val typeId: String?,
    val streams: List<CmsStream>,
    val externalId: String?,
)

internal data class CmsParsed(val categories: List<CmsCategory>, val items: List<CmsItem>)

/**
 * MacCMS response parsing and request URLs, ported from Android's TVBoxRepository so both platforms derive the same
 * tokens, `tvbox://` URLs and 63-bit IDs (Java String hash) for the same site data.
 */
internal class MacCmsParser(private val config: TvBoxStoredConfig) {
    fun parse(baseUrl: String, headers: Map<String, String>, content: String): CmsParsed {
        val normalized = content.removePrefix("﻿").trim()
        if (normalized.startsWith('<')) return parseXml(baseUrl, headers, normalized)
        val root = runCatching { TvBoxJson.parseToJsonElement(normalized) }.getOrNull() ?: return CmsParsed(emptyList(), emptyList())
        val categories = linkedMapOf<String, CmsCategory>()
        val items = linkedMapOf<String, CmsItem>()
        preferredPayloads(root).forEach { extract(it, baseUrl, headers, categories, items) }
        if (categories.isEmpty() && items.isEmpty()) extract(root, baseUrl, headers, categories, items)
        return CmsParsed(categories.values.toList(), items.values.toList())
    }

    private fun preferredPayloads(root: JsonElement): List<JsonElement> {
        if (root !is JsonObject) return listOf(root)
        val data = root["data"]
        val dataObject = data as? JsonObject
        return listOfNotNull(
            root["class"], root["classes"], root["types"], root["list"], root["data_list"],
            dataObject?.get("class"), dataObject?.get("classes"), dataObject?.get("types"),
            dataObject?.get("list"), dataObject?.get("data"), data,
        ).filterNot { it is JsonNull }.ifEmpty { listOf(root) }
    }

    private fun extract(
        node: JsonElement,
        baseUrl: String,
        headers: Map<String, String>,
        categories: MutableMap<String, CmsCategory>,
        items: MutableMap<String, CmsItem>,
    ) {
        when (node) {
            is JsonArray -> node.forEach { extract(it, baseUrl, headers, categories, items) }
            is JsonObject -> {
                val categoryId = node.firstNonBlank("type_id", "class_id")
                val categoryName = node.firstNonBlank("type_name", "class_name", "group")
                if (categoryId != null && categoryName != null) {
                    categories.getOrPut(categoryId) { CmsCategory(categoryId, categoryName) }
                }
                vodItem(node, baseUrl, headers)?.let { items.getOrPut(it.token) { it } }
                node.values.forEach { extract(it, baseUrl, headers, categories, items) }
            }
            else -> Unit
        }
    }

    private fun parseXml(baseUrl: String, headers: Map<String, String>, content: String): CmsParsed {
        val document = MiniXml.parse(content) ?: return CmsParsed(emptyList(), emptyList())
        val categories = linkedMapOf<String, CmsCategory>()
        document.descendants("ty").forEach { element ->
            val id = (element.attribute("id") ?: element.childText("id")
                ?: element.attribute("type_id") ?: element.childText("type_id")).orEmpty().trim()
            val name = element.textContent.trim()
            if (id.isNotEmpty() && name.isNotEmpty()) categories.getOrPut(id) { CmsCategory(id, name) }
        }
        val items = linkedMapOf<String, CmsItem>()
        document.descendants("video").forEach { element ->
            val playFrom = ArrayList<String>()
            val playUrls = ArrayList<String>()
            element.descendants("dd").forEach { dd ->
                val payload = dd.textContent.trim()
                if (payload.isNotEmpty()) {
                    playUrls += payload
                    playFrom += dd.attribute("flag")?.trim()?.ifEmpty { null } ?: "线路${playUrls.size}"
                }
            }
            val fields = buildMap {
                fun putIfPresent(key: String, value: String?) {
                    value?.trim()?.takeIf(String::isNotEmpty)?.let { put(key, JsonPrimitive(it)) }
                }
                putIfPresent("vod_id", element.childText("id"))
                putIfPresent("vod_name", element.childText("name"))
                putIfPresent("type_id", element.childText("tid"))
                putIfPresent("type_name", element.childText("type"))
                putIfPresent("vod_pic", element.childText("pic"))
                putIfPresent("vod_remarks", element.childText("note"))
                putIfPresent("vod_content", element.childText("des"))
                putIfPresent("vod_play_from", playFrom.takeIf { it.isNotEmpty() }?.joinToString("$$$") ?: element.childText("dt"))
                putIfPresent("vod_play_url", playUrls.takeIf { it.isNotEmpty() }?.joinToString("$$$") ?: element.childText("dl"))
            }
            vodItem(JsonObject(fields), baseUrl, headers)?.let { items.getOrPut(it.token) { it } }
        }
        return CmsParsed(categories.values.toList(), items.values.toList())
    }

    private fun vodItem(node: JsonObject, baseUrl: String, headers: Map<String, String>): CmsItem? {
        if (!looksLikeVod(node)) return null
        val externalId = node.firstNonBlank("vod_id", "id", "ids")
        val title = node.firstNonBlank("vod_name", "name", "title")
        val cover = TvBoxUrls.resolve(baseUrl, node.firstNonBlank(*COVER_KEYS))
            ?: listOfNotNull(config.logo, config.wallpaper).map(TvBoxUrls::primaryLocator).firstOrNull(TvBoxUrls::isHttp)
        val streams = buildList {
            node.firstNonBlank(*DIRECT_URL_KEYS)
                ?.let { TvBoxUrls.resolve(baseUrl, it) }
                ?.takeIf(TvBoxUrls::isPlayableStream)
                ?.takeIf { title != null }
                ?.let { direct -> add(CmsStream(positiveHash("$title|$direct").toString(), title!!, direct, headersFor(direct, headers))) }
            node.firstNonBlank("vod_play_url")?.let { payload ->
                addAll(parsePlayUrl(title ?: externalId ?: config.siteName, payload, node.firstNonBlank("vod_play_from"), baseUrl, headers))
            }
        }
        if (title == null || (externalId == null && streams.isEmpty())) return null
        val token = "cms:${externalId ?: positiveHash(title)}"
        return CmsItem(
            id = positiveHash("item|$token"),
            token = token,
            title = title,
            url = itemUrl(token, externalId),
            description = node.firstNonBlank("vod_remarks", "remark", "vod_content", "description", "desc"),
            coverUrl = cover,
            group = node.firstNonBlank("type_name", "group"),
            typeId = node.firstNonBlank("type_id"),
            streams = streams,
            externalId = externalId,
        )
    }

    private fun looksLikeVod(node: JsonObject): Boolean {
        if ("vod_id" in node || "vod_name" in node || "vod_play_url" in node || "vod_pic" in node) return true
        val title = node.firstNonBlank("name", "title")
        val direct = node.firstNonBlank(*DIRECT_URL_KEYS)
        return title != null && direct != null && TvBoxUrls.isPlayableStream(direct)
    }

    /** `vod_play_from` names the `$$$`-separated lines; each line is `#`-separated `name$url` episodes. */
    fun parsePlayUrl(
        title: String,
        payload: String,
        playFrom: String?,
        baseUrl: String,
        baseHeaders: Map<String, String>,
    ): List<CmsStream> {
        val groups = payload.split("$$$")
        val groupNames = playFrom?.split("$$$")?.map(String::trim).orEmpty()
        val streams = ArrayList<CmsStream>()
        groups.forEachIndexed { groupIndex, groupPayload ->
            val groupName = groupNames.getOrNull(groupIndex)?.takeIf(String::isNotBlank)
            groupPayload.split('#').map(String::trim).filter(String::isNotBlank).forEachIndexed { streamIndex, entry ->
                val rawName = entry.substringBefore('$', "").trim().ifBlank { "线路${groupIndex + 1}-${streamIndex + 1}" }
                val name = if (groupName != null && groups.size > 1) "$groupName - $rawName" else rawName
                val rawUrl = entry.substringAfter('$', "").trim()
                if (rawUrl.isEmpty()) return@forEachIndexed
                val resolved = TvBoxUrls.resolve(baseUrl, rawUrl) ?: rawUrl
                if (!TvBoxUrls.isPlayableStream(resolved)) return@forEachIndexed
                streams += CmsStream(
                    positiveHash("$title|$name|$resolved").toString(), name, resolved, headersFor(resolved, baseHeaders),
                    line = groupName ?: "线路${groupIndex + 1}".takeIf { groups.size > 1 },
                )
            }
        }
        return streams
    }

    fun headersFor(url: String?, extra: Map<String, String>): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers += config.staticHeaders
        headers += extra
        val host = TvBoxUrls.host(url)
        if (host != null) config.headerRules.filter { it.host.equals(host, ignoreCase = true) }.forEach { headers += it.headers }
        if (headers.keys.none { it.equals("Referer", ignoreCase = true) }) TvBoxUrls.origin(url)?.let { headers["Referer"] = it }
        return headers
    }

    companion object {
        private val COVER_KEYS = arrayOf(
            "vod_pic", "vod_pic_thumb", "vod_pic_slide", "pic", "pic_url", "img", "image", "thumb", "thumbnail",
            "thumbnail_url", "cover", "cover_url", "poster",
        )
        private val DIRECT_URL_KEYS = arrayOf("playUrl", "play_url", "url", "link", "src", "video", "videoUrl", "m3u8")

        fun listUrls(baseUrl: String, page: Int, query: String, categoryId: String?): List<String> {
            val urls = linkedSetOf<String>()
            fun add(ac: String?, queryPairs: List<Pair<String, String>>, categoryPairs: List<Pair<String, String>>) {
                val params = buildList {
                    if (ac != null) add("ac" to ac)
                    add("pg" to page.toString())
                    addAll(categoryPairs)
                    addAll(queryPairs)
                }
                TvBoxUrls.withQuery(baseUrl, params)?.let(urls::add)
            }
            val categoryVariants = if (categoryId.isNullOrBlank()) {
                listOf(emptyList())
            } else {
                listOf("t", "type", "class", "type_id", "class_id").map { listOf(it to categoryId) }
            }
            if (query.isNotBlank()) {
                for (queryPairs in listOf("wd", "key", "keyword").map { listOf(it to query) }) {
                    for (categoryPairs in categoryVariants) {
                        add(null, queryPairs, categoryPairs)
                        add("videolist", queryPairs, categoryPairs)
                        add("detail", queryPairs, categoryPairs)
                        add("list", queryPairs, categoryPairs)
                    }
                }
            } else {
                for (categoryPairs in categoryVariants) {
                    add("videolist", emptyList(), categoryPairs)
                    add("detail", emptyList(), categoryPairs)
                    add("list", emptyList(), categoryPairs)
                    add(null, emptyList(), categoryPairs)
                }
            }
            return urls.toList()
        }

        fun detailUrls(baseUrl: String, externalId: String): List<String> {
            val urls = linkedSetOf<String>()
            for (idParam in listOf("ids", "id")) {
                for (ac in listOf("detail", "videolist", "list", null)) {
                    val params = buildList {
                        if (ac != null) add("ac" to ac)
                        add(idParam to externalId)
                    }
                    TvBoxUrls.withQuery(baseUrl, params)?.let(urls::add)
                }
            }
            return urls.toList()
        }

        fun itemUrl(token: String, externalId: String?): String =
            if (externalId.isNullOrBlank()) "tvbox://item/$token" else "tvbox://item/$token?id=${TvBoxUrls.encode(externalId)}"

        fun chapterUrl(itemToken: String, streamToken: String, externalId: String?, streamUrl: String?): String {
            val query = listOfNotNull(
                externalId?.trim()?.takeIf(String::isNotEmpty)?.let { "id=${TvBoxUrls.encode(it)}" },
                streamUrl?.trim()?.takeIf(String::isNotEmpty)?.let { "play=${TvBoxUrls.encode(it)}" },
            ).joinToString("&")
            return "tvbox://chapter/$itemToken/$streamToken" + if (query.isEmpty()) "" else "?$query"
        }

        /** Java String hash masked to a non-negative Long, as Android's `positiveHash`. */
        fun positiveHash(value: String): Long = value.hashCode().toLong() and Long.MAX_VALUE
    }
}

internal data class TvBoxLocator(val host: String, val segments: List<String>, val query: Map<String, String>) {
    companion object {
        fun parse(url: String?): TvBoxLocator? {
            val value = url?.takeIf { it.startsWith("tvbox://") } ?: return null
            val rest = value.removePrefix("tvbox://").substringBefore('#')
            val path = rest.substringBefore('?')
            val query = rest.substringAfter('?', "").split('&').filter(String::isNotEmpty).associate { pair ->
                TvBoxUrls.decode(pair.substringBefore('=')) to TvBoxUrls.decode(pair.substringAfter('=', ""))
            }
            val parts = path.split('/')
            return TvBoxLocator(parts.first(), parts.drop(1).filter(String::isNotEmpty).map(TvBoxUrls::decode), query)
        }
    }
}

private fun JsonObject.firstNonBlank(vararg keys: String): String? = keys.firstNotNullOfOrNull { string(it) }
