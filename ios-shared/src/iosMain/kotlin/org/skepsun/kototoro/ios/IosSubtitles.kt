package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.source.SourceExternalTrack

/** A side-loaded track a source attaches to a stream (Aniyomi `Video.subtitleTracks` / `audioTracks`). */
class IosExternalTrack internal constructor(internal val value: SourceExternalTrack) {
    val url: String get() = value.url
    /** The source's label: a language name or code, sometimes empty. */
    val lang: String get() = value.lang
    /** Track headers; the player adds them over the stream's own headers. */
    val headers: Map<String, String> get() = value.headers.orEmpty()
}

class IosSubtitleCue internal constructor(val startMs: Long, val endMs: Long, val text: String)

/**
 * Cues of an external WebVTT, SubRip or ASS/SSA subtitle file, for a text overlay over AVPlayer (which cannot add a
 * side-loaded subtitle to an HLS or progressive stream). Styling, positioning and karaoke are dropped; overlapping
 * cues are shown together, earliest first.
 */
class IosSubtitles private constructor(val cues: List<IosSubtitleCue>) {
    private val longest = cues.maxOfOrNull { it.endMs - it.startMs } ?: 0L

    val isEmpty: Boolean get() = cues.isEmpty()

    /** The text shown at [ms], or null when no cue is active. */
    fun textAt(ms: Long): String? {
        // Cues are sorted by start: the active ones start at or before [ms] and no earlier than the longest cue allows.
        var index = upperBound(ms) - 1
        val active = ArrayList<IosSubtitleCue>(2)
        while (index >= 0 && cues[index].startMs >= ms - longest) {
            val cue = cues[index]
            if (ms < cue.endMs) active += cue
            index--
        }
        if (active.isEmpty()) return null
        return active.asReversed().joinToString("\n") { it.text }
    }

    private fun upperBound(ms: Long): Int {
        var low = 0
        var high = cues.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startMs <= ms) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        /** Detects the format from the content; [name] (URL or file name) only breaks ties. Never throws. */
        fun parse(text: String, name: String = ""): IosSubtitles {
            val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
            val head = normalized.trimStart()
            val cues = when {
                head.startsWith("WEBVTT") -> parseCueBlocks(normalized, vtt = true)
                head.startsWith("[Script Info]", ignoreCase = true) ||
                    normalized.contains("\nDialogue:") || name.substringBefore('?').endsWith(".ass", true) ||
                    name.substringBefore('?').endsWith(".ssa", true) -> parseAss(normalized)
                else -> parseCueBlocks(normalized, vtt = false)
            }
            // A stable sort keeps file order for cues that start together.
            return IosSubtitles(cues.filter { it.endMs > it.startMs && it.text.isNotBlank() }.sortedBy { it.startMs })
        }

        /** WebVTT and SubRip share the cue block shape: optional identifier, `start --> end [settings]`, text. */
        private fun parseCueBlocks(text: String, vtt: Boolean): List<IosSubtitleCue> {
            val result = ArrayList<IosSubtitleCue>()
            val lines = text.split('\n')
            var index = 0
            while (index < lines.size) {
                val line = lines[index]
                val arrow = line.indexOf("-->")
                if (arrow < 0) {
                    index++
                    // Skip WebVTT NOTE / STYLE / REGION bodies, so they are never read as cue text; such blocks
                    // cannot contain an arrow, so a timing line ends one even without the blank separator.
                    if (vtt && (line.startsWith("NOTE") || line == "STYLE" || line == "REGION")) {
                        while (index < lines.size && lines[index].isNotBlank() && !lines[index].contains("-->")) index++
                    }
                    continue
                }
                val start = timestamp(line.substring(0, arrow).trim())
                val end = timestamp(line.substring(arrow + 3).trim().substringBefore(' ').substringBefore('\t'))
                index++
                val body = ArrayList<String>()
                while (index < lines.size && lines[index].isNotBlank() && !lines[index].contains("-->")) {
                    body += lines[index]
                    index++
                }
                if (start != null && end != null) {
                    result += IosSubtitleCue(start, end, cleanMarkup(body.joinToString("\n")))
                }
            }
            return result
        }

        /** `hh:mm:ss.mmm`, `mm:ss.mmm` (WebVTT) or `hh:mm:ss,mmm` (SubRip); fraction digits are optional. */
        private fun timestamp(value: String): Long? {
            val parts = value.replace(',', '.').split(':')
            if (parts.size !in 2..3) return null
            val hours = if (parts.size == 3) parts[0].trim().toLongOrNull() ?: return null else 0L
            val minutes = parts[parts.size - 2].trim().toLongOrNull() ?: return null
            val seconds = parts.last().trim()
            val whole = seconds.substringBefore('.').toLongOrNull() ?: return null
            val fraction = seconds.substringAfter('.', "").take(3).padEnd(3, '0').toLongOrNull() ?: return null
            return ((hours * 60 + minutes) * 60 + whole) * 1000 + fraction
        }

        /** Tags (`<i>`, `<c.yellow>`, `<00:01.000>`, `{\an8}`) are dropped; common entities are decoded. */
        private fun cleanMarkup(text: String): String = text
            .replace(Regex("<[^>\n]*>"), "")
            .replace(Regex("\\{\\\\[^}]*\\}"), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")
            .lines().joinToString("\n") { it.trim() }
            .trim()

        private fun parseAss(text: String): List<IosSubtitleCue> {
            val result = ArrayList<IosSubtitleCue>()
            var inEvents = false
            // V4+ default order, used until the section names its own.
            var format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
            for (raw in text.split('\n')) {
                val line = raw.trim()
                if (line.startsWith("[")) {
                    inEvents = line.equals("[Events]", ignoreCase = true)
                    continue
                }
                if (!inEvents) continue
                val colon = line.indexOf(':')
                if (colon < 0) continue
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1)
                when {
                    key.equals("Format", true) -> format = value.split(',').map { it.trim().lowercase() }
                    key.equals("Dialogue", true) -> {
                        // Text is the last field and may itself contain commas.
                        val fields = value.split(',', limit = format.size)
                        if (fields.size < format.size) continue
                        val start = assTime(fields[format.indexOf("start")])
                        val end = assTime(fields[format.indexOf("end")])
                        val body = fields[format.indexOf("text").takeIf { it >= 0 } ?: (fields.size - 1)]
                        if (start != null && end != null) result += IosSubtitleCue(start, end, assText(body))
                    }
                }
            }
            return result
        }

        /** `h:mm:ss.cc` (centiseconds). */
        private fun assTime(value: String): Long? {
            val parts = value.trim().split(':')
            if (parts.size != 3) return null
            val hours = parts[0].toLongOrNull() ?: return null
            val minutes = parts[1].toLongOrNull() ?: return null
            val whole = parts[2].substringBefore('.').toLongOrNull() ?: return null
            val centis = parts[2].substringAfter('.', "0").take(2).padEnd(2, '0').toLongOrNull() ?: return null
            return ((hours * 60 + minutes) * 60 + whole) * 1000 + centis * 10
        }

        private fun assText(value: String): String = value
            .replace(Regex("\\{[^}]*\\}"), "")
            .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
            .lines().joinToString("\n") { it.trim() }
            .trim()
    }
}
