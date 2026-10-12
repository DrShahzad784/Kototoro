package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderInfoBarLayout

class ReaderInfoBarMeasureTest {

    @Test
    fun `cutout compressed progress is measured once with its final width`() {
        val progress = Child(140)
        measure(ReaderInfoBarLayout.SPLIT, ReaderInfoBarCutout(100, 150), progress)
        assertEquals(1, progress.measurements.size)
        assertEquals(26, progress.measurements.single().maxWidth)
    }

    @Test
    fun `zero available progress width does not trigger a second measure`() {
        val progress = Child(140)
        measure(ReaderInfoBarLayout.SPLIT, ReaderInfoBarCutout(80, 232), progress)
        assertEquals(1, progress.measurements.size)
        assertEquals(0, progress.measurements.single().maxWidth)
    }

    @Test
    fun `both layouts handle progress and system status being absent`() {
        for (layout in ReaderInfoBarLayout.entries) {
            for (hasProgress in listOf(false, true)) {
                for (hasSystem in listOf(false, true)) {
                    val progress = if (hasProgress) Child(30) else null
                    val system = if (hasSystem) Child(40) else null
                    val reading = Child(80)
                    measure(layout, null, progress, system, reading)
                    assertEquals(1, reading.measurements.size)
                    progress?.let { assertEquals(1, it.measurements.size) }
                    system?.let { assertEquals(1, it.measurements.size) }
                }
            }
        }
    }

    private fun measure(
        layout: ReaderInfoBarLayout,
        cutout: ReaderInfoBarCutout?,
        progress: Child?,
        system: Child? = Child(40),
        reading: Child = Child(80),
    ) {
        val scope = mockk<MeasureScope> {
            every { layout(any(), any(), any(), any()) } returns mockk<MeasureResult>()
        }
        val policy = readerInfoBarMeasurePolicy(layout, 8, 8, 0, 8, 4, cutout, progress != null, system != null)
        with(policy) {
            scope.measure(listOfNotNull(reading.measurable, progress?.measurable, system?.measurable),
                Constraints(maxWidth = 240, maxHeight = 30))
        }
    }

    private class Child(private val naturalWidth: Int) {
        val measurements = mutableListOf<Constraints>()
        val measurable = object : Measurable {
            override val parentData: Any? = null
            override fun minIntrinsicWidth(height: Int) = naturalWidth
            override fun maxIntrinsicWidth(height: Int) = naturalWidth
            override fun minIntrinsicHeight(width: Int) = 10
            override fun maxIntrinsicHeight(width: Int) = 10

            override fun measure(constraints: Constraints): Placeable {
                assertTrue(measurements.isEmpty(), "measure() may not be called multiple times on the same Measurable")
                measurements += constraints
                return mockk<Placeable> {
                    every { width } returns naturalWidth.coerceAtMost(constraints.maxWidth)
                    every { height } returns 10
                }
            }
        }
    }
}
