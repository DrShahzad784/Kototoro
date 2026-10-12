package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.reader.core.PageCurlGeometry
import org.skepsun.kototoro.reader.core.PagePoint
import org.skepsun.kototoro.reader.core.PageTurnTransition
import kotlin.math.abs

data class ComposeReaderPageTransform(
    val translationFactor: Float = 0f,
    val alpha: Float = 1f,
    val rotationY: Float = 0f,
    val transformOrigin: TransformOrigin = TransformOrigin.Center,
    val zIndex: Float = 0f,
    val foldProgress: Float = 0f,
    val isCurlUnfolding: Boolean = false,
    val revealedPageShade: Float = 0f,
)

fun resolveComposeReaderPageTransform(
    animation: ReaderAnimation,
    pageOffset: Float,
    isVertical: Boolean,
    isReversed: Boolean,
    navigationProgress: Float = 0f,
    isSettledPage: Boolean = false,
    isIncomingPage: Boolean = false,
    isCurlUnfolding: Boolean = false,
): ComposeReaderPageTransform = when (animation) {
    ReaderAnimation.DEFAULT -> ComposeReaderPageTransform()
    ReaderAnimation.NONE -> ComposeReaderPageTransform(
        translationFactor = when {
            pageOffset in -0.5f..0.5f -> -pageOffset
            pageOffset > 0f -> 1f
            else -> -1f
        },
    )
    ReaderAnimation.ADVANCED -> resolveCoverPageTransform(
        pageOffset = pageOffset,
        navigationProgress = navigationProgress,
        isSettledPage = isSettledPage,
        isIncomingPage = isIncomingPage,
        isReversed = isReversed,
    )
    ReaderAnimation.SIMULATION -> resolveSimulationPageTransform(
        pageOffset = pageOffset,
        isVertical = isVertical,
        isReversed = isReversed,
        isCurlUnfolding = isCurlUnfolding,
    )
}

private fun resolveCoverPageTransform(
    pageOffset: Float,
    navigationProgress: Float,
    isSettledPage: Boolean,
    isIncomingPage: Boolean,
    isReversed: Boolean,
): ComposeReaderPageTransform {
    if (abs(navigationProgress) < COVER_PAGE_EPSILON) {
        return ComposeReaderPageTransform()
    }

    val isForwardNavigation = navigationProgress > 0f
    val physicalDirection = if (isReversed) -1f else 1f
    return when {
        isSettledPage && isForwardNavigation -> ComposeReaderPageTransform(
            // The Pager's normal motion moves the current page left. The next
            // page is positioned underneath it by the adjacent-page branch below.
            zIndex = 1f,
        )
        isSettledPage -> ComposeReaderPageTransform(
            // On backward navigation the current page must remain fixed while
            // the previous page enters from the reading-direction edge above it.
            translationFactor = pageOffset * physicalDirection,
        )
        isIncomingPage && !isSettledPage -> ComposeReaderPageTransform(
            translationFactor = if (isForwardNavigation) {
                pageOffset * physicalDirection
            } else {
                0f
            },
            zIndex = if (isForwardNavigation) 0f else 1f,
        )
        else -> ComposeReaderPageTransform(zIndex = -1f)
    }
}

private const val COVER_PAGE_EPSILON = 0.001f

fun resolveAdvancedNavigationProgress(
    anchorPage: Int,
    currentPage: Int,
    currentPageOffsetFraction: Float,
): Float = ((currentPage - anchorPage) + currentPageOffsetFraction).coerceIn(-1f, 1f)

fun resolveAdvancedAnimationAnchor(
    anchorPage: Int,
    currentPage: Int,
    currentPageOffsetFraction: Float,
    isScrollInProgress: Boolean,
): Int {
    if (currentPage == anchorPage) return anchorPage
    if (abs(currentPageOffsetFraction) < COVER_PAGE_EPSILON) return currentPage
    if (!isScrollInProgress) return anchorPage

    val unboundedProgress = (currentPage - anchorPage) + currentPageOffsetFraction
    return if (abs(unboundedProgress) > 1f + COVER_PAGE_EPSILON) {
        // A new navigation started before the previous Pager animation became
        // idle. Commit the completed adjacent page as the next animation anchor.
        currentPage
    } else {
        anchorPage
    }
}

