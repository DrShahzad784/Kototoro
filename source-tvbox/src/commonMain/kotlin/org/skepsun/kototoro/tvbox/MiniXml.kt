package org.skepsun.kototoro.tvbox

/**
 * Just enough XML for MacCMS `<rss><list><video>` responses: elements, attributes, text, CDATA and the predefined /
 * numeric entities. DOCTYPE and processing instructions are skipped and never expanded, matching the Android
 * DocumentBuilder setup that disallows doctype declarations and external entities.
 */
internal class XmlElement(val name: String, val attributes: Map<String, String>) {
    val children = mutableListOf<XmlElement>()
    private val text = StringBuilder()

    /** All text below this element, like DOM `textContent`. */
    val textContent: String
        get() = buildString { appendText(this) }

    private fun appendText(target: StringBuilder) {
        target.append(text)
        children.forEach { it.appendText(target) }
    }

    internal fun addText(value: String) {
        text.append(value)
    }

    fun descendants(name: String): List<XmlElement> = buildList { collect(name, this) }

    private fun collect(name: String, target: MutableList<XmlElement>) {
        children.forEach { child ->
            if (child.name.equals(name, ignoreCase = true)) target += child
            child.collect(name, target)
        }
    }

    /** Text of the first direct child with this name; blank text is absent. */
    fun childText(name: String): String? =
        children.firstOrNull { it.name.equals(name, ignoreCase = true) }?.textContent?.trim()?.ifBlank { null }

    fun attribute(name: String): String? = attributes.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

internal object MiniXml {
    /** Returns a synthetic root holding the document's top-level elements, or null when the input is not XML. */
    fun parse(input: String): XmlElement? {
        val root = XmlElement("#document", emptyMap())
        val stack = ArrayDeque<XmlElement>().apply { addLast(root) }
        var index = 0
        while (index < input.length) {
            val open = input.indexOf('<', index)
            if (open < 0) {
                stack.last().addText(decodeEntities(input.substring(index)))
                break
            }
            if (open > index) stack.last().addText(decodeEntities(input.substring(index, open)))
            when {
                input.startsWith("<!--", open) -> index = input.indexOf("-->", open).let { if (it < 0) input.length else it + 3 }
                input.startsWith("<![CDATA[", open) -> {
                    val end = input.indexOf("]]>", open)
                    val stop = if (end < 0) input.length else end
                    stack.last().addText(input.substring(open + 9, stop))
                    index = if (end < 0) input.length else end + 3
                }
                input.startsWith("<?", open) -> index = input.indexOf("?>", open).let { if (it < 0) input.length else it + 2 }
                input.startsWith("<!", open) -> index = skipDeclaration(input, open)
                input.startsWith("</", open) -> {
                    val end = input.indexOf('>', open)
                    if (end < 0) return root.takeIf { it.children.isNotEmpty() }
                    val name = input.substring(open + 2, end).trim()
                    // Tolerate stray closing tags: only unwind when the element is actually open.
                    if (stack.drop(1).any { it.name.equals(name, ignoreCase = true) }) {
                        while (stack.size > 1) {
                            if (stack.removeLast().name.equals(name, ignoreCase = true)) break
                        }
                    }
                    index = end + 1
                }
                else -> {
                    val end = findTagEnd(input, open + 1)
                    if (end < 0) return root.takeIf { it.children.isNotEmpty() }
                    val selfClosing = input[end - 1] == '/'
                    val body = input.substring(open + 1, if (selfClosing) end - 1 else end)
                    val element = parseTag(body) ?: return null
                    stack.last().children += element
                    if (!selfClosing) stack.addLast(element)
                    index = end + 1
                }
            }
        }
        return root.takeIf { it.children.isNotEmpty() }
    }

    private fun skipDeclaration(input: String, start: Int): Int {
        var depth = 0
        var index = start
        while (index < input.length) {
            when (input[index]) {
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) return index + 1
            }
            index++
        }
        return input.length
    }

    private fun findTagEnd(input: String, start: Int): Int {
        var quote: Char? = null
        for (index in start until input.length) {
            val char = input[index]
            when {
                quote != null -> if (char == quote) quote = null
                char == '"' || char == '\'' -> quote = char
                char == '>' -> return index
            }
        }
        return -1
    }

    private val nameRegex = Regex("""^[A-Za-z_:][\w:.\-]*""")
    private val attributeRegex = Regex("""([A-Za-z_:][\w:.\-]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))""")

    private fun parseTag(body: String): XmlElement? {
        val trimmed = body.trim()
        val name = nameRegex.find(trimmed)?.value ?: return null
        val attributes = attributeRegex.findAll(trimmed.substring(name.length)).associate { match ->
            val value = match.groups[3]?.value ?: match.groups[4]?.value ?: match.groups[5]?.value.orEmpty()
            match.groupValues[1] to decodeEntities(value)
        }
        return XmlElement(name, attributes)
    }

    private val entityRegex = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")

    fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        return entityRegex.replace(value) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                    code?.takeIf { it in 1..0x10FFFF }?.let(::codePointToString) ?: match.value
                }
            }
        }
    }

    private fun codePointToString(code: Int): String = if (code < 0x10000) {
        code.toChar().toString()
    } else {
        val offset = code - 0x10000
        charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
    }
}
