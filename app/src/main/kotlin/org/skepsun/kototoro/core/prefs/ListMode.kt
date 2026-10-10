package org.skepsun.kototoro.core.prefs

import androidx.annotation.Keep

@Keep
enum class ListMode {

    LIST, DETAILED_LIST, GRID, COMPACT_GRID, MOVIE_GRID, COMPACT_MOVIE_GRID;

    val isGrid: Boolean
        get() = this != LIST && this != DETAILED_LIST

    val isMovieGrid: Boolean
        get() = this == MOVIE_GRID || this == COMPACT_MOVIE_GRID

    val hasTitleOverlay: Boolean
        get() = this == COMPACT_GRID || this == COMPACT_MOVIE_GRID
}
