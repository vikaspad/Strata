package dev.strata.core

import dev.strata.core.model.ElementType
import dev.strata.core.model.XpathEntry
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.IdentityHashMap

/** One snapshot's verified model. [covered] maps every element that has an entry (template instances included). */
class SnapshotAnalysis(
    val modules: List<Pair<ResolvedModule, List<XpathEntry>>>,
    val omitted: Map<ElementType, Int>,
    val covered: Map<Element, XpathEntry>,
    val unaddressable: List<Element>,
)

/**
 * Builds the page/module/xpath model of one snapshot document (CLAUDE.md §5). [used] are the elements the test
 * looked up or interacted with in this snapshot; they are always marked, and emitted even when not anchored (R2).
 */
class SnapshotAnalyzer(private val log: (String) -> Unit = {}) {
    private val verifier = JsoupXpathVerifier()
    private val templates = TemplateBuilder(verifier)

    fun analyze(doc: Document, used: Set<Element> = emptySet()): SnapshotAnalysis {
        val usedSet = identitySet(used)
        val body = doc.body()
        val order = IdentityHashMap<Element, Int>()
        body.select("*").forEachIndexed { i, el -> order[el] = i }

        val resolved = IdentityHashMap<Element, ResolvedModule>()
        val moduleOrder = mutableListOf<ResolvedModule>()
        val moduleXpaths = ModuleXpaths(verifier)
        for ((el, kind) in DefaultModuleDetector.detect(doc)) {
            val module = moduleXpaths.resolve(doc, el, kind)
            if (module == null) {
                log("merged module ${outline(el)} into parent: no unique module xpath")
            } else {
                resolved[el] = module
                moduleOrder += module
            }
        }
        fun moduleOf(el: Element): Element = el.parents().firstOrNull { resolved.containsKey(it) } ?: body

        val covered = IdentityHashMap<Element, XpathEntry>()
        val entries = IdentityHashMap<Element, MutableList<Pair<Int, XpathEntry>>>()
        val omitted = sortedMapOf<ElementType, Int>()
        fun add(module: Element, entry: XpathEntry, instances: List<Element>) {
            // The first, most specific entry wins: a menu item's own entry over its list-item template.
            instances.forEach { covered.putIfAbsent(it, entry) }
            val position = instances.minOf { order[it] ?: Int.MAX_VALUE }
            entries.getOrPut(module) { mutableListOf() } += position to entry
        }

        val generator = DefaultXpathGenerator(verifier, { covered[it] }) { resolved[it]?.xpath }

        // Repeated cards (§5.13) first, so their controls get one template each instead of a positional entry per card.
        fun eligible(el: Element): ElementType? {
            val type = Interactables.typeOf(el) ?: return null
            if (Interactables.isHidden(el) || insideSvg(el) || AgGrid.handledByAdapter(el) || resolved.containsKey(el)) return null
            return if (handledByTemplate(el, type) && type != ElementType.LIST_ITEM) null else type
        }
        val cardTemplates = CardTemplates(verifier)
        val cards = cardTemplates.build(body, ::moduleOf, { resolved[it]?.xpath }, { resolved.containsKey(it) },
            ::eligible) { usedSet.containsKey(it) }
        for ((module, t) in cards) add(module, t.entry, t.instances)

        for (el in body.select("*").drop(1)) {
            if (covered.containsKey(el)) continue
            val type = Interactables.typeOf(el) ?: continue
            if (Interactables.isHidden(el) || insideSvg(el) || handledByTemplate(el, type)) continue
            if (AgGrid.handledByAdapter(el)) continue
            // A container div/span/p is already addressed by its module xpath.
            if (resolved.containsKey(el) && Interactables.isContentTag(el)) continue
            val module = moduleOf(el)
            if (Interactables.isContent(type)) {
                if (!usedSet.containsKey(el) && cardTemplates.inBulkCard(el)) {
                    omitted.merge(type, 1, Int::plus)
                    continue
                }
                val entry = generator.generate(el, module, allowPositional = false, type = type)
                    ?: if (usedSet.contains(el)) generator.generate(el, module, allowPositional = true, type = type) else null
                if (entry == null) {
                    if (!usedSet.contains(el)) omitted.merge(type, 1, Int::plus)
                } else {
                    add(module, entry, listOf(el))
                }
                continue
            }
            val entry = generator.generate(el, module, allowPositional = true, type = type)
            if (entry == null) {
                log("dropped ${outline(el)} in ${resolved[module]?.xpath}: no strategy matched exactly 1")
            } else {
                add(module, entry, listOf(el))
            }
        }

        // AG Grid (§5.12): headers, rows, cells and cell controls of each grid module, plus a TABLE entry outside it.
        val gridTemplates = AgGridTemplates(verifier)
        for (host in AgGrid.hosts(doc)) {
            val gridModule = resolved[host]
            if (gridModule == null || gridModule.kind != dev.strata.core.model.ModuleKind.GRID) {
                log("AG Grid ${outline(host)} has no unique module xpath; its cells get no templates")
                continue
            }
            gridTemplates.tableEntry(generator, host, moduleOf(host))?.let { add(moduleOf(host), it, listOf(host)) }
            for (t in gridTemplates.build(host, gridModule.xpath)) add(host, t.entry, t.instances)
        }

        // Structures whose parts are templates.
        for (table in body.select("table")) {
            if (Interactables.isHidden(table)) continue
            val asModule = resolved[table]
            val module = if (asModule != null) table else moduleOf(table)
            val base = if (asModule != null) "." else covered[table]?.takeUnless { it.isTemplate }?.value ?: continue
            val moduleAbsolute = resolved[module]?.xpath ?: continue
            val tableName = covered[table]?.name?.removeSuffix("Table")?.ifEmpty { null } ?: "table"
            for (t in templates.table(table, module, moduleAbsolute, base, tableName)) add(module, t.entry, t.instances)
        }
        for (list in body.select("ul, ol, [role=list]")) {
            val listEntry = covered[list]?.takeUnless { it.isTemplate } ?: continue
            val module = moduleOf(list)
            val moduleAbsolute = resolved[module]?.xpath ?: continue
            val name = Names.withSuffix(listEntry.name.removeSuffix("List").ifEmpty { "list" }, "Item")
            templates.listItems(module, moduleAbsolute, listEntry.value, list, name)?.let { add(module, it.entry, it.instances) }
        }
        for (select in body.select("select")) {
            val selectEntry = covered[select]?.takeUnless { it.isTemplate } ?: continue
            val options = select.select("option")
            if (options.isEmpty() || !selectEntry.value.startsWith(".//")) continue
            val module = moduleOf(select)
            val moduleAbsolute = resolved[module]?.xpath ?: continue
            val name = Names.withSuffix(selectEntry.name.removeSuffix("Dropdown"), "Option")
            templates.options(module, moduleAbsolute, "${selectEntry.value}//option${TemplateBuilder.TEXT_PLACEHOLDER}", options, name)
                ?.let { add(module, it.entry, it.instances) }
        }
        val customOptions = body.select("[role=option], mat-option").filterNot { it.normalName() == "option" || Interactables.isHidden(it) }
        for ((module, group) in customOptions.groupBy { IdentityKey(moduleOf(it)) }) {
            val moduleAbsolute = resolved[module.el]?.xpath ?: continue
            for ((step, options) in group.groupBy { optionStep(it) }) {
                val name = Names.withSuffix(resolved[module.el]?.name?.let { Names.camel(it).removeSuffix("Menu") } ?: "option", "Option")
                templates.options(module.el, moduleAbsolute, ".//$step${TemplateBuilder.TEXT_PLACEHOLDER}", options, name)
                    ?.let { add(module.el, it.entry, it.instances) }
            }
        }

        // R2: anything used but still not covered gets a last-resort entry.
        val unaddressable = mutableListOf<Element>()
        for (el in usedSet.keys) {
            if (covered.containsKey(el)) continue
            val module = moduleOf(el)
            val entry = if (el === body || el === doc.selectFirst("html")) {
                null
            } else {
                generator.generate(el, module, allowPositional = true, type = Interactables.typeOf(el) ?: ElementType.OTHER_INTERACTIVE)
            }
            if (entry == null) unaddressable += el else add(module, entry, listOf(el))
        }

        val usedEntries = identitySet(usedSet.keys.mapNotNull { covered[it] })
        val modules = moduleOrder.mapNotNull { m ->
            entries[m.element]?.sortedBy { it.first }?.map { (_, e) -> if (usedEntries.containsKey(e)) e.copy(used = true) else e }
                ?.let { m to it }
        }
        val markedCovered = IdentityHashMap<Element, XpathEntry>()
        covered.forEach { (el, e) -> markedCovered[el] = if (usedEntries.containsKey(e)) e.copy(used = true) else e }
        return SnapshotAnalysis(modules, omitted, markedCovered, unaddressable)
    }

