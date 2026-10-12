package org.skepsun.kototoro.reader.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Visual page-turn style of a paged reader; the persisted `ReaderAnimation` preference maps onto it
 * (`NONE`/`DEFAULT` → [SLIDE], `ADVANCED` → [COVER], `SIMULATION` → [CURL]).
 *
 * Scene geometry and transition visuals are deliberately separate concerns (ADR 0002 I3):
 * [PagedReaderScene] decides where slots are, these rules decide how a slot looks while a turn is
 * in flight. Pure semantics shared by the Compose hosts and iOS; no platform types (I1).
 */
enum class PageTurnStyle {
    /** Pager-like motion: the scroll offset alone moves the slots. */
    SLIDE,

    /** The settled page stays in place while the incoming page slides over it. */
    COVER,

    /** The turning page folds like paper and reveals the next page underneath. */
    CURL,
}

/**
 * Draw-phase transform for one slot, in abstract units: [translationFactor] is a fraction of the
 * slot's own size (added to its scroll position), [foldProgress] drives [PageCurlGeometry].
 */
data class PageTurnTransform(
    val translationFactor: Float = 0f,
    val alpha: Float = 1f,
    val zIndex: Float = 0f,
    val foldProgress: Float = 0f,
    val isCurlUnfolding: Boolean = false,
    val revealedPageShade: Float = 0f,
)

/** A point in slot-local pixels. */
data class PagePoint(val x: Float, val y: Float) {
    operator fun minus(other: PagePoint) = PagePoint(x - other.x, y - other.y)

    companion object {
        val Zero = PagePoint(0f, 0f)
    }
}

/**
 * The fold of a curling page in slot-local pixels: the page is drawn clipped to [frontPath], and its
 * back mirrored across the fold line — scale `-1` on the reading axis around [bottomCurlOffset],
 * then rotate by [angle] radians (`scale(1, -1)` and `-angle` on a vertical axis) — clipped to [backPath].
 */
data class PageCurlGeometry(
    val topCurlOffset: PagePoint,
    val bottomCurlOffset: PagePoint,
    val frontPath: List<PagePoint>,
    val backPath: List<PagePoint>,
    val angle: Float,
    val curlLineVector: PagePoint,
)

object PageTurnTransition {

    /** Below this magnitude a cover navigation counts as settled rather than in flight. */
    const val COVER_PROGRESS_EPSILON = 0.001f

    /** Shade the revealed page takes so a fold reads as depth rather than a flat swap. */
    private const val REVEALED_SHADE_FACTOR = 0.16f

    /** A turning page only starts fading this far into its fold. */
    private const val FADE_START_PROGRESS = 0.92f

    /** Fold progress span over which the turning page fades from opaque to invisible. */
    private const val FADE_SPAN = 0.08f

    private const val PAGE_CURL_DRAG_EPSILON = 0.001f

    fun transformFor(
        style: PageTurnStyle,
        input: PagedSlotTransitionInput,
        isVertical: Boolean,
        isReversed: Boolean,
    ): PageTurnTransform = when (style) {
        // A scroll-driven host already moves every slot with the paging offset, so a slide needs no
        // additional per-slot translation: adding one would move the page twice per finger pixel.
        PageTurnStyle.SLIDE -> PageTurnTransform()
        PageTurnStyle.COVER -> coverTransform(input, isReversed)
        PageTurnStyle.CURL -> curlTransform(input, isVertical, isReversed)
    }

    /**
     * Reading-order slot the cover keeps static at the viewport for the current travel.
     *
     * The cover is asymmetric by design (matching the legacy transform's layering): a forward turn
     * keeps the page being left on top as the active page and reveals the entering page beneath it,
     * so the entering page is the pinned one; a backward turn layers the entering (previous) page
     * over the page being left, so the page being left is the pinned one and the previous page
     * slides over it.
     */
    fun coverPinnedSlot(settledSlot: Int, travelFraction: Float): Int =
        if (travelFraction >= 0f) settledSlot + 1 else settledSlot

    /** The pinned slot while a cover is in flight for [motion], or null when the cover is at rest. */
    fun activeCoverPinnedSlot(motion: PagedMotionSnapshot): Int? {
        val travel = motion.currentSlot - motion.settledSlot + motion.offsetFraction
        val inFlight = motion.isScrollInProgress || abs(travel) > COVER_PROGRESS_EPSILON
        return if (inFlight) coverPinnedSlot(motion.settledSlot, travel) else null
    }

