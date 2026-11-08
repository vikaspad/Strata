package dev.strata.core

import dev.strata.core.model.ElementType
import dev.strata.core.model.Strategy
import dev.strata.core.model.XpathEntry
import org.jsoup.nodes.Element

/**
 * CLAUDE.md §5.6–5.7. Tries each strategy for the element's type in order; the first whose relative value matches
 * only [el] within the module, and whose absolute form matches only [el] in the snapshot, wins.
 *
 * @param moduleXpath the verified absolute XPath of each module element in this snapshot.
 * @param entryOf the entry already generated for an element, used to locate icons through their button or link.
 */
class DefaultXpathGenerator(
    private val verifier: JsoupXpathVerifier,
    private val entryOf: (Element) -> XpathEntry? = { null },
    private val moduleXpath: (Element) -> String?,
) : XpathGenerator {

    override fun generate(el: Element, module: Element): XpathEntry? = generate(el, module, allowPositional = true)

    /** [allowPositional] is false for content elements that are anchored (R1): POSITIONAL is only for used ones (R2). */
    fun generate(
        el: Element,
        module: Element,
        allowPositional: Boolean,
        type: ElementType = Interactables.typeOf(el) ?: ElementType.OTHER_INTERACTIVE,
    ): XpathEntry? {
        val moduleAbsolute = moduleXpath(module) ?: return null
        val doc = el.ownerDocument() ?: return null
        for ((strategy, value) in candidates(el, module, type, allowPositional)) {
            if (verifier.selectWithin(module, value) != listOf(el)) continue
            val absolute = absoluteOf(moduleAbsolute, value)
            val matches = verifier.select(doc, absolute)
            if (matches != listOf(el)) continue
            return XpathEntry(
                name = elementName(el, type),
                type = type,
                value = value,
                absolute = absolute,
                strategy = strategy,
                matches = matches.size,
            )
        }
        return null
    }

    private fun candidates(
        el: Element,
        module: Element,
        type: ElementType,
        allowPositional: Boolean,
    ): List<Pair<Strategy, String>> {
        val tag = step(el)
        val out = mutableListOf<Pair<Strategy, String>>()
        fun attr(strategy: Strategy, name: String, value: String) {
            out += strategy to ".//$tag[@$name=${xpathLiteral(value)}]"
        }

        for (attr in TEST_ID_ATTRS) {
            el.attr(attr).takeIf { DynamicIds.isStable(it) }?.let { out += Strategy.TESTID to ".//*[@$attr=${xpathLiteral(it)}]" }
        }
        if (DynamicIds.isStable(el.id())) attr(Strategy.ID, "id", el.id())
        el.attr("name").takeIf { DynamicIds.isStable(it) }?.let { attr(Strategy.NAME, "name", it) }
        el.attr("aria-label").takeIf { it.isNotBlank() }?.let { attr(Strategy.ARIA_LABEL, "aria-label", it) }
        if (type in FORM_CONTROLS) labelCandidates(el, tag).forEach { out += Strategy.LABEL_FOR to it }
        for (name in ATTRIBUTES.getOrDefault(type, emptyList())) {
            el.attr(name).takeIf { it.isNotBlank() && !DynamicIds.isDynamic(it) && it.lowercase() !in GENERIC_ROLES }
                ?.let { attr(Strategy.ATTRIBUTE, name, it) }
        }
        if (type == ElementType.TABLE) {
            el.selectFirst("caption")?.let { Texts.usable(it.text()) }?.let {
                out += Strategy.STRUCTURE to ".//$tag[caption[normalize-space()=${xpathLiteral(it)}]]"
            }
        }
        if (type == ElementType.ICON) {
            Interactables.iconClass(el)?.let { out += Strategy.CLASS to ".//$tag[${hasClass(it)}]" }
        }
        if (type !in NO_TEXT) {
            Texts.usable(el.text())?.let { out += Strategy.TEXT to ".//$tag[normalize-space()=${xpathLiteral(it)}]" }
        }
        el.attr("placeholder").takeIf { it.isNotBlank() }?.let { attr(Strategy.PLACEHOLDER, "placeholder", it) }
        if (type == ElementType.ICON) {
            el.parents().firstOrNull { Interactables.isInteractable(it) }?.let(entryOf)?.let { parent ->
                if (parent.value.startsWith(".//")) out += Strategy.STRUCTURE to "${parent.value}//$tag"
            }
        }
        if (allowPositional) {
            val index = module.select(el.normalName()).filter { it !== module }.indexOfFirst { it === el }
            if (index >= 0) out += Strategy.POSITIONAL to "(.//$tag)[${index + 1}]"
        }
        return out
    }

    /** §5.6 step 5: the label's `@for` (works for dynamic ids, which never appear literally), then wrapping, then `following::`. */
    private fun labelCandidates(el: Element, tag: String): List<String> {
        val (label, text) = labelFor(el) ?: return emptyList()
        val literal = xpathLiteral(text)
        return when {
            el.parents().contains(label) -> listOf(".//label[normalize-space()=$literal]//$tag")
            label.attr("for") == el.id() && el.id().isNotBlank() -> listOf(
                ".//$tag[@id=//label[normalize-space()=$literal]/@for]",
                ".//label[normalize-space()=$literal]/following::$tag[1]",
            )
            else -> listOf(".//label[normalize-space()=$literal]/following::$tag[1]")
        }
    }

    private fun elementName(el: Element, type: ElementType): String {
        val iconName = if (type == ElementType.ICON) {
            Interactables.iconClass(el)?.substringAfter('-')
        } else {
            null
        }
        // An icon inside a button or link is named after it: `saveButton` → `saveIcon`.
        val parentName = if (type == ElementType.ICON) {
            el.parents().firstOrNull { Interactables.isInteractable(it) }?.let(entryOf)?.name
                ?.let { name -> SUFFIX.values.fold(name) { n, suffix -> n.removeSuffix(suffix) } }
        } else {
            null
        }
        val source = listOfNotNull(
            el.attr("aria-label").takeIf { it.isNotBlank() },
            if (type in FORM_CONTROLS) labelFor(el)?.second else null,
            if (type !in NO_TEXT) Texts.usable(el.text()) else null,
            el.attr("placeholder").takeIf { it.isNotBlank() },
            el.attr("name").takeIf { DynamicIds.isStable(it) },
            TEST_ID_ATTRS.firstNotNullOfOrNull { attr -> el.attr(attr).takeIf { DynamicIds.isStable(it) } },
            el.id().takeIf { DynamicIds.isStable(it) },
            el.attr("title").takeIf { it.isNotBlank() },
            el.attr("alt").takeIf { it.isNotBlank() },
            el.attr("data-icon").takeIf { it.isNotBlank() },
            iconName,
            parentName,
            el.selectFirst("caption")?.text()?.let { Texts.usable(it) },
            el.attr("role").takeIf { it.isNotBlank() && it.lowercase() !in GENERIC_ROLES },
        ).map { Names.camel(it) }.firstOrNull { it.isNotEmpty() } ?: Names.camel(el.normalName())
        return Names.withSuffix(source, SUFFIX.getValue(type))
    }

    companion object {
        val TEST_ID_ATTRS: List<String> = listOf("data-testid", "data-test", "data-qa", "data-cy")
        private val GENERIC_ROLES = setOf("presentation", "none", "generic")

        private val FORM_CONTROLS = setOf(
            ElementType.TEXT_INPUT, ElementType.PASSWORD, ElementType.TEXTAREA, ElementType.DROPDOWN,
            ElementType.CHECKBOX, ElementType.RADIO,
        )

        /** Types whose own text is redacted or meaningless as a locator. */
        private val NO_TEXT = setOf(
            ElementType.TABLE, ElementType.LIST, ElementType.IFRAME, ElementType.PARAGRAPH, ElementType.SPAN,
            ElementType.DIV, ElementType.TABLE_CELL, ElementType.TABLE_ROW, ElementType.LIST_ITEM,
        )

        /** Extra attribute strategies (§5.6 `ATTRIBUTE`) per type, tried after `aria-label`. */
        private val ATTRIBUTES: Map<ElementType, List<String>> = mapOf(
            ElementType.ICON to listOf("title", "alt", "data-icon", "svgicon", "data-mat-icon-name"),
            ElementType.LABEL to listOf("for"),
            ElementType.IFRAME to listOf("title"),
            ElementType.PARAGRAPH to listOf("role", "title"),
            ElementType.SPAN to listOf("role", "title"),
            ElementType.DIV to listOf("role", "title"),
            ElementType.LIST to listOf("role"),
            ElementType.BUTTON to listOf("title"),
            ElementType.LINK to listOf("title"),
        )

        val SUFFIX: Map<ElementType, String> = mapOf(
            ElementType.BUTTON to "Button",
            ElementType.LINK to "Link",
            ElementType.TEXT_INPUT to "Input",
            ElementType.PASSWORD to "Input",
            ElementType.DROPDOWN to "Dropdown",
            ElementType.OPTION to "Option",
            ElementType.CHECKBOX to "Checkbox",
            ElementType.RADIO to "Radio",
            ElementType.TEXTAREA to "Textarea",
            ElementType.TAB to "Tab",
            ElementType.ICON to "Icon",
            ElementType.TABLE to "Table",
            ElementType.TABLE_ROW to "Row",
            ElementType.TABLE_HEADER to "Header",
            ElementType.TABLE_CELL to "Cell",
            ElementType.HEADING to "Heading",
            ElementType.LABEL to "Label",
            ElementType.PARAGRAPH to "Paragraph",
            ElementType.SPAN to "Span",
            ElementType.DIV to "Div",
            ElementType.LIST to "List",
            ElementType.LIST_ITEM to "Item",
            ElementType.IFRAME to "Frame",
            ElementType.OTHER_INTERACTIVE to "Element",
        )

        /** The XPath step for [el]'s tag. SVG lives in its own namespace in browsers, so it needs `local-name()`. */
        fun step(el: Element): String = if (el.normalName() == "svg") "*[local-name()='svg']" else el.normalName()

        /** `.//x` → `<module>//x`; `./x` → `<module>/x`; `(.//x)[n]` → `(<module>//x)[n]`. */
        fun absoluteOf(moduleXpath: String, value: String): String = when {
            value.startsWith(".//") -> moduleXpath + value.substring(1)
            value.startsWith("./") -> moduleXpath + value.substring(1)
            value.startsWith("(.//") -> "(" + moduleXpath + value.substring(2)
            else -> value
        }

        /** The label pointing at [el] via `for`, or the label wrapping it, with its usable text. */
        fun labelFor(el: Element): Pair<Element, String>? {
            val doc = el.ownerDocument() ?: return null
            val label = el.id().takeIf { it.isNotBlank() }
                ?.let { id -> doc.getElementsByTag("label").firstOrNull { it.attr("for") == id } }
                ?: el.parents().firstOrNull { it.normalName() == "label" }
                ?: return null
            val text = Texts.usable(label.text()) ?: return null
            return label to text
        }
    }
}
