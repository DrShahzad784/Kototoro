package org.skepsun.kototoro.tvbox

/** URL helpers matching Android's TVBox repository (OkHttp / android.net.Uri) without platform URL types. */
internal object TvBoxUrls {
    private val playableMarkers = listOf(".m3u8", ".mp4", ".flv", ".mpd", ".mkv", ".webm", ".avi", ".mov")

    /** TVBox locators may carry `;md5;…`, `;pk;…` or other `;` suffixes after the actual address. */
    fun primaryLocator(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed
        for (marker in listOf(";md5;", ";pk;")) {
            val index = trimmed.indexOf(marker, ignoreCase = true)
            if (index >= 0) return trimmed.substring(0, index).trim()
        }
        val separator = trimmed.indexOf(';')
        return if (separator >= 0) trimmed.substring(0, separator).trim() else trimmed
    }

    fun isHttp(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

    fun isPlayableStream(value: String): Boolean {
        val normalized = primaryLocator(value).lowercase()
        if (playableMarkers.any { it in normalized }) return true
        return normalized.startsWith("rtmp://") || normalized.startsWith("rtsp://")
    }

    fun resolve(baseUrl: String?, rawUrl: String?): String? {
        val value = rawUrl?.let(::primaryLocator).orEmpty()
        if (value.isEmpty()) return null
        if (isHttp(value)) return value
        if (value.startsWith("//")) return "https:$value"
        if (value.contains("://")) return value
        val base = baseUrl?.let(::primaryLocator)?.takeIf(::isHttp) ?: return value
        return resolveHttp(base, value)
    }

    fun host(url: String?): String? {
        val value = url?.let(::primaryLocator)?.takeIf(::isHttp) ?: return null
        val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        return authority.substringAfterLast('@').substringBefore(':').lowercase().ifEmpty { null }
    }

    fun origin(url: String?): String? {
        val value = url?.let(::primaryLocator)?.takeIf(::isHttp) ?: return null
        val scheme = value.substringBefore("://").lowercase()
        return host(value)?.let { "$scheme://$it/" }
    }

    /** Appends query parameters, percent-encoding like OkHttp's `addQueryParameter`. */
    fun withQuery(baseUrl: String, params: List<Pair<String, String>>): String? {
        if (!isHttp(baseUrl)) return null
        val fragmentIndex = baseUrl.indexOf('#')
        val withoutFragment = if (fragmentIndex >= 0) baseUrl.substring(0, fragmentIndex) else baseUrl
        if (params.isEmpty()) return withoutFragment
        val query = params.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        val separator = when {
            '?' !in withoutFragment -> "?"
            withoutFragment.endsWith('?') || withoutFragment.endsWith('&') -> ""
            else -> "&"
        }
        return withoutFragment + separator + query
    }

    /** android.net.Uri.encode: everything except letters, digits and `-_.!~*'()` is UTF-8 percent-encoded. */
    fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val char = (byte.toInt() and 0xFF).toChar()
            if (char.isLetterOrDigit() && char.code < 0x80 || char in "-_.!~*'()") {
                append(char)
            } else {
                append('%')
                append(HEX[(byte.toInt() shr 4) and 0xF])
                append(HEX[byte.toInt() and 0xF])
            }
        }
    }

    /** Query-parameter decoding like android.net.Uri.getQueryParameter (`+` is a space). */
    fun decode(value: String): String {
        if ('%' !in value && '+' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 <= value.lastIndex) {
                val code = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (code != null) {
                    bytes += code.toByte()
                    index += 3
                    continue
                }
            }
            (if (char == '+') " " else char.toString()).encodeToByteArray().forEach { bytes += it }
            index++
        }
        return bytes.toByteArray().decodeToString()
    }

    /**
     * Converts internationalized host labels to ASCII (RFC 3492 Punycode, like Android's `IDN.toASCII` before
     * fetching TVBox repositories), so platform HTTP stacks accept addresses such as `http://饭太硬.net/tv`.
     */
    fun asciiHost(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val authorityStart = schemeEnd + 3
        val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) url.length else it }
        val authority = url.substring(authorityStart, authorityEnd)
        val userInfo = authority.substringBeforeLast('@', "").let { if (it.isEmpty()) "" else "$it@" }
        val hostPort = authority.substringAfterLast('@')
        val host = hostPort.substringBefore(':')
        if (host.all { it.code < 0x80 }) return url
        val port = hostPort.substring(host.length)
        val ascii = host.split('.', '。', '．', '｡').joinToString(".") { label ->
            if (label.all { it.code < 0x80 }) label else "xn--" + punycode(label.lowercase())
        }
        return url.substring(0, authorityStart) + userInfo + ascii + port + url.substring(authorityEnd)
    }

    private fun punycode(label: String): String {
        val codePoints = buildList {
            var index = 0
            while (index < label.length) {
                val high = label[index]
                if (high.isHighSurrogate() && index + 1 < label.length && label[index + 1].isLowSurrogate()) {
                    add(((high.code - 0xD800) shl 10) + (label[index + 1].code - 0xDC00) + 0x10000)
                    index += 2
                } else {
                    add(high.code)
                    index++
                }
            }
        }
        val output = StringBuilder()
        codePoints.filter { it < 0x80 }.forEach { output.append(it.toChar()) }
        val basicCount = output.length
        var handled = basicCount
        if (basicCount > 0) output.append('-')
        var n = 128
        var delta = 0
        var bias = 72
        fun digit(value: Int): Char = if (value < 26) 'a' + value else '0' + (value - 26)
        while (handled < codePoints.size) {
            val m = codePoints.filter { it >= n }.min()
            delta += (m - n) * (handled + 1)
            n = m
            for (code in codePoints) {
                if (code < n) delta++
                if (code == n) {
                    var q = delta
                    var k = 36
                    while (true) {
                        val t = if (k <= bias) 1 else if (k >= bias + 26) 26 else k - bias
                        if (q < t) break
                        output.append(digit(t + (q - t) % (36 - t)))
                        q = (q - t) / (36 - t)
                        k += 36
                    }
                    output.append(digit(q))
                    // Bias adaptation (RFC 3492 §6.1).
                    var adapted = if (handled == basicCount) delta / 700 else delta / 2
                    adapted += adapted / (handled + 1)
                    var shift = 0
                    while (adapted > 455) {
                        adapted /= 35
                        shift += 36
                    }
                    bias = shift + 36 * adapted / (adapted + 38)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return output.toString()
    }

    /** RFC 3986 §5.2 reference resolution against an absolute http(s) base. */
    private fun resolveHttp(base: String, reference: String): String {
        val schemeEnd = base.indexOf("://")
        val scheme = base.substring(0, schemeEnd)
        val afterScheme = base.substring(schemeEnd + 3)
        val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }.let {
            if (it < 0) afterScheme.length else it
        }
        val authority = afterScheme.substring(0, authorityEnd)
        val rest = afterScheme.substring(authorityEnd).substringBefore('#')
        val basePath = rest.substringBefore('?').ifEmpty { "/" }
        val baseQuery = rest.substringAfter('?', "").takeIf { '?' in rest }
        val prefix = "$scheme://$authority"
        return when {
            reference.startsWith("?") -> prefix + basePath + reference
            reference.startsWith("#") -> prefix + basePath + (baseQuery?.let { "?$it" } ?: "") + reference
            reference.startsWith("/") -> prefix + normalize(reference)
            else -> prefix + normalize(basePath.substringBeforeLast('/', "") + "/" + reference)
        }
    }

    private fun normalize(pathWithQuery: String): String {
        val suffixIndex = pathWithQuery.indexOfFirst { it == '?' || it == '#' }
        val path = if (suffixIndex >= 0) pathWithQuery.substring(0, suffixIndex) else pathWithQuery
        val suffix = if (suffixIndex >= 0) pathWithQuery.substring(suffixIndex) else ""
        val output = ArrayList<String>()
        val segments = path.split('/')
        segments.forEachIndexed { index, segment ->
            when (segment) {
                "." -> if (index == segments.lastIndex) output += ""
                ".." -> {
                    if (output.size > 1) output.removeAt(output.lastIndex)
                    if (index == segments.lastIndex) output += ""
                }
                else -> output += segment
            }
        }
        val joined = output.joinToString("/")
        return (if (joined.startsWith('/')) joined else "/$joined") + suffix
    }

    private const val HEX = "0123456789ABCDEF"
}