    /**
     * Screen displacement for the cover style's static page in a scroll-driven host: the active
     * page tracks the finger at its scroll position while the static page underneath cancels its
     * scroll base, so it stays pinned at the viewport. The pin only engages while a cover is in
     * flight, and starts and ends with the static page at its plain scroll position.
     */
    fun coverPinShift(
        slotIndex: Int,
        settledSlot: Int,
        travelFraction: Float,
        baseScreenOffset: Float,
        inFlight: Boolean,
    ): Float {
        if (!inFlight) return 0f
        if (slotIndex != coverPinnedSlot(settledSlot, travelFraction)) return 0f
        return -baseScreenOffset
    }

    /** Whether the active curl folds the page back in (turning backwards) rather than away. */
    fun isCurlUnfolding(
        settledPage: Int,
        targetPage: Int,
        horizontalDragFraction: Float,
        isReadingReversed: Boolean,
        verticalDragFraction: Float = 0f,
        isVertical: Boolean = false,
    ): Boolean = when {
        targetPage < settledPage -> true
        targetPage > settledPage -> false
        isVertical -> verticalDragFraction > PAGE_CURL_DRAG_EPSILON
        isReadingReversed -> horizontalDragFraction < -PAGE_CURL_DRAG_EPSILON
        else -> horizontalDragFraction > PAGE_CURL_DRAG_EPSILON
    }

    /** Mirrored reading folds from the physical start edge. */
    fun curlFromStart(isReadingReversed: Boolean): Boolean = isReadingReversed

    /** The fold's origin from where the finger went down, as fractions of the slot. */
    fun curlStartFraction(downFraction: PagePoint, isVertical: Boolean, curlFromStart: Boolean): PagePoint =
        if (isVertical) downFraction else PagePoint(if (curlFromStart) 0f else 1f, downFraction.y)

    /**
     * Fold geometry for a slot whose [transform] is actually folding, or `null` while the slot is
     * flat; the fold starts from the reading edge at the height the finger went down.
     */
    fun curlGeometry(
        width: Float,
        height: Float,
        transform: PageTurnTransform,
        downFraction: PagePoint,
        isVertical: Boolean,
        isReversed: Boolean,
    ): PageCurlGeometry? {
        if (transform.foldProgress <= 0f) return null
        val fromStart = curlFromStart(isReversed)
        return curlGeometry(
            width = width,
            height = height,
            progress = transform.foldProgress,
            touchFraction = curlStartFraction(downFraction, isVertical, fromStart),
            isVertical = isVertical,
            isReversed = fromStart,
            isCurlUnfolding = transform.isCurlUnfolding,
        )
    }

    fun curlGeometry(
        width: Float,
        height: Float,
        progress: Float,
        touchFraction: PagePoint,
        isVertical: Boolean,
        isReversed: Boolean,
        isCurlUnfolding: Boolean = false,
    ): PageCurlGeometry {
        val canonicalWidth = if (isVertical) height else width
        val canonicalHeight = if (isVertical) width else height
        if (canonicalWidth <= 0f || canonicalHeight <= 0f) {
            return PageCurlGeometry(PagePoint.Zero, PagePoint.Zero, emptyList(), emptyList(), 0f, PagePoint.Zero)
        }
        val edge = curlEdge(
            width = canonicalWidth,
            height = canonicalHeight,
            progress = progress,
            startFraction = (if (isVertical) touchFraction.x else touchFraction.y).let { dragOrigin ->
                if (isCurlUnfolding) 1f - dragOrigin else dragOrigin
            },
            isReversed = isReversed,
        )
        val topIntersection = intersection(
            PagePoint(0f, 0f), PagePoint(canonicalWidth, 0f), edge.top, edge.bottom,
        ) ?: edge.top
        val bottomIntersection = intersection(
            PagePoint(0f, canonicalHeight), PagePoint(canonicalWidth, canonicalHeight), edge.top, edge.bottom,
        ) ?: edge.bottom
        val topCurlOffset = if (isReversed) {
            PagePoint(min(canonicalWidth, topIntersection.x), 0f)
        } else {
            PagePoint(max(0f, topIntersection.x), 0f)
        }
        val bottomCurlOffset = if (isReversed) {
            PagePoint(min(canonicalWidth, bottomIntersection.x), canonicalHeight)
        } else {
            PagePoint(max(0f, bottomIntersection.x), canonicalHeight)
        }
        val frontPath = if (isReversed) {
            listOf(
                PagePoint(canonicalWidth, 0f),
                topCurlOffset,
                bottomCurlOffset,
                PagePoint(canonicalWidth, canonicalHeight),
            )
        } else {
            listOf(PagePoint.Zero, topCurlOffset, bottomCurlOffset, PagePoint(0f, canonicalHeight))
        }
        val backPath = curlBackPath(canonicalWidth, canonicalHeight, topCurlOffset, bottomCurlOffset, isReversed)
        fun map(point: PagePoint): PagePoint = if (isVertical) PagePoint(point.y, point.x) else point
        return PageCurlGeometry(
            topCurlOffset = map(topCurlOffset),
            bottomCurlOffset = map(bottomCurlOffset),
            frontPath = frontPath.map(::map),
            backPath = backPath.map(::map),
            angle = PI.toFloat() - atan2(
                bottomCurlOffset.y - topCurlOffset.y,
                bottomCurlOffset.x - topCurlOffset.x,
            ) * 2f,
            curlLineVector = map(bottomCurlOffset - topCurlOffset),
        )
    }

