package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.library.SourceBookmark
import org.skepsun.kototoro.core.library.SourceLibraryEntry

class IosLibraryEntry internal constructor(value: SourceLibraryEntry) {
    val content = IosSourceContent(value.content)
    val categoryIds = value.categoryIds.map(Long::toInt)
    val updatedAt = value.updatedAt
    val progress = value.progressPercent ?: 0f
    val hasProgress = value.progressPercent != null
}

data class IosReadingPosition(val chapterId: String, val index: Int, val scroll: Float, val progress: Float)

class IosBookmark internal constructor(internal val value: SourceBookmark) {
    val id: String get() = value.pageId.toString()
    val chapterId: String get() = value.chapterId.toString()
    val index: Int get() = value.page
    val scroll: Int get() = value.scroll
    val preview: String get() = value.preview
}

data class IosUpdate(val content: IosSourceContent, val newChapters: Int, val checkedAt: Long)
data class IosUpdateReport(val checked: Int, val withUpdates: Int, val failed: Int, val newChapters: Int)
