package org.skepsun.kototoro.reader.render.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.reader.core.PageTurnStyle
import org.skepsun.kototoro.reader.core.PageTurnTransform
import org.skepsun.kototoro.reader.core.PageTurnTransition
import org.skepsun.kototoro.reader.core.PagePoint
import org.skepsun.kototoro.reader.core.PagedSlotTransitionInput
import org.skepsun.kototoro.reader.ui.compose.ComposeReaderPageCurlGeometry
import org.skepsun.kototoro.reader.ui.compose.toComposeGeometry

/**
 * Visual page-transition style of the paged scene host; the rules live in `reader-core`
 * ([PageTurnTransition]) so every host, including iOS, turns pages the same way.
 */
typealias ScenePageTransition = PageTurnStyle

/** Draw-phase transform for one slot; see [PageTurnTransform]. */
typealias ScenePageTransform = PageTurnTransform

/**
 * Maps the user's persisted animation preference onto a scene transition style.
 *
 * `NONE` shares the [ScenePageTransition.SLIDE] visuals with `DEFAULT`; the two differ only in
 * whether the release is animated at all, which the host decides from `isAnimationEnabled` and
 * the preference itself.
 */
fun resolveScenePageTransition(animation: ReaderAnimation): ScenePageTransition = when (animation) {
    ReaderAnimation.NONE, ReaderAnimation.DEFAULT -> ScenePageTransition.SLIDE
    ReaderAnimation.ADVANCED -> ScenePageTransition.COVER
    ReaderAnimation.SIMULATION -> ScenePageTransition.CURL
}

/** Compose-typed entry points over the shared [PageTurnTransition] rules. */
object ScenePageTransitionRenderer {

    /** Below this magnitude a cover navigation counts as settled rather than in flight. */
    const val COVER_PROGRESS_EPSILON = PageTurnTransition.COVER_PROGRESS_EPSILON

    /** See [PageTurnTransition.coverPinnedSlot]. */
    fun resolveSceneCoverPinnedSlot(settledSlot: Int, travelFraction: Float): Int =
        PageTurnTransition.coverPinnedSlot(settledSlot, travelFraction)

    /** See [PageTurnTransition.coverPinShift]. */
    fun resolveSceneCoverPinShift(
        slotIndex: Int,
        settledSlot: Int,
        travelFraction: Float,
        baseScreenOffset: Float,
        inFlight: Boolean,
    ): Float = PageTurnTransition.coverPinShift(slotIndex, settledSlot, travelFraction, baseScreenOffset, inFlight)

    fun transformFor(
        transition: ScenePageTransition,
        input: PagedSlotTransitionInput,
        isVertical: Boolean,
        isReversed: Boolean,
    ): ScenePageTransform = PageTurnTransition.transformFor(transition, input, isVertical, isReversed)

    /**
     * Fold geometry for a slot whose transform is actually folding, or `null` while the slot is
     * flat; the scene folds from the same reading edge as the legacy hosts.
     */
    @Suppress("UNUSED_PARAMETER")
    fun resolveCurlGeometry(
        size: Size,
        transform: ScenePageTransform,
        downFraction: Offset,
        horizontalDragFraction: Float,
        isVertical: Boolean,
        isReversed: Boolean,
    ): ComposeReaderPageCurlGeometry? = PageTurnTransition.curlGeometry(
        width = size.width,
        height = size.height,
        transform = transform,
        downFraction = PagePoint(downFraction.x, downFraction.y),
        isVertical = isVertical,
        isReversed = isReversed,
    )?.toComposeGeometry()
}