    /**
     * Paper-fold animation: the page being turned folds and fades out at the very end of its
     * travel, while the page revealed underneath only takes a shade so the reader sees depth
     * instead of a flat swap. Mirrored reading flips which physical side folds.
     */
    private fun curlTransform(
        input: PagedSlotTransitionInput,
        isVertical: Boolean,
        isReversed: Boolean,
    ): PageTurnTransform {
        val reversed = isReversed && !isVertical
        val isTurningPage = if (reversed) input.pageOffset >= 0f else input.pageOffset <= 0f
        val foldProgress = if (isTurningPage) abs(input.pageOffset).coerceIn(0f, 1f) else 0f
        val revealedPageShade = if (!isTurningPage && abs(input.pageOffset) <= 1f) {
            abs(input.pageOffset) * REVEALED_SHADE_FACTOR
        } else {
            0f
        }
        return PageTurnTransform(
            translationFactor = -input.pageOffset,
            alpha = if (input.pageOffset !in -1f..1f) {
                0f
            } else {
                1f - ((foldProgress - FADE_START_PROGRESS) / FADE_SPAN).coerceIn(0f, 1f)
            },
            zIndex = if (isTurningPage) 1f else 0f,
            foldProgress = foldProgress,
            isCurlUnfolding = isTurningPage && input.isCurlUnfolding,
            revealedPageShade = revealedPageShade,
        )
    }

    /**
     * The page being left stays in place while the incoming page slides in over/under it,
     * mirroring the legacy cover animation. Reading direction only flips the physical sign of the
     * travel; which slot is on top follows from whether navigation moves forward or backward.
     */
    private fun coverTransform(input: PagedSlotTransitionInput, isReversed: Boolean): PageTurnTransform {
        if (abs(input.navigationProgress) < COVER_PROGRESS_EPSILON) return PageTurnTransform()
        val isForwardNavigation = input.navigationProgress > 0f
        val physicalDirection = if (isReversed) -1f else 1f
        return when {
            input.isSettledPage && isForwardNavigation -> PageTurnTransform(zIndex = 1f)
            input.isSettledPage -> PageTurnTransform(translationFactor = input.pageOffset * physicalDirection)
            input.isIncomingPage -> PageTurnTransform(
                translationFactor = if (isForwardNavigation) input.pageOffset * physicalDirection else 0f,
                zIndex = if (isForwardNavigation) 0f else 1f,
            )
            // Slots beyond the two participants stay behind the moving pair.
            else -> PageTurnTransform(zIndex = -1f)
        }
    }

    private data class CurlEdge(val top: PagePoint, val bottom: PagePoint)

