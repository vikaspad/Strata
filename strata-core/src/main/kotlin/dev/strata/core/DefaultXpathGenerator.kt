package dev.strata.core

import dev.strata.core.model.ElementType
import dev.strata.core.model.Strategy
import dev.strata.core.model.XpathEntry
import org.jsoup.nodes.Element

/**
 * CLAUDE.md §5.6–5.7. Tries each strategy in order; the first whose relative value matches only [el] within
 * the module, and whose absolute form matches only [el] in the snapshot, wins.
 *
 * @param moduleXpath the verified absolute XPath of each module element in this snapshot.
 */
class DefaultXpathGenerator(
    private val verifier: JsoupXpathVerifier,
    private val moduleXpath: (Element) -> String?,
) : XpathGenerator {

    override fun generate(el: Element, module: Element): XpathEntry? {
        val moduleAbsolute = moduleXpath(module) ?: return null
        val doc = el.ownerDocument() ?: return null
        val type = Interactables.typeOf(el)
        for ((strategy, value) in candidates(el, module, type)) {
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

    private fun candidates(el: Element, module: Element, type: ElementType): List<Pair<Strategy, String>> {
        val tag = el.normalName()
        val out = mutableListOf<Pair<Strategy, String>>()

        for (attr in TEST_ID_ATTRS) {
            el.attr(attr).takeIf { DynamicIds.isStable(it) }?.let {
                out += Strategy.TESTID to ".//*[@$attr=${xpathLiteral(it)}]"
            }
        }
        if (DynamicIds.isStable(el.id())) out += Strategy.ID to ".//$tag[@id=${xpathLiteral(el.id())}]"
        el.attr("name").takeIf { DynamicIds.isStable(it) }?.let {
            out += Strategy.NAME to ".//$tag[@name=${xpathLiteral(it)}]"
        }
        el.attr("aria-label").takeIf { it.isNotBlank() }?.let {
            out += Strategy.ARIA_LABEL to ".//$tag[@aria-label=${xpathLiteral(it)}]"
        }
        // A label's `for` pointing at a stable id resolves to that id, which ID above already tried; what is left
        // is addressing the element through the label's text.
        labelFor(el)?.let { (label, text) ->
            val literal = xpathLiteral(text)
            out += if (el.parents().contains(label)) {
                Strategy.LABEL_FOR to ".//label[normalize-space()=$literal]//$tag"
            } else {
                Strategy.LABEL_FOR to ".//label[normalize-space()=$literal]/following::$tag[1]"
            }
        }
        if (type in TEXT_TYPES) {
            Texts.usable(el.text())?.let { out += Strategy.TEXT to ".//$tag[normalize-space()=${xpathLiteral(it)}]" }
        }
        el.attr("placeholder").takeIf { it.isNotBlank() }?.let {
            out += Strategy.PLACEHOLDER to ".//$tag[@placeholder=${xpathLiteral(it)}]"
        }
        val index = module.getElementsByTag(tag).filter { it !== module }.indexOfFirst { it === el }
        if (index >= 0) out += Strategy.POSITIONAL to "(.//$tag)[${index + 1}]"
        return out
    }

    private fun elementName(el: Element, type: ElementType): String {
        val source = listOfNotNull(
            el.attr("aria-label").takeIf { it.isNotBlank() },
            labelFor(el)?.second,
            if (type in TEXT_TYPES || type == ElementType.OTHER_INTERACTIVE) Texts.usable(el.text()) else null,
            el.attr("placeholder").takeIf { it.isNotBlank() },
            el.attr("name").takeIf { DynamicIds.isStable(it) },
            TEST_ID_ATTRS.firstNotNullOfOrNull { attr -> el.attr(attr).takeIf { DynamicIds.isStable(it) } },
            el.id().takeIf { DynamicIds.isStable(it) },
            el.attr("title").takeIf { it.isNotBlank() },
        ).map { Names.camel(it) }.firstOrNull { it.isNotEmpty() } ?: Names.camel(el.normalName())
        return Names.withSuffix(source, SUFFIX.getValue(type))
    }

    companion object {
        val TEST_ID_ATTRS: List<String> = listOf("data-testid", "data-test", "data-qa", "data-cy")
        private val TEXT_TYPES = setOf(ElementType.BUTTON, ElementType.LINK, ElementType.TAB)
        private val SUFFIX = mapOf(
            ElementType.BUTTON to "Button",
            ElementType.LINK to "Link",
            ElementType.TEXT_INPUT to "Input",
            ElementType.DROPDOWN to "Dropdown",
            ElementType.CHECKBOX to "Checkbox",
            ElementType.RADIO to "Radio",
            ElementType.TEXTAREA to "Textarea",
            ElementType.TAB to "Tab",
            ElementType.OTHER_INTERACTIVE to "Element",
        )

        /** `.//x` → `<module>//x`; `(.//x)[n]` → `(<module>//x)[n]`. */
        fun absoluteOf(moduleXpath: String, value: String): String = when {
            value.startsWith(".//") -> moduleXpath + value.substring(1)
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
