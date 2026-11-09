package dev.strata.plugin

import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.strata.core.RunSummaries
import dev.strata.core.RunSummary
import dev.strata.core.model.LocatorCoverageRun
import dev.strata.core.percent
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/** Four summary tiles over a per-page table and the list of broken locators. */
class CoveragePanel : JPanel(BorderLayout()) {
    private val pagesTile = Tile("PAGES CAPTURED")
    private val interactableTile = Tile("INTERACTABLE ELEMENTS")
    private val coveredTile = Tile("COVERED BY TESTS")
    private val brokenTile = Tile("BROKEN TEST LOCATORS")

    private val pagesModel = ReadOnlyModel(
        arrayOf("Page", "Route", "Visits", "Interactable", "Covered", "Covered %", "Broken"),
        setOf(2, 3, 4, 5, 6),
    )
    private val brokenModel = ReadOnlyModel(arrayOf("Route", "Locator", "Command", "Reason"), emptySet())

    init {
        brokenTile.toolTipText = "Locators that never found an element in their test session. " +
            "A test that deliberately checks an element is absent also appears here."
        val tiles = JPanel(GridLayout(1, 4, JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(8)
            add(pagesTile)
            add(interactableTile)
            add(coveredTile)
            add(brokenTile)
        }
        val splitter = JBSplitter(false, 0.6f).apply {
            firstComponent = JBScrollPane(table(pagesModel, "Run a test with Locator Coverage to see results"))
            secondComponent = JBScrollPane(table(brokenModel, "No broken locators"))
        }
        add(tiles, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
    }

    private fun table(model: DefaultTableModel, empty: String): JBTable = JBTable(model).apply {
        autoCreateRowSorter = true
        emptyText.text = empty
    }

    fun show(run: LocatorCoverageRun) = show(RunSummaries.of(run))

    private fun show(summary: RunSummary) {
        pagesTile.value = summary.pages.size.toString()
        interactableTile.value = summary.interactable.toString()
        coveredTile.value = "${summary.covered} (${summary.coveredPercent}%)"
        brokenTile.value = summary.broken.size.toString()
        brokenTile.alert = summary.broken.isNotEmpty()

        pagesModel.rowCount = 0
        for (p in summary.pages) {
            pagesModel.addRow(arrayOf<Any>(p.name, p.routeKey, p.visits, p.interactable, p.covered, percent(p.covered, p.interactable), p.broken))
        }
        brokenModel.rowCount = 0
        for (f in summary.broken) {
            brokenModel.addRow(arrayOf<Any>(f.routeKey, f.locator, f.command, f.reason.name))
        }
    }

    internal fun tileValues(): List<String> = listOf(pagesTile, interactableTile, coveredTile, brokenTile).map { it.value }

    internal fun pageRowCount(): Int = pagesModel.rowCount

    internal fun brokenRowCount(): Int = brokenModel.rowCount

    private class ReadOnlyModel(columns: Array<String>, private val numeric: Set<Int>) : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false

        override fun getColumnClass(column: Int): Class<*> =
            if (column in numeric) Integer::class.java else String::class.java
    }

    private class Tile(caption: String) : JPanel() {
        private val number = JBLabel("-").apply { font = JBFont.h1().asBold() }
        private val normalColor = number.foreground

        var value: String
            get() = number.text
            set(text) {
                number.text = text
            }

        var alert: Boolean = false
            set(on) {
                field = on
                number.foreground = if (on) JBColor.namedColor("Label.errorForeground", JBColor.RED) else normalColor
            }

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(8, 12))
            add(number)
            add(JBLabel(caption).apply { font = JBFont.small() })
        }
    }
}
