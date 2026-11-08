package dev.strata.core

import dev.strata.core.model.ElementType
import dev.strata.core.model.Strategy
import dev.strata.core.model.XpathEntry
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.WeakHashMap

/**
 * AG Grid adapter (CLAUDE.md §5.12). AG Grid renders a deep tree of library elements around the data; the stable
 * hooks are `col-id` (the column's field or colId) on headers and cells and `row-index` (display position) on
 * rows. `row-id` is the application's own row key, so it is data and is stripped by redaction (§4.4).
 *
 * The grid's host is the element the application passed to `createGrid`: the nearest ancestor of
 * `.ag-root-wrapper` without an `ag-` class. It becomes a GRID module.
 */
object AgGrid {
    const val ROOT_CLASS: String = "ag-root-wrapper"
    private val hostsByDoc = WeakHashMap<Document, Set<Element>>()

    /** Grid hosts in [doc], cached per document. */
    fun hosts(doc: Document): Set<Element> = synchronized(hostsByDoc) {
        hostsByDoc.getOrPut(doc) {
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Element, Boolean>()).apply {
                doc.select(".$ROOT_CLASS").mapNotNullTo(this) { host(it) }
            }
        }
    }

    fun host(wrapper: Element): Element? = wrapper.parents().firstOrNull { p -> p.classNames().none { it.startsWith("ag-") } }

    fun isHost(el: Element): Boolean = el.ownerDocument()?.let { hosts(it).contains(el) } ?: false

    /** The host of the grid [el] belongs to, if it is inside one. */
    fun hostOf(el: Element): Element? {
        val doc = el.ownerDocument() ?: return null
        val hosts = hosts(doc)
        if (hosts.isEmpty()) return null
        return el.parents().firstOrNull { hosts.contains(it) }
    }

    /**
     * Inside a grid, only the application's own controls outside rows and header rows (e.g. a pagination button)
     * are handled by the generic rules. Everything else is either a template (§5.12) or library chrome.
     */
    fun handledByAdapter(el: Element): Boolean {
        if (hostOf(el) == null) return false
        if (!Interactables.isInteractable(el)) return true
        val role = el.attr("role").lowercase()
        return role in INTERNAL_ROLES || el.hasClass("ag-tab-guard") ||
            el.parents().any { it.attr("role").lowercase() == "row" || it.attr("role").lowercase() == "columnheader" }
    }

    private val INTERNAL_ROLES = setOf("presentation", "none", "grid", "treegrid", "rowgroup", "row", "gridcell", "columnheader")

    /** A readable name source for a column: AG Grid's built-in columns get a plain name, others their col-id. */
    fun columnName(colId: String): String = when (colId) {
        "ag-Grid-SelectionColumn" -> "selection"
        "ag-Grid-AutoColumn" -> "group"
        else -> colId
    }

    /** Module XPath fallback for a host without its own anchors: the nearest non-`ag-` ancestor of the one grid. */
    fun fallbackModuleXpath(): String =
        "//div[${hasClass(ROOT_CLASS)}]/ancestor::div[not(contains(@class, 'ag-'))][1]"
}

/** Builds the GRID module's entries: headers, header controls, rows, cells and per-column cell controls. */
class AgGridTemplates(private val verifier: JsoupXpathVerifier) {
    private val builder = TemplateBuilder(verifier)

