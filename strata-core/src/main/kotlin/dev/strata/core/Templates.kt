package dev.strata.core

import dev.strata.core.model.ElementType
import dev.strata.core.model.Strategy
import dev.strata.core.model.XpathEntry
import org.jsoup.nodes.Element

/** A verified template plus the snapshot elements it stands for (used to mark usage). */
data class Template(val entry: XpathEntry, val instances: List<Element>)

/**
 * Templates for repeated structures (CLAUDE.md §5.9): table rows, cells and row actions (`{row}`), list items (`{n}`)
 * and dropdown options (`{text}`). A template is emitted only if, for every instance in the snapshot, substituting
 * the placeholder selects exactly that instance, both within the module and from the document root.
 */
class TemplateBuilder(private val verifier: JsoupXpathVerifier) {

    /**
     * @param base the table's path relative to [module]: `.` when the table is the module, else the table entry's value.
     */
    fun table(table: Element, module: Element, moduleAbsolute: String, base: String, tableName: String): List<Template> {
        val prefix = if (base == ".") "." else base
        val out = mutableListOf<Template>()
        val headers = table.selectFirst("thead")?.selectFirst("tr")?.children()?.filter { it.normalName() == "th" }.orEmpty()
        val rows = table.selectFirst("tbody")?.children()?.filter { it.normalName() == "tr" }.orEmpty()

        table.selectFirst("thead")?.selectFirst("tr")?.let { headerRow ->
            single(module, moduleAbsolute, "$prefix/thead/tr", headerRow, Strategy.STRUCTURE, ElementType.TABLE_ROW,
                Names.withSuffix("${tableName}Header", "Row"))?.let(out::add)
        }
        headers.forEachIndexed { i, th ->
            val text = Texts.usable(th.text())
            val value = if (text != null) "$prefix/thead/tr/th[normalize-space()=${xpathLiteral(text)}]" else "$prefix/thead/tr/th[${i + 1}]"
            val name = Names.withSuffix(if (text != null) Names.camel(text) else "column${i + 1}", "Header")
            single(module, moduleAbsolute, value, th, if (text != null) Strategy.TEXT else Strategy.STRUCTURE,
                ElementType.TABLE_HEADER, name)?.let(out::add)
        }
        if (rows.isEmpty()) return out

        indexed(module, moduleAbsolute, "$prefix/tbody/tr[{row}]", "{row}", rows.indices.associate { it + 1 to rows[it] },
            ElementType.TABLE_ROW, Names.withSuffix(tableName, "Row"))?.let(out::add)

        val columns = rows.maxOf { row -> row.children().count { it.normalName() == "td" } }
        for (k in 1..columns) {
            val header = headers.getOrNull(k - 1)?.let { Texts.usable(it.text()) }
            val cells = rows.indices.mapNotNull { r -> rows[r].children().filter { it.normalName() == "td" }.getOrNull(k - 1)?.let { r + 1 to it } }.toMap()
            indexed(module, moduleAbsolute, "$prefix/tbody/tr[{row}]/${column(header, k)}", "{row}", cells,
                ElementType.TABLE_CELL, Names.withSuffix(if (header != null) Names.camel(header) else "column$k", "Cell"))
                ?.let(out::add)
        }
        out += rowActions(module, moduleAbsolute, prefix, rows, headers)
        return out
    }

    /** `td[k]`, or the column found through its header text so it survives column reordering. */
    private fun column(header: String?, k: Int): String =
        if (header == null) "td[$k]" else "td[count(ancestor::table[1]/thead/tr/th[normalize-space()=${xpathLiteral(header)}]/preceding-sibling::th)+1]"

