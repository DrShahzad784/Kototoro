package org.skepsun.kototoro.core.ui.compose

import org.skepsun.kototoro.core.prefs.ListMode

// Cover geometry is shared by Android and desktop in core-ui.
fun ListMode.gridPosterCardStyle(gridScale: Float): CompactPosterCardStyle =
    if (isMovieGrid) moviePosterCardStyle(gridScale) else compactPosterCardStyle(gridScale)

fun ListMode.railPosterCardStyle(gridScale: Float): CompactPosterCardStyle =
    if (isMovieGrid) moviePosterCardStyle(gridScale) else compactPosterRailCardStyle(gridScale)