    fun build(host: Element, moduleAbsolute: String): List<Template> {
        val out = mutableListOf<Template>()
        val headers = host.select("[role=columnheader][col-id]")
        for (header in headers) {
            val colId = header.attr("col-id")
            val text = header.selectFirst(".ag-header-cell-text")?.let { Texts.usable(it.text()) }
            val label = text ?: AgGrid.columnName(colId)
            val name = Names.withSuffix(Names.camel(label).ifEmpty { "column" }, "Header")
            builder.single(host, moduleAbsolute, ".//div[@role='columnheader'][@col-id=${xpathLiteral(colId)}]", header,
                Strategy.ATTRIBUTE, ElementType.TABLE_HEADER, name)?.let(out::add)
            for (control in Interactables.find(header)) {
                val step = controlStep(control, header) ?: continue
                val type = Interactables.typeOf(control) ?: ElementType.OTHER_INTERACTIVE
                val controlName = Names.withSuffix(Names.camel(label), DefaultXpathGenerator.SUFFIX.getValue(type))
                builder.single(host, moduleAbsolute, ".//div[@role='columnheader'][@col-id=${xpathLiteral(colId)}]$step",
                    control, Strategy.ATTRIBUTE, type, controlName)?.let(out::add)
            }
        }
        val headerText = headers.associate { h ->
            h.attr("col-id") to (h.selectFirst(".ag-header-cell-text")?.let { Texts.usable(it.text()) } ?: AgGrid.columnName(h.attr("col-id")))
        }

        // Data rows: numeric row-index (pinned rows use "t-0"/"b-0" and are left out). 1-based {row} = row-index + 1.
        val rows = host.select("[role=row][row-index]").filter { it.attr("row-index").toIntOrNull() != null }
        val byIndex = rows.groupBy { it.attr("row-index").toInt() + 1 }
        val rowStep = ".//div[@role='row'][@row-index={row}-1]"
        if (byIndex.values.all { it.size == 1 }) {
            builder.indexed(host, moduleAbsolute, rowStep, "{row}", byIndex.mapValues { it.value.single() },
                ElementType.TABLE_ROW, Names.withSuffix(gridName(host), "Row"))?.let(out::add)
        }

        val cells = rows.flatMap { row -> row.select("[role=gridcell][col-id]").map { row to it } }
        for ((colId, inColumn) in cells.groupBy { it.second.attr("col-id") }) {
            val cellStep = "$rowStep//div[@role='gridcell'][@col-id=${xpathLiteral(colId)}]"
            val expected = inColumn.associate { (row, cell) -> row.attr("row-index").toInt() + 1 to cell }
            if (expected.size != inColumn.size) continue
            val label = headerText[colId] ?: AgGrid.columnName(colId)
            builder.indexed(host, moduleAbsolute, cellStep, "{row}", expected, ElementType.TABLE_CELL,
                Names.withSuffix(Names.camel(label).ifEmpty { "column" }, "Cell"))?.let(out::add)

            // Controls rendered in this column's cells, one template per kind of control.
            val controls = inColumn.flatMap { (row, cell) -> Interactables.find(cell).map { Triple(row, cell, it) } }
            for ((step, group) in controls.groupBy { (_, cell, control) -> controlStep(control, cell) }) {
                if (step == null) continue
                val byRow = group.associate { (row, _, control) -> row.attr("row-index").toInt() + 1 to control }
                if (byRow.size != group.size) continue
                val sample = group.first().third
                val type = Interactables.typeOf(sample) ?: ElementType.OTHER_INTERACTIVE
                val source = Texts.usable(sample.text()) ?: label
                builder.indexed(host, moduleAbsolute, "$cellStep$step", "{row}", byRow, type,
                    Names.withSuffix(Names.camel(source).ifEmpty { "cell" }, DefaultXpathGenerator.SUFFIX.getValue(type)))
                    ?.let(out::add)
            }
        }
        return out
    }

    /** The step from a cell (or header) to [control]: by text, then by input type, then by tag and position. */
    private fun controlStep(control: Element, container: Element): String? {
        val tag = DefaultXpathGenerator.step(control)
        Texts.usable(control.text())?.let { return "//$tag[normalize-space()=${xpathLiteral(it)}]" }
        val type = control.attr("type").lowercase()
        val sameKind = container.select(control.normalName()).filter { type.isEmpty() || it.attr("type").lowercase() == type }
        val base = if (control.normalName() == "input" && type.isNotEmpty()) "input[@type=${xpathLiteral(type)}]" else tag
        val nth = sameKind.indexOfFirst { it === control } + 1
        if (nth == 0) return null
        return if (sameKind.size == 1) "//$base" else "/descendant::$base[$nth]"
    }

    private fun gridName(host: Element): String = listOfNotNull(
        DefaultModuleDetector.stableTestId(host),
        host.id().takeIf { DynamicIds.isStable(it) },
        host.attr("aria-label").takeIf { it.isNotBlank() },
    ).map { Names.camel(it) }.firstOrNull { it.isNotEmpty() }?.removeSuffix("Grid") ?: "grid"

    /** Grid hosts are also emitted as a TABLE entry in their parent module, so "tables" covers grids too. */
    fun tableEntry(generator: DefaultXpathGenerator, host: Element, parentModule: Element): XpathEntry? =
        generator.generate(host, parentModule, allowPositional = true, type = ElementType.TABLE)
}