private fun resolveSimulationPageTransform(
    pageOffset: Float,
    isVertical: Boolean,
    isReversed: Boolean,
    isCurlUnfolding: Boolean,
): ComposeReaderPageTransform {
    val reversed = isReversed && !isVertical
    val isTurningPage = if (reversed) pageOffset >= 0f else pageOffset <= 0f
    val foldProgress = if (isTurningPage) abs(pageOffset).coerceIn(0f, 1f) else 0f
    val revealedPageShade = if (!isTurningPage && abs(pageOffset) <= 1f) {
        abs(pageOffset) * 0.16f
    } else {
        0f
    }
    return ComposeReaderPageTransform(
        translationFactor = -pageOffset,
        alpha = if (pageOffset !in -1f..1f) {
            0f
        } else {
            1f - ((foldProgress - 0.92f) / 0.08f).coerceIn(0f, 1f)
        },
        // Keep the revealed spread below the turning spread, but above the
        // pager's own background so its image content remains drawable.
        zIndex = if (isTurningPage) 1f else 0f,
        foldProgress = foldProgress,
        isCurlUnfolding = isTurningPage && isCurlUnfolding,
        revealedPageShade = revealedPageShade,
    )
}

@Stable
class ComposeReaderPageCurlState internal constructor() {
    var downFraction by mutableStateOf(Offset(0.75f, 0.85f))
        private set
    var touchFraction by mutableStateOf(Offset(0.75f, 0.85f))
        private set

    val horizontalDragFraction: Float
        get() = touchFraction.x - downFraction.x
    val verticalDragFraction: Float
        get() = touchFraction.y - downFraction.y

    fun beginTouch(position: Offset, viewport: Size) {
        val fraction = position.toTouchFraction(viewport) ?: return
        downFraction = fraction
        touchFraction = fraction
    }

    fun updateTouch(position: Offset, viewport: Size) {
        touchFraction = position.toTouchFraction(viewport) ?: return
    }

    fun resetDrag() {
        touchFraction = downFraction
    }

    private fun Offset.toTouchFraction(viewport: Size): Offset? {
        if (viewport.width <= 0f || viewport.height <= 0f) return null
        return Offset(
            x = (x / viewport.width).coerceIn(0f, 1f),
            y = (y / viewport.height).coerceIn(0f, 1f),
        )
    }
}

@Composable
fun rememberComposeReaderPageCurlState(): ComposeReaderPageCurlState =
    remember { ComposeReaderPageCurlState() }

fun Modifier.trackComposeReaderPageCurl(
    state: ComposeReaderPageCurlState,
    enabled: Boolean,
): Modifier = pointerInput(state, enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        val viewport = Size(size.width.toFloat(), size.height.toFloat())
        state.beginTouch(down.position, viewport)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.firstOrNull { it.pressed }?.let { change ->
                state.updateTouch(change.position, viewport)
            }
        } while (event.changes.any { it.pressed })
    }
}

