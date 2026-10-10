package org.skepsun.kototoro.ios

import org.skepsun.kototoro.reader.core.*

data class IosReaderPlacement(
    val index: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val segment: Int = 0,
)
data class IosReaderSlot(val index: Int, val anchor: Int, val placements: List<IosReaderPlacement>)
data class IosReaderProgress(val index: Int, val offset: Float)
data class IosReaderSize(val width: Int, val height: Int)
data class IosReaderRect(val left: Int, val top: Int, val width: Int, val height: Int)
data class IosReaderTile(
    val row: Int,
    val col: Int,
    val sampleSize: Int,
    val logicalLeft: Int,
    val logicalTop: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    val decodeLeft: Int,
    val decodeTop: Int,
    val decodeWidth: Int,
    val decodeHeight: Int,
)

/** Geometry and reading semantics stay in reader-core; SwiftUI owns pixels, gestures and native scrolling. */
class IosReader {
    private var scene: MutableReaderScene? = null
    private var width = 1
    private var height = 1
    private val sizes = mutableMapOf<Int, PageGeometryHint>()
    private val specLookup = mutableMapOf<Long, Pair<Int, Int>>()
    private var count = 0
    private var mode = 0
    private var doublePage = false
    private var cover = false
    private var fit = 0
    private var splitWide = false

    fun configure(count: Int, width: Int, height: Int, mode: Int, doublePage: Boolean, cover: Boolean, fit: Int) =
        configure(count, width, height, mode, doublePage, cover, fit, false)

    fun configure(
        count: Int,
        width: Int,
        height: Int,
        mode: Int,
        doublePage: Boolean,
        cover: Boolean,
        fit: Int,
        splitWide: Boolean,
    ) {
        require(count >= 0)
        this.count = count; this.width = width.coerceAtLeast(1); this.height = height.coerceAtLeast(1)
        this.mode = mode; this.doublePage = doublePage; this.cover = cover; this.fit = fit; this.splitWide = splitWide
        sizes.keys.retainAll(0 until count)
        rebuild()
    }

    fun reset() { sizes.clear(); specLookup.clear(); scene = null; count = 0 }

    fun setImageSize(index: Int, width: Int, height: Int) {
        if (index !in 0 until count || width <= 0 || height <= 0) return
        val hint = PageGeometryHint.Exact(width, height)
        if (sizes[index] == hint) return
        sizes[index] = hint
        if (splitWide && !doublePage && mode < 3) {
            rebuild()
        } else {
            scene?.updatePageHint(PageId(index.toLong()), hint)
        }
    }

    private fun rebuild() {
        specLookup.clear()
        val pages = (0 until count).map { PageId(it.toLong()) to (sizes[it] ?: PageGeometryHint.Estimated(0.7f)) }
        val direction = if (mode == 1 || mode == 4) SceneReadingDirection.RIGHT_TO_LEFT else
            if (mode == 2 || mode == 3) SceneReadingDirection.TOP_TO_BOTTOM else SceneReadingDirection.LEFT_TO_RIGHT
        scene = when (mode) {
            3 -> VerticalReaderScene(width, height, pages)
            4 -> HorizontalReaderScene(height, width, pages, readingDirection = direction)
            else -> {
                val specs = mutableListOf<PagedPageSpec>()
                pages.forEachIndexed { index, (id, hint) ->
                    if (splitWide && !doublePage && hint is PageGeometryHint.Exact && WidePagePolicy.isWidePage(hint)) {
                        val halfHint = PageGeometryHint.Exact((hint.width / 2).coerceAtLeast(1), hint.height)
                        val firstSeg = if (mode == 1) PageSegment.RIGHT_HALF else PageSegment.LEFT_HALF
                        val secondSeg = if (mode == 1) PageSegment.LEFT_HALF else PageSegment.RIGHT_HALF
                        val firstCode = if (firstSeg == PageSegment.LEFT_HALF) 1 else 2
                        val secondCode = if (secondSeg == PageSegment.LEFT_HALF) 1 else 2
                        val secondId = PageId((count + index).toLong())
                        specLookup[id.value] = index to firstCode
                        specLookup[secondId.value] = index to secondCode
                        specs += PagedPageSpec(id, halfHint, 0L, specs.size, segment = firstSeg)
                        specs += PagedPageSpec(secondId, halfHint, 0L, specs.size, segment = secondSeg)
                    } else {
                        specLookup[id.value] = index to 0
                        specs += PagedPageSpec(id, hint, 0L, specs.size)
                    }
                }
                PagedReaderScene(width, height,
                    PagedSpreadConfig(doublePage, cover, direction, zoomMode = when (fit) {
                        1 -> ZoomMode.FIT_WIDTH
                        2 -> ZoomMode.FIT_HEIGHT
                        3 -> ZoomMode.KEEP_START
                        else -> ZoomMode.FIT_CENTER
                    }),
                    specs)
            }
        }
    }