    private fun rowActions(
        module: Element,
        moduleAbsolute: String,
        prefix: String,
        rows: List<Element>,
        headers: List<Element>,
    ): List<Template> {
        data class Action(val row: Int, val el: Element, val options: List<Pair<String, String>>)

        val actions = rows.flatMapIndexed { r, row ->
            Interactables.find(row).map { el ->
                val cell = el.parents().first { it.normalName() == "td" || it.normalName() == "th" }
                val k = cell.elementSiblingIndex() + 1
                val header = headers.getOrNull(k - 1)?.let { Texts.usable(it.text()) }
                val tag = DefaultXpathGenerator.step(el)
                val nth = cell.select(el.normalName()).indexOfFirst { it === el } + 1
                val options = listOfNotNull(
                    Texts.usable(el.text())?.let { "//$tag[normalize-space()=${xpathLiteral(it)}]" to it },
                    el.attr("aria-label").takeIf { it.isNotBlank() }?.let { "//$tag[@aria-label=${xpathLiteral(it)}]" to it },
                    el.attr("title").takeIf { it.isNotBlank() }?.let { "//$tag[@title=${xpathLiteral(it)}]" to it },
                    "/${column(header, k)}/descendant::$tag[$nth]" to (header ?: "column $k"),
                )
                Action(r + 1, el, options)
            }
        }

        val out = mutableListOf<Template>()
        var pending = actions.map { it to 0 }
        while (pending.isNotEmpty()) {
            val groups = pending.groupBy { (a, level) -> a.options[level].first }
            val retry = mutableListOf<Pair<Action, Int>>()
            for ((step, members) in groups) {
                val byRow = members.associate { (a, _) -> a.row to a.el }
                val sample = members.first().first
                val type = Interactables.typeOf(sample.el) ?: ElementType.OTHER_INTERACTIVE
                val name = Names.withSuffix(Names.camel(sample.options[members.first().second].second).ifEmpty { "row" },
                    DefaultXpathGenerator.SUFFIX.getValue(type))
                val template = if (byRow.size == members.size) {
                    indexed(module, moduleAbsolute, "$prefix/tbody/tr[{row}]$step", "{row}", byRow, type, name)
                } else {
                    null
                }
                if (template != null) {
                    out += template
                } else {
                    members.filter { (a, level) -> level + 1 < a.options.size }.forEach { (a, level) -> retry += a to level + 1 }
                }
            }
            pending = retry
        }
        return out
    }

    /** `{text}` template for options. [value] contains `[normalize-space()='{text}']`; the rest must select exactly [options]. */
    fun options(module: Element, moduleAbsolute: String, value: String, options: List<Element>, name: String): Template? {
        val structural = value.replace(TEXT_PREDICATE, "")
        if (!sameElements(verifier.selectWithin(module, structural), options)) return null
        val absolute = DefaultXpathGenerator.absoluteOf(moduleAbsolute, value)
        val doc = module.ownerDocument() ?: return null
        if (!sameElements(verifier.select(doc, absolute.replace(TEXT_PREDICATE, "")), options)) return null
        return Template(
            XpathEntry(name, ElementType.OPTION, value, absolute, Strategy.TEXT_TEMPLATE, matches = 1, instances = options.size),
            options,
        )
    }

    /** List items of a list located by [listValue]. */
    fun listItems(module: Element, moduleAbsolute: String, listValue: String, list: Element, name: String): Template? {
        val items = list.children().filter { it.normalName() == "li" || it.attr("role") == "listitem" }
        if (items.isEmpty()) return null
        val step = if (items.all { it.normalName() == "li" }) "li" else "*[@role='listitem']"
        return indexed(module, moduleAbsolute, "$listValue/$step[{n}]", "{n}", items.indices.associate { it + 1 to items[it] },
            ElementType.LIST_ITEM, name)
    }

    private fun indexed(
        module: Element,
        moduleAbsolute: String,
        value: String,
        placeholder: String,
        expected: Map<Int, Element>,
        type: ElementType,
        name: String,
    ): Template? {
        if (expected.isEmpty()) return null
        val doc = module.ownerDocument() ?: return null
        val absolute = DefaultXpathGenerator.absoluteOf(moduleAbsolute, value)
        val last = expected.keys.max()
        for (i in 1..last) {
            val want = expected[i]?.let { listOf(it) } ?: emptyList()
            if (verifier.selectWithin(module, value.replace(placeholder, "$i")) != want) return null
            if (verifier.select(doc, absolute.replace(placeholder, "$i")) != want) return null
        }
        return Template(
            XpathEntry(name, type, value, absolute, Strategy.ROW_TEMPLATE, matches = 1, instances = expected.size),
            expected.values.toList(),
        )
    }

    private fun single(
        module: Element,
        moduleAbsolute: String,
        value: String,
        el: Element,
        strategy: Strategy,
        type: ElementType,
        name: String,
    ): Template? {
        val doc = module.ownerDocument() ?: return null
        if (verifier.selectWithin(module, value) != listOf(el)) return null
        val absolute = DefaultXpathGenerator.absoluteOf(moduleAbsolute, value)
        if (verifier.select(doc, absolute) != listOf(el)) return null
        return Template(XpathEntry(name, type, value, absolute, strategy, matches = 1), listOf(el))
    }

    private fun sameElements(a: List<Element>, b: List<Element>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) -> x === y }

    companion object {
        const val TEXT_PLACEHOLDER: String = "[normalize-space()='{text}']"
        private val TEXT_PREDICATE = Regex(Regex.escape(TEXT_PLACEHOLDER))
    }
}