    private fun handledByTemplate(el: Element, type: ElementType): Boolean = when {
        type == ElementType.TABLE_ROW || type == ElementType.TABLE_HEADER || type == ElementType.TABLE_CELL -> true
        type == ElementType.LIST_ITEM -> true
        el.normalName() == "option" || el.normalName() == "mat-option" || el.attr("role") == "option" -> true
        // Everything inside a data row is either a row template or data.
        el.parents().any { it.normalName() == "tbody" } -> true
        else -> false
    }

    private fun optionStep(el: Element): String =
        if (el.normalName() == "div" || el.normalName() == "li" || el.normalName() == "span") "*[@role='option']" else el.normalName()

    private fun insideSvg(el: Element): Boolean = el.parents().any { it.normalName() == "svg" }

    private class IdentityKey(val el: Element) {
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.el === el
        override fun hashCode(): Int = System.identityHashCode(el)
    }

    private fun <T> identitySet(items: Collection<T>): IdentityHashMap<T, Unit> =
        IdentityHashMap<T, Unit>().apply { items.forEach { put(it, Unit) } }

    companion object {
        fun outline(el: Element): String {
            val attrs = el.attributes().joinToString(" ") { "${it.key}=\"${it.value}\"" }
            return "<${el.normalName()}${if (attrs.isEmpty()) "" else " $attrs"}>".take(160)
        }
    }
}