fun Modifier.composeReaderPageCurl(
    transform: ComposeReaderPageTransform,
    isVertical: Boolean,
    isReadingReversed: Boolean,
    state: ComposeReaderPageCurlState,
    drawBackContent: Boolean = true,
): Modifier {
    if (transform.foldProgress <= 0f) return this
    val curlFromStart = resolvePageCurlFromStart(
        isVertical = isVertical,
        isReadingReversed = isReadingReversed,
        horizontalDragFraction = state.horizontalDragFraction,
    )
    return drawWithCache {
        val geometry = calculatePageCurlGeometry(
            size = size,
            progress = transform.foldProgress,
            touchFraction = resolvePageCurlStartFraction(
                downFraction = state.downFraction,
                isVertical = isVertical,
                curlFromStart = curlFromStart,
            ),
            isVertical = isVertical,
            isReversed = curlFromStart,
            isCurlUnfolding = transform.isCurlUnfolding,
        )
        val frontPath = geometry.frontPath.toPath()
        val backPath = geometry.backPath.toPath()
        val shadowProgress = (1f - abs(geometry.curlLineVector.y) / size.height.coerceAtLeast(1f))
        val drawBackPage: androidx.compose.ui.graphics.drawscope.ContentDrawScope.() -> Unit = backPage@{
            withTransform({
                if (isVertical) {
                    scale(1f, -1f, pivot = geometry.bottomCurlOffset)
                    rotateRad(-geometry.angle, pivot = geometry.bottomCurlOffset)
                } else {
                    scale(-1f, 1f, pivot = geometry.bottomCurlOffset)
                    rotateRad(geometry.angle, pivot = geometry.bottomCurlOffset)
                }
            }) {
                drawPath(
                    path = backPath,
                    color = Color.Black.copy(alpha = 0.12f + shadowProgress.coerceIn(0f, 1f) * 0.12f),
                    style = Stroke(width = 18f),
                )
                clipPath(backPath) {
                    if (drawBackContent) this@backPage.drawContent()
                    drawRect(Color.White.copy(alpha = 0.1f))
                }
            }
        }
        onDrawWithContent {
            clipPath(frontPath) {
                this@onDrawWithContent.drawContent()
            }
            drawBackPage()
        }
    }
}

fun resolvePageCurlFromStart(
    isVertical: Boolean,
    isReadingReversed: Boolean,
    horizontalDragFraction: Float,
): Boolean = PageTurnTransition.curlFromStart(isReadingReversed)

fun resolvePageCurlUnfolding(
    settledPage: Int,
    targetPage: Int,
    horizontalDragFraction: Float,
    isReadingReversed: Boolean,
    verticalDragFraction: Float = 0f,
    isVertical: Boolean = false,
): Boolean = PageTurnTransition.isCurlUnfolding(
    settledPage, targetPage, horizontalDragFraction, isReadingReversed, verticalDragFraction, isVertical,
)

fun resolvePageCurlStartFraction(
    downFraction: Offset,
    isVertical: Boolean,
    curlFromStart: Boolean,
): Offset = PageTurnTransition.curlStartFraction(PagePoint(downFraction.x, downFraction.y), isVertical, curlFromStart)
    .let { Offset(it.x, it.y) }

data class ComposeReaderPageCurlGeometry(
    val topCurlOffset: Offset,
    val bottomCurlOffset: Offset,
    val frontPath: List<Offset>,
    val backPath: List<Offset>,
    val angle: Float,
    val curlLineVector: Offset,
)

/** The shared fold geometry of `reader-core` ([PageTurnTransition.curlGeometry]) in Compose types. */
fun calculatePageCurlGeometry(
    size: Size,
    progress: Float,
    touchFraction: Offset,
    isVertical: Boolean,
    isReversed: Boolean,
    isCurlUnfolding: Boolean = false,
): ComposeReaderPageCurlGeometry = PageTurnTransition.curlGeometry(
    width = size.width,
    height = size.height,
    progress = progress,
    touchFraction = PagePoint(touchFraction.x, touchFraction.y),
    isVertical = isVertical,
    isReversed = isReversed,
    isCurlUnfolding = isCurlUnfolding,
).toComposeGeometry()

internal fun PageCurlGeometry.toComposeGeometry(): ComposeReaderPageCurlGeometry {
    fun PagePoint.offset() = Offset(x, y)
    return ComposeReaderPageCurlGeometry(
        topCurlOffset = topCurlOffset.offset(),
        bottomCurlOffset = bottomCurlOffset.offset(),
        frontPath = frontPath.map { it.offset() },
        backPath = backPath.map { it.offset() },
        angle = angle,
        curlLineVector = curlLineVector.offset(),
    )
}

fun List<Offset>.toPath(): Path = Path().apply {
    forEachIndexed { index, point ->
        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
}

const val READER_PAGE_CAMERA_DISTANCE = 20_000f

@Composable
fun ComposeReaderSimulationPageShadow(
    transform: ComposeReaderPageTransform,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (transform.revealedPageShade > 0f) {
            drawRect(Color.Black.copy(alpha = transform.revealedPageShade))
        }
    }
}
