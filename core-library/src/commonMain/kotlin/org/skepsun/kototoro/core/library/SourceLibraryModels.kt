package org.skepsun.kototoro.core.library

import org.skepsun.kototoro.core.source.SourceContent

/** Desktop projections of the shared schema; persistence types do not cross into Compose. */
data class SourceBookmark(
    val contentId: Long,
    val pageId: Long,
    val chapterId: Long,
    val page: Int,
    val scroll: Int,
    val createdAt: Long,
    val percent: Float,
    val preview: String = "",
)

data class SourceLibraryEntry(
    val content: SourceContent,
    val categoryIds: Set<Long>,
    val updatedAt: Long,
    val lastReadAt: Long?,
    val progressPercent: Float?,
    /** History only: the stored chapter last read, for "continue reading" captions. */
    val lastChapter: SourceLibraryChapter? = null,
)

data class SourceLibraryChapter(val title: String, val number: Float)

data class SourceLibraryCategory(val id: Long, val title: String)

data class SourceLibrarySnapshot(
    val entries: List<SourceLibraryEntry> = emptyList(),
    val categories: List<SourceLibraryCategory> = emptyList(),
)