    fun slots(): List<IosReaderSlot> {
        val current = scene ?: return emptyList()
        return if (current is PagedReaderScene) current.allSlots.map { slot ->
            val anchor = specLookup[slot.progressAnchorPageId.value]?.first ?: slot.progressAnchorPageId.value.toInt()
            IosReaderSlot(slot.slotIndex, anchor, slot.placements.map {
                val b = it.boundsInSlot
                val (pageIndex, segment) = specLookup[it.pageId.value] ?: (it.pageId.value.toInt() to 0)
                IosReaderPlacement(pageIndex, b.left, b.top, b.width, b.height, segment)
            })
        } else current.pageGeometries.mapIndexed { index, geometry ->
            val b = geometry.sceneBounds
            IosReaderSlot(index, geometry.pageId.value.toInt(), listOf(
                IosReaderPlacement(index, b.left, b.top, b.width, b.height, 0)))
        }
    }

    fun progress(x: Float, y: Float): IosReaderProgress {
        val frame = scene?.resolve(ReaderViewport(FloatRect.fromLtwh(x, y, width.toFloat(), height.toFloat())))
        val rawId = frame?.progress?.activePageId?.value ?: 0L
        val pageIndex = specLookup[rawId]?.first ?: rawId.toInt()
        return IosReaderProgress(pageIndex, frame?.progress?.intraPageOffsetPx ?: 0f)
    }

    fun decodeSize(width: Int, height: Int): IosReaderSize {
        val resolved = ImageDecodeSize.resolve(IntSize(width, height), MAXIMUM_PIXELS, MAXIMUM_DIMENSION)
        return IosReaderSize(resolved.width, resolved.height)
    }

    fun isTiled(width: Int, height: Int): Boolean =
        width > 4096 || height > 4096 || width.toLong() * height > MAXIMUM_PIXELS

    fun edgeSampleSize(width: Int, height: Int): Int =
        ReaderEdgeDetection.sampleSize(width, height)

    fun contentBounds(
        width: Int,
        height: Int,
        sampleSize: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        pixels: IntArray,
    ): IosReaderRect? {
        if (width <= 0 || height <= 0 || sampleSize <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return null
        if (pixels.size < bitmapWidth * bitmapHeight) return null
        val rect = ReaderEdgeDetection.contentBounds(width, height, sampleSize, bitmapWidth, bitmapHeight) { out, x, y, w, h ->
            for (row in 0 until h) {
                val srcOffset = (y + row) * bitmapWidth + x
                val dstOffset = row * w
                pixels.copyInto(out, destinationOffset = dstOffset, startIndex = srcOffset, endIndex = srcOffset + w)
            }
        } ?: return null
        return IosReaderRect(rect.left, rect.top, rect.width, rect.height)
    }

    fun visibleTiles(
        x: Float,
        y: Float,
        index: Int,
        encodedWidth: Int,
        encodedHeight: Int,
        displayScale: Float,
    ): List<IosReaderTile> {
        if (index !in 0 until count || encodedWidth <= 0 || encodedHeight <= 0) return emptyList()
        val current = scene ?: return emptyList()
        val scale = displayScale.coerceAtLeast(1f)
        val node = if (current is PagedReaderScene) {
            val slot = current.allSlots.firstOrNull { s ->
                s.placements.any { (specLookup[it.pageId.value]?.first ?: it.pageId.value.toInt()) == index }
           }
            slot?.visibleContentNodes(scale, x, y)?.firstOrNull {
                (specLookup[it.pageId.value]?.first ?: it.pageId.value.toInt()) == index
            }
        } else {
            val frame = current.resolve(ReaderViewport(FloatRect.fromLtwh(x, y, width.toFloat(), height.toFloat())))
            frame.visibleNodes.firstOrNull { it.pageId.value.toInt() == index }
        } ?: return emptyList()
        return VisibleImageTiles.resolve(node, IntSize(encodedWidth, encodedHeight), scale).map { spec ->
            IosReaderTile(
                row = spec.key.row,
                col = spec.key.col,
                sampleSize = spec.sampleSize,
                logicalLeft = spec.logicalRect.left,
                logicalTop = spec.logicalRect.top,
                logicalWidth = spec.logicalRect.width,
                logicalHeight = spec.logicalRect.height,
                decodeLeft = spec.decodeRegion.left,
                decodeTop = spec.decodeRegion.top,
                decodeWidth = spec.decodeRegion.width,
                decodeHeight = spec.decodeRegion.height,
            )
        }
    }

    fun clampPan(value: Float, contentMin: Float, contentMax: Float, viewport: Float, scale: Float): Float =
        value.coerceIn(PagedPanBoundsResolver.resolvePanRange(contentMin, contentMax, viewport, scale.coerceAtLeast(1f)))

    fun autoScrollDelay(speed: Float): Long = ReaderAutoScroll.pageSwitchDelayMs(speed)

    fun scrollOrigin(index: Int, offset: Float): Float = scene?.resolveViewportOriginForPage(
        PageId(index.toLong()), if (mode == 3) height.toFloat() else width.toFloat(), offset,
    )?.coerceIn(0f, scrollLimit()) ?: 0f

    fun scrollLimit(): Float = ((scene?.totalSceneExtent ?: 0f) - if (mode == 3) height else width).coerceAtLeast(0f)

    fun autoScrollDistance(speed: Float, elapsedMillis: Int): Float =
        elapsedMillis.coerceAtLeast(0).toFloat() * ReaderAutoScroll.speedMultiplier(speed) / 32f

    companion object {
        const val MAXIMUM_PIXELS = 4L * 1024 * 1024
        const val MAXIMUM_DIMENSION = 16384
    }
}
