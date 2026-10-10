package org.skepsun.kototoro.core.library

/** The native novel renderer supplies decoded plain text; retain the shared length/whitespace policy. */
internal actual fun sourceNovelBookmarkPreview(value: String): String =
    org.skepsun.kototoro.bookmarks.domain.normalizeNovelBookmarkPreview(value) { text ->
        text.replace(Regex("<[^>]+>"), " ")
    }
