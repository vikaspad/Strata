package dev.strata.core

import dev.strata.core.model.ElementType
import dev.strata.core.model.ModuleKind
import dev.strata.core.model.Stability
import dev.strata.core.model.Strategy
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AG Grid adapter (CLAUDE.md §5.12), on markup shaped like AG Grid 36's redacted DOM. */
class AgGridTest {

    private fun header(colId: String, text: String = "", extra: String = "") =
        "<div class='ag-header-cell' role='columnheader' col-id='$colId' tabindex='-1'>$extra" +
            "<span class='ag-header-cell-text'>$text</span></div>"

    private fun cell(colId: String, inner: String = "···") =
        "<div role='gridcell' col-id='$colId' class='ag-cell' tabindex='-1'>$inner</div>"

    private fun row(index: Int, cells: String) = "<div role='row' row-index='$index' class='ag-row'>$cells</div>"

    private fun grid(host: String, body: String, headers: String) =
        "$host<div class='ag-styled-root'><div class='ag-root-wrapper' role='presentation'>" +
            "<div class='ag-tab-guard' tabindex='0'></div>" +
            "<div class='ag-grid-viewport' role='grid'><div role='rowgroup'>" +
            "<div class='ag-header-row' role='row' aria-rowindex='1' tabindex='0'>$headers</div>$body</div></div>" +
            "<div class='ag-paging-panel'><button aria-label='Next Page'>›</button></div></div></div></div>"

    private val orders = grid(
        "<div data-testid='orders-grid'>",
        (0..2).joinToString("") { i ->
            row(i, cell("ag-Grid-SelectionColumn", "<input type='checkbox' id='ag-${40 + i}-input' aria-label='Press Space'>") +
                cell("orderId") + cell("notional") + cell("actions", "<button>Cancel</button>"))
        },
        header("ag-Grid-SelectionColumn", extra = "<input type='checkbox' id='ag-22-input' aria-label='Select all'>") +
            header("orderId", "Order") + header("notional", "Notional") + header("actions", "Actions"),
    )

    private fun analyze(html: String) = SnapshotAnalyzer().analyze(Jsoup.parse("<html><body><main>$html</main></body></html>"))

    @Test
    fun gridBecomesAModuleWithColumnKeyedTemplates() {
        val a = analyze(orders)
        val (module, entries) = a.modules.single { it.first.kind == ModuleKind.GRID }
        assertEquals("//div[@data-testid='orders-grid']", module.xpath)
        assertEquals("OrdersGrid", module.name)
        val byName = entries.associateBy { it.name }
        assertEquals(".//div[@role='columnheader'][@col-id='notional']", byName.getValue("notionalHeader").value)
        assertEquals(Strategy.ATTRIBUTE, byName.getValue("notionalHeader").strategy)
        assertEquals(".//div[@role='row'][@row-index=%s-1]", byName.getValue("ordersRow").value)
        assertEquals(3, byName.getValue("ordersRow").instances)
        assertEquals(".//div[@role='row'][@row-index=%s-1]//div[@role='gridcell'][@col-id='notional']",
            byName.getValue("notionalCell").value)
        assertEquals(".//div[@role='row'][@row-index=%s-1]//div[@role='gridcell'][@col-id='actions']//button[normalize-space()='Cancel']",
            byName.getValue("cancelButton").value)
        // Names are deduplicated when visits merge; per snapshot both checkboxes are `selectionCheckbox`.
        val checkboxes = entries.filter { it.name == "selectionCheckbox" }.map { it.value }
        assertEquals(
            listOf(
                ".//div[@role='columnheader'][@col-id='ag-Grid-SelectionColumn']//input[@type='checkbox']",
                ".//div[@role='row'][@row-index=%s-1]//div[@role='gridcell'][@col-id='ag-Grid-SelectionColumn']//input[@type='checkbox']",
            ),
            checkboxes,
        )
        assertTrue(entries.all { it.stability == Stability.MEDIUM }, entries.filter { it.stability != Stability.MEDIUM }.toString())
    }

    @Test
    fun appControlsOutsideRowsUseTheGenericRulesAndInternalsAreSilent() {
        val a = analyze(orders)
        val entries = a.modules.flatMap { it.second }
        assertNotNull(entries.singleOrNull { it.name == "nextPageButton" })
        assertTrue(entries.none { it.absolute.contains("ag-tab-guard") || it.type == ElementType.OTHER_INTERACTIVE })
        assertTrue(entries.none { Regex("ag-\\d+-").containsMatchIn(it.absolute) })
        assertEquals(emptyMap(), a.omitted)
        // The grid also counts as a TABLE in the page.
        assertEquals("//main//*[@data-testid='orders-grid']", entries.single { it.type == ElementType.TABLE }.absolute)
    }

    @Test
    fun pinnedColumnsSplitRowsSoOnlyCellsAreTemplated() {
        val pinned = grid(
            "<div id='blotter'>",
            "<div class='ag-pinned-left-cols-container' role='presentation'>" + row(0, cell("orderId")) + row(1, cell("orderId")) + "</div>" +
                "<div class='ag-center-cols-container' role='presentation'>" + row(0, cell("notional")) + row(1, cell("notional")) + "</div>",
            header("orderId", "Order") + header("notional", "Notional"),
        )
        val entries = analyze(pinned).modules.single { it.first.kind == ModuleKind.GRID }.second
        assertNull(entries.singleOrNull { it.type == ElementType.TABLE_ROW })
        assertEquals(2, entries.single { it.name == "notionalCell" }.instances)
        assertEquals(2, entries.single { it.name == "orderCell" }.instances)
    }

    @Test
    fun hostWithoutAnchorsFallsBackToTheRootWrapper() {
        val bare = grid("<div class='panel'>", row(0, cell("a")), header("a", "A"))
        val module = analyze(bare).modules.single { it.first.kind == ModuleKind.GRID }.first
        assertEquals("//div[contains(concat(' ', normalize-space(@class), ' '), ' ag-root-wrapper ')]/ancestor::div[not(contains(@class, 'ag-'))][1]",
            module.xpath)
    }

    @Test
    fun twoGridsAreSeparateModules() {
        val two = grid("<div id='buys'>", row(0, cell("a")), header("a", "A")) +
            grid("<div id='sells'>", row(0, cell("a")), header("a", "A"))
        val grids = analyze(two).modules.filter { it.first.kind == ModuleKind.GRID }
        assertEquals(listOf("//div[@id='buys']", "//div[@id='sells']"), grids.map { it.first.xpath })
    }

    @Test
    fun agGridGeneratedIdsAreDynamic() {
        assertTrue(DynamicIds.isDynamic("ag-22-input"))
        assertTrue(DynamicIds.isDynamic("ag-5-label"))
        assertFalse(DynamicIds.isDynamic("ag-grid-host"))
    }
}