    private fun curlEdge(width: Float, height: Float, progress: Float, startFraction: Float, isReversed: Boolean): CurlEdge {
        val start = CurlEdge(PagePoint(width, 0f), PagePoint(width, height))
        val middle = CurlEdge(PagePoint(width, height / 2f), PagePoint(width / 2f, height))
        val end = CurlEdge(PagePoint(0f, 0f), PagePoint(0f, height))
        val normalized = progress.coerceIn(0f, 1f)
        val bottomEdge = if (normalized <= 1f / 3f) {
            lerpEdge(start, middle, normalized * 3f)
        } else {
            lerpEdge(middle, end, (normalized - 1f / 3f) * 1.5f)
        }
        val topIntersection = intersection(PagePoint.Zero, PagePoint(width, 0f), bottomEdge.top, bottomEdge.bottom)
            ?: bottomEdge.top
        val bottomIntersection = intersection(PagePoint(0f, height), PagePoint(width, height), bottomEdge.top, bottomEdge.bottom)
            ?: bottomEdge.bottom
        val centerX = lerp(width, 0f, normalized)
        val cornerBias = startFraction.coerceIn(0f, 1f) * 2f - 1f
        val halfSlope = (topIntersection.x - bottomIntersection.x) / 2f * cornerBias
        val edge = CurlEdge(top = PagePoint(centerX + halfSlope, 0f), bottom = PagePoint(centerX - halfSlope, height))
        return if (isReversed) {
            CurlEdge(PagePoint(width - edge.top.x, edge.top.y), PagePoint(width - edge.bottom.x, edge.bottom.y))
        } else {
            edge
        }
    }

    private fun lerpEdge(start: CurlEdge, end: CurlEdge, fraction: Float) = CurlEdge(
        top = PagePoint(lerp(start.top.x, end.top.x, fraction), lerp(start.top.y, end.top.y, fraction)),
        bottom = PagePoint(lerp(start.bottom.x, end.bottom.x, fraction), lerp(start.bottom.y, end.bottom.y, fraction)),
    )

    private fun curlBackPath(
        width: Float,
        height: Float,
        topCurlOffset: PagePoint,
        bottomCurlOffset: PagePoint,
        isReversed: Boolean,
    ): List<PagePoint> {
        val path = mutableListOf<PagePoint>()
        if (isReversed) {
            if (topCurlOffset.x > 0f) {
                path += topCurlOffset
                path += PagePoint(0f, topCurlOffset.y)
            } else {
                val crossing = intersection(topCurlOffset, bottomCurlOffset, PagePoint(0f, 0f), PagePoint(0f, height))
                    ?: PagePoint.Zero
                path += crossing
                path += crossing
            }
            if (bottomCurlOffset.x > 0f) {
                path += PagePoint(0f, height)
                path += bottomCurlOffset
            } else {
                val crossing = intersection(topCurlOffset, bottomCurlOffset, PagePoint(0f, 0f), PagePoint(0f, height))
                    ?: PagePoint(0f, height)
                path += crossing
                path += crossing
            }
        } else {
            if (topCurlOffset.x < width) {
                path += topCurlOffset
                path += PagePoint(width, topCurlOffset.y)
            } else {
                val crossing = intersection(topCurlOffset, bottomCurlOffset, PagePoint(width, 0f), PagePoint(width, height))
                    ?: PagePoint(width, 0f)
                path += crossing
                path += crossing
            }
            if (bottomCurlOffset.x < width) {
                path += PagePoint(width, height)
                path += bottomCurlOffset
            } else {
                val crossing = intersection(topCurlOffset, bottomCurlOffset, PagePoint(width, 0f), PagePoint(width, height))
                    ?: PagePoint(width, height)
                path += crossing
                path += crossing
            }
        }
        return path
    }

    private fun intersection(line1a: PagePoint, line1b: PagePoint, line2a: PagePoint, line2b: PagePoint): PagePoint? {
        val denominator = (line1a.x - line1b.x) * (line2a.y - line2b.y) - (line1a.y - line1b.y) * (line2a.x - line2b.x)
        if (denominator == 0f) return null
        val x1 = (line1a.x * line1b.y - line1a.y * line1b.x) * (line2a.x - line2b.x)
        val x2 = (line1a.x - line1b.x) * (line2a.x * line2b.y - line2a.y * line2b.x)
        val y1 = (line1a.x * line1b.y - line1a.y * line1b.x) * (line2a.y - line2b.y)
        val y2 = (line1a.y - line1b.y) * (line2a.x * line2b.y - line2a.y * line2b.x)
        return PagePoint((x1 - x2) / denominator, (y1 - y2) / denominator)
    }

    private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction
}
