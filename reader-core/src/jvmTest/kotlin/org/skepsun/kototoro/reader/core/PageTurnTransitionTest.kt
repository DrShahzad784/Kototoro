package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The shared cover and curl rules every paged host draws from (Compose scene, legacy pager, iOS). */
class PageTurnTransitionTest {

    private fun input(slot: Int, offset: Float) = PagedTransitionResolver.resolve(
        snapshot = PagedMotionSnapshot(
            currentSlot = 0, settledSlot = 0, targetSlot = 0, offsetFraction = offset, isScrollInProgress = true,
        ),
        slotIndex = slot,
        direction = SceneReadingDirection.LEFT_TO_RIGHT,
    )

    @Test
    fun `forward cover keeps the page being left on top and pins the entering page`() {
        val leaving = PageTurnTransition.transformFor(PageTurnStyle.COVER, input(0, 0.4f), false, false)
        val entering = PageTurnTransition.transformFor(PageTurnStyle.COVER, input(1, 0.4f), false, false)
        assertTrue(leaving.zIndex > entering.zIndex)
        assertEquals(0f, leaving.translationFactor)
        // The entering page cancels its scroll travel: offset 0.6 of a page, translated back by it.
        assertEquals(0.6f, entering.translationFactor, 1e-6f)
        assertEquals(1, PageTurnTransition.coverPinnedSlot(settledSlot = 0, travelFraction = 0.4f))
        assertEquals(0, PageTurnTransition.coverPinnedSlot(settledSlot = 0, travelFraction = -0.4f))
    }

    @Test
    fun `curl folds the turning page above a shaded revealed page and fades at the end`() {
        val turning = PageTurnTransition.transformFor(PageTurnStyle.CURL, input(0, 0.5f), false, false)
        val revealed = PageTurnTransition.transformFor(PageTurnStyle.CURL, input(1, 0.5f), false, false)
        assertEquals(0.5f, turning.foldProgress, 1e-6f)
        assertEquals(0f, revealed.foldProgress)
        assertTrue(turning.zIndex > revealed.zIndex)
        assertEquals(0.08f, revealed.revealedPageShade, 1e-6f)
        val late = PageTurnTransition.transformFor(PageTurnStyle.CURL, input(0, 0.96f), false, false)
        assertEquals(0.5f, late.alpha, 1e-4f)
        assertNull(PageTurnTransition.curlGeometry(400f, 600f, revealed, PagePoint(0.5f, 0.5f), false, false))
    }

    @Test
    fun `half fold from the middle edge crosses the page centre`() {
        val geometry = PageTurnTransition.curlGeometry(
            width = 400f, height = 600f, progress = 0.5f, touchFraction = PagePoint(1f, 0.5f),
            isVertical = false, isReversed = false,
        )
        // A finger at mid height folds a straight line: both curl points sit at the half-way column.
        assertEquals(200f, geometry.topCurlOffset.x, 1e-3f)
        assertEquals(200f, geometry.bottomCurlOffset.x, 1e-3f)
        assertEquals(listOf(PagePoint(0f, 0f), PagePoint(200f, 0f), PagePoint(200f, 600f), PagePoint(0f, 600f)),
            geometry.frontPath)
        assertEquals(0f, geometry.angle, 1e-5f)
        assertEquals(PagePoint(0f, 600f), geometry.curlLineVector)
    }

    @Test
    fun `vertical folds swap axes and mirrored reading folds from the start edge`() {
        val vertical = PageTurnTransition.curlGeometry(400f, 600f, 0.5f, PagePoint(0.5f, 1f), isVertical = true, isReversed = false)
        assertEquals(300f, vertical.topCurlOffset.y, 1e-3f)
        val mirrored = PageTurnTransition.curlGeometry(400f, 600f, 0.25f, PagePoint(0f, 0.5f), isVertical = false, isReversed = true)
        assertEquals(PagePoint(400f, 0f), mirrored.frontPath.first())
        assertTrue(mirrored.topCurlOffset.x < 200f)
        assertTrue(PageTurnTransition.curlFromStart(isReadingReversed = true))
    }
}
