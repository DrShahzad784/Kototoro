package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.source.*
import kotlin.test.*

class IosSourceFiltersTest {
    private val source = SourceRef("MIHON_1", "zh", "MANGA")

    private fun node(path: List<Int>, kind: SourceFilterKind, name: String, state: SourceFilterValue? = null,
                     values: List<String> = emptyList(), children: List<SourceFilterNode> = emptyList()) =
        SourceFilterNode(MihonFilterRules.nodeId(path, kind, name), name, kind, state, values, children)

    private val nodes = listOf(
        node(listOf(0), SourceFilterKind.HEADER, "General"),
        node(listOf(1), SourceFilterKind.CHECKBOX, "Flag", SourceFilterValue.Toggle(true)),
        node(listOf(2), SourceFilterKind.SELECT, "Choice", SourceFilterValue.Choice(0), listOf("A", "B")),
        node(listOf(3), SourceFilterKind.SORT, "Order", SourceFilterValue.Sort(0, false), listOf("Name", "Date")),
        node(listOf(4), SourceFilterKind.TEXT, "Author", SourceFilterValue.Text("")),
        node(listOf(5), SourceFilterKind.GROUP, "Genres", children = listOf(
            node(listOf(5, 0), SourceFilterKind.TRISTATE, "Action", SourceFilterValue.TriState(SourceTriState.IGNORE)),
        )),
    )

    private fun panel(capabilities: SourceFilterCapabilities = SourceFilterCapabilities(true, true, true, true,
        false, false, false, false), dynamic: Boolean = true): IosFilterPanel {
        val descriptor = SourceDescriptor(source, setOf("POPULARITY"), "POPULARITY", capabilities,
            SourcePagingMode.PAGE_INDEX, isDynamicFilteringSupported = dynamic)
        return IosFilterPanel(descriptor, MihonFilterRules.options(nodes, source),
            dynamic = if (dynamic) SourceDynamicFilters(source, nodes) else null)
    }

    private fun selection(dynamic: List<IosFilterChange> = emptyList(), yearFrom: Int = 0, yearTo: Int = 0,
                          originalLocale: String? = null, author: String? = null) =
        IosFilterSelection(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null,
            originalLocale, 0, yearFrom, yearTo, author, dynamic)

    @Test
    fun extensionControlsBecomeTypedNativeChanges() {
        val panel = panel()
        assertTrue(panel.isDynamic)
        // The quick bar only offers real genres, never select/sort/text projections.
        assertEquals(listOf("top:Flag", "Genres/Action"), panel.quickTags.map { it.key })
        val action = assertNotNull(panel.control("Genres/Action"))
        assertEquals("tristate", action.kind)
        val flag = panel.controls[1]
        assertEquals(IosFilterValue(true, 0, 0, false, ""), flag.initial)
        val choice = panel.controls[2]
        val sort = panel.controls[3]
        val text = panel.controls[4]
        val filter = assertNotNull(selection(listOf(
            IosFilterChange(flag.id, IosFilterValue(false, 0, 0, false, "")),
            IosFilterChange(choice.id, IosFilterValue(false, 0, 1, false, "")),
            IosFilterChange(sort.id, IosFilterValue(false, 0, 1, true, "")),
            IosFilterChange(text.id, IosFilterValue(false, 0, 0, false, "作者")),
            IosFilterChange(action.id, IosFilterValue(false, 2, 0, false, "")),
        )).toFilter(panel, "q"))
        assertEquals("q", filter.query)
        assertEquals(listOf(
            SourceFilterValue.Toggle(false), SourceFilterValue.Choice(1), SourceFilterValue.Sort(1, true),
            SourceFilterValue.Text("作者"), SourceFilterValue.TriState(SourceTriState.EXCLUDE),
        ), filter.dynamicFilters.map { it.value })
        // The host validates the same way before touching native state.
        MihonFilterRules.changes(nodes, emptySet(), emptySet(), filter.dynamicFilters, strict = true, applyLegacy = false)
        // A cleared sort is the protocol's null; out-of-range indexes and unknown ids are dropped.
        val cleared = assertNotNull(selection(listOf(
            IosFilterChange(sort.id, IosFilterValue(false, 0, -1, false, "")),
            IosFilterChange(choice.id, IosFilterValue(false, 0, 9, false, "")),
            IosFilterChange("missing", IosFilterValue(true, 0, 0, false, "")),
        )).toFilter(panel, null))
        assertEquals(listOf(SourceFilterChange(sort.id, null)), cleared.dynamicFilters)
    }

    @Test
    fun parserCapabilitiesExposeYearRangeOriginalLocaleAndAuthor() {
        val panel = panel(SourceFilterCapabilities(true, true, true, true, false, true, true, true), dynamic = false)
        assertFalse(panel.isDynamic)
        assertTrue(panel.yearRange && panel.originalLocale && panel.author)
        val filter = assertNotNull(selection(yearFrom = 1990, yearTo = 2000, originalLocale = "ja", author = "  某人 ")
            .toFilter(panel, null))
        assertEquals(1990, filter.yearFrom)
        assertEquals(2000, filter.yearTo)
        assertEquals("ja", filter.originalLocale)
        assertEquals("某人", filter.author)
        assertNull(selection(author = "  ").toFilter(panel, null))
    }
}
