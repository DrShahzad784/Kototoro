package org.skepsun.kototoro.core.library

internal actual fun sourceNovelBookmarkPreview(value: String): String =
    org.skepsun.kototoro.bookmarks.domain.parseNovelBookmarkPreview(value)
