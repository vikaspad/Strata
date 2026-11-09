package dev.strata.core

import dev.strata.core.model.XpathEntry
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Repeated rows collapse into templates (CLAUDE.md §5.13) and large grids verify quickly (§5.9). */
class LargeGridTest {
    private val rows = 1000

    private fun page(html: String): Document = Jsoup.parse("<html><head><title>x</title></head><body><main>$html</main></body></html>")

    private fun entries(a: SnapshotAnalysis): List<XpathEntry> = a.modules.flatMap { it.second }

    private fun ariaGrid(n: Int): String = "<div role='grid' aria-label='Orders'><div role='row'><div role='columnheader'>Id</div></div>" +
        (1..n).joinToString("") { "<div role='row'><div role='gridcell'>···</div><div role='gridcell'><input type='checkbox'><a href='/o'>···</a></div></div>" } + "</div>"

    private fun agGrid(firstRow: Int, n: Int): String = "<div id='g'><div class='ag-root-wrapper'>" +
        (firstRow until firstRow + n).joinToString("") { r -> "<div role='row' row-index='$r'><div role='gridcell' col-id='qty'><button>Cancel</button></div></div>" } + "</div></div>"

    @Test
    fun ariaRowsBecomeTemplatesAndEveryInstanceResolves() {
        val doc = page(ariaGrid(rows))
        val link = doc.select("[role=row] a")[499]
        val a = SnapshotAnalyzer().analyze(doc, setOf(link))
        val all = entries(a)
        assertTrue(all.size < 10, all.map { it.value }.toString())
        val template = all.single { it.used }
        assertEquals(rows, template.instances)
        // Card 1 is the header row, so data row 500 is card 501.
        assertEquals(listOf(link), JsoupXpathVerifier().select(doc, template.absoluteFor(501)))
    }

    @Test
    fun plainRepeatedRowsAndListsWithDifferingTextBecomeTemplates() {
        val divs = (1..rows).joinToString("") { "<div class='row'><a href='/o/$it'>Client$it</a></div>" }
        val list = "<ul id='l'>" + (1..rows).joinToString("") { "<li><a href='/o/$it'>Item$it</a></li>" } + "</ul>"
        for (html in listOf("<div class='grid'>$divs</div>", list)) {
            val all = entries(SnapshotAnalyzer().analyze(page(html)))
            assertTrue(all.size < 6, all.map { it.value }.toString())
            assertTrue(all.none { it.value.contains("Client") || it.value.contains("Item") }, "row text is data")
        }
    }

    @Test
    fun anUnlabelledParentAmongOtherListsStillGivesOneTemplate() {
        val other = "<ul><li><a href='/a'>About</a></li></ul>"
        val records = "<ul>" + (1..rows).joinToString("") { "<li><a href='/o/$it'>Item$it</a></li>" } + "</ul>"
        val doc = page(other + records)
        val all = entries(SnapshotAnalyzer().analyze(doc))
        assertTrue(all.size < 8, all.map { it.value }.toString())
        val link = all.single { it.value.contains("%s") && it.value.endsWith("//a") }
        assertEquals(listOf(doc.select("ul")[1].select("a")[9]), JsoupXpathVerifier().select(doc, link.absoluteFor(10)))
    }

    @Test
    fun aShortMenuKeepsItsNamedEntries() {
        val menu = "<ul id='m'>" + (1..19).joinToString("") { "<li><a href='/s$it'>Section$it</a></li>" } + "</ul>"
        val all = entries(SnapshotAnalyzer().analyze(page(menu)))
        assertTrue(all.any { it.value == ".//a[normalize-space()='Section7']" }, all.map { it.value }.toString())
    }

    @Test
    fun virtualisedGridVerifiesOnlyTheRenderedRows() {
        val doc = page(agGrid(50, 11))
        val row = entries(SnapshotAnalyzer().analyze(doc)).single { it.name == "cancelButton" }
        assertEquals(11, row.instances)
        assertEquals(listOf<Element>(doc.select("button")[0]), JsoupXpathVerifier().select(doc, row.absoluteFor(51)))
    }

    @Test
    fun aRowIndexSharedByTwoRowsIsNotATemplate() {
        val html = "<div id='g'><div class='ag-root-wrapper'>" + listOf(0, 0, 1).joinToString("") {
            "<div role='row' row-index='$it'><div role='gridcell' col-id='qty'>x</div></div>"
        } + "</div></div>"
        assertNull(entries(SnapshotAnalyzer().analyze(page(html))).firstOrNull { it.type == dev.strata.core.model.ElementType.TABLE_ROW })
    }

    @Test
    fun twoBodiesMakeTheRowIndexAmbiguousSoNoRowTemplateIsEmitted() {
        val body = "<tbody><tr><td>···</td></tr></tbody>"
        val all = entries(SnapshotAnalyzer().analyze(page("<table id='t'>$body$body</table>")))
        assertTrue(all.none { it.value.contains("tr[%s]") }, all.map { it.value }.toString())
    }

    @Test
    fun aThousandRowsStayWellWithinABudget() {
        val start = System.nanoTime()
        for (html in listOf(ariaGrid(rows), agGrid(0, rows))) assertNotNull(SnapshotAnalyzer().analyze(page(html)))
        val seconds = (System.nanoTime() - start) / 1_000_000_000
        assertTrue(seconds < 30, "took ${seconds}s")
    }
}
