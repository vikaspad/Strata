package dev.strata.core

import dev.strata.core.model.ElementType
import dev.strata.core.model.Stability
import dev.strata.core.model.Strategy
import dev.strata.core.model.XpathEntry
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M2 element classes, templates and rules R1–R3 (CLAUDE.md §5.2, §5.9). */
class ComponentRulesTest {

    private fun analyze(body: String, used: (org.jsoup.nodes.Document) -> Set<Element> = { emptySet() }): SnapshotAnalysis {
        val doc = Jsoup.parse("<html><body>$body</body></html>")
        return SnapshotAnalyzer().analyze(doc, used(doc))
    }

    private fun SnapshotAnalysis.entries(): List<XpathEntry> = modules.flatMap { it.second }

    private fun SnapshotAnalysis.entry(name: String): XpathEntry = entries().single { it.name == name }

    @Test
    fun tableTemplatesUseHeaderTextAndVerifyEveryRow() {
        val a = analyze(
            "<table id='t'><thead><tr><th>Name</th><th>Qty</th><th></th></tr></thead><tbody>" +
                "<tr><td>···</td><td>···</td><td><button>Close</button></td></tr>" +
                "<tr><td>···</td><td>···</td><td><button>Close</button></td></tr></tbody></table>",
        )
        val qty = a.entry("qtyCell")
        assertEquals(Strategy.ROW_TEMPLATE, qty.strategy)
        assertEquals(Stability.MEDIUM, qty.stability)
        assertEquals(2, qty.instances)
        assertEquals("//table[@id='t']/tbody/tr[%s]/td[count(ancestor::table[1]/thead/tr/th[normalize-space()='Qty']/preceding-sibling::th)+1]", qty.absolute)
        assertEquals(Stability.LOW, a.entry("column3Cell").stability)
        assertEquals("./tbody/tr[%s]//button[normalize-space()='Close']", a.entry("closeButton").value)
        assertEquals(ElementType.TABLE_HEADER, a.entry("nameHeader").type)
        assertEquals("./tbody/tr[%s]", a.entry("tRow").value)
    }

    @Test
    fun rowActionsWithoutTextFallBackToTheirColumn() {
        val a = analyze(
            "<table id='t'><thead><tr><th>Name</th><th>Actions</th></tr></thead><tbody>" +
                "<tr><td>···</td><td><button>···</button></td></tr></tbody></table>",
        )
        val action = a.entries().single { it.type == ElementType.BUTTON }
        assertTrue(action.value.contains("th[normalize-space()='Actions']"), action.value)
        assertTrue(action.value.endsWith("/descendant::button[1]"), action.value)
        assertEquals(Stability.LOW, action.stability)
    }

    @Test
    fun listItemsAndOptionsAreTemplates() {
        val a = analyze(
            "<ul id='w'><li>···</li><li>···</li></ul>" +
                "<select name='ccy'><option>···</option><option>···</option></select>" +
                "<div role='listbox' aria-label='Side'><div role='option'>···</div><div role='option'>···</div></div>",
        )
        assertEquals(".//ul[@id='w']/li[%s]", a.entry("wItem").value)
        assertEquals(2, a.entry("wItem").instances)
        assertEquals(".//select[@name='ccy']//option[normalize-space()='%s']", a.entry("ccyOption").value)
        assertEquals(Strategy.TEXT_TEMPLATE, a.entry("ccyOption").strategy)
        val custom = a.entries().single { it.type == ElementType.OPTION && it.value.contains("@role='option'") }
        assertEquals("//div[@aria-label='Side']//*[@role='option'][normalize-space()='%s']", custom.absolute)
    }

    @Test
    fun iconsAreLocatedByLabelClassOrParent() {
        val a = analyze(
            "<button aria-label='Save'><svg></svg></button><i class='fa fa-trash fa-lg'></i>" +
                "<img alt='Logo' src='x.png'><mat-icon>home</mat-icon><i class='fa'></i>",
        )
        val icons = a.entries().filter { it.type == ElementType.ICON }.associate { it.name to it.value }
        assertEquals(".//button[@aria-label='Save']//*[local-name()='svg']", icons["saveIcon"])
        assertEquals(".//i[contains(concat(' ', normalize-space(@class), ' '), ' fa-trash ')]", icons["trashIcon"])
        assertEquals(".//img[@alt='Logo']", icons["logoIcon"])
        assertEquals(".//mat-icon[normalize-space()='home']", icons["homeIcon"])
        assertEquals(1, a.omitted[ElementType.ICON])
    }

    @Test
    fun contentIsEmittedOnlyWhenAnchoredOrUsed() {
        val a = analyze(
            "<p id='note'>···</p><p>···</p><span data-testid='ts'>···</span><span>···</span><div role='alert'>···</div><div>···</div>",
        )
        assertEquals(setOf("noteParagraph", "tsSpan", "alertDiv"), a.entries().map { it.name }.toSet())
        assertEquals(mapOf(ElementType.PARAGRAPH to 1, ElementType.SPAN to 1, ElementType.DIV to 1), a.omitted)

        val withUse = analyze("<p>···</p><p>···</p>") { doc -> setOf(doc.select("p")[1]) }
        val p = withUse.entries().single()
        assertEquals("(.//p)[2]", p.value)
        assertTrue(p.used)
        assertEquals(Stability.LOW, p.stability)
        assertEquals(mapOf(ElementType.PARAGRAPH to 1), withUse.omitted)
    }

    @Test
    fun usedTemplateInstancesMarkTheTemplate() {
        val a = analyze("<ol aria-label='Recent'><li>···</li><li>···</li></ol>") { doc -> setOf(doc.select("li")[1]) }
        assertTrue(a.entry("recentItem").used)
        assertFalse(a.entry("recentList").used)
    }

    @Test
    fun headingsAndLabelsByTextButNotDataLikeText() {
        val a = analyze("<h2>Trades</h2><h1>Blotter 48213</h1><label for='q'>Query</label><input id='q'>")
        assertEquals(".//h2[normalize-space()='Trades']", a.entry("tradesHeading").value)
        assertEquals("(.//h1)[1]", a.entries().single { it.type == ElementType.HEADING && it.name != "tradesHeading" }.value)
        assertEquals(".//label[@for='q']", a.entry("queryLabel").value)
        assertNull(Texts.usable("Order 12345"))
    }

    @Test
    fun iframesTablesAndPasswords() {
        val a = analyze(
            "<iframe title='Help' src='/f'></iframe><table><caption>Positions</caption><tbody><tr><td>···</td></tr></tbody></table>" +
                "<input type='password' name='pw'>",
        )
        assertEquals(".//iframe[@title='Help']", a.entries().single { it.type == ElementType.IFRAME }.value)
        assertEquals(".//table[caption[normalize-space()='Positions']]", a.entry("positionsTable").value)
        assertEquals(ElementType.PASSWORD, a.entry("pwInput").type)
        // The caption also makes the table a module, so its templates are relative to it.
        assertEquals("//table[.//caption[normalize-space()='Positions']]/tbody/tr[%s]", a.entry("positionsRow").absolute)
    }

    @Test
    fun tableWithoutModuleXpathHangsTemplatesOffItsEntry() {
        val a = analyze("<table><tbody><tr><td>···</td></tr></tbody></table><table><tbody><tr><td>···</td></tr></tbody></table>")
        val rows = a.entries().filter { it.type == ElementType.TABLE_ROW }.map { it.value }
        assertEquals(listOf("(.//table)[1]/tbody/tr[%s]", "(.//table)[2]/tbody/tr[%s]"), rows)
    }
}
