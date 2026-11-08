package dev.strata.core

import dev.strata.core.model.ElementType
import org.jsoup.nodes.Element
import java.util.IdentityHashMap

/**
 * Repeated cards (CLAUDE.md §5.13): sibling containers with the same tag and classes, such as product tiles, that
 * each hold the same controls. Instead of a positional XPath for every copy of every control, each control gets one
 * `{n}` template, `<cards>[{n}]<step>`, where `{n}` is the 1-based card number.
 *
 * A label (text, `aria-label`, `title`, `alt`, `name`, `placeholder` or a test id) counts as UI only if **every**
 * card has it, like the repeated-label exception for table rows (§4.4). Text that differs between cards, such as
 * product names and prices, is treated as data and never used. A sibling group without any such shared label is not
 * a card group: menus and category panels whose links all differ keep their own entries.
 */
class CardTemplates(private val verifier: JsoupXpathVerifier) {
    private val builder = TemplateBuilder(verifier)

    /** Cards of one group, all in [module], in document order. */
    class Group(val module: Element, val cards: List<Element>)

    /**
     * @param eligible the type an element inside a card would be emitted as, or null if it gets no entry here.
     * @param used elements the test used; unanchored content inside a card is templated only when used (R2).
     */
    fun build(
        body: Element,
        moduleOf: (Element) -> Element,
        moduleAbsolute: (Element) -> String?,
        isModule: (Element) -> Boolean,
        eligible: (Element) -> ElementType?,
        used: (Element) -> Boolean,
    ): List<Pair<Element, Template>> {
        val out = mutableListOf<Pair<Element, Template>>()
        for (group in groups(body, moduleOf, isModule, eligible)) {
            val absolute = moduleAbsolute(group.module) ?: continue
            val cards = cardsTemplate(group, absolute) ?: continue
            cardEntry(group, absolute, cards)?.let { out += group.module to it }
            out += controls(group, absolute, cards, moduleOf, eligible, used).map { group.module to it }
        }
        return out
    }

    /** Sibling groups that share a signature and a label in every card, innermost groups only. */
    fun groups(
        body: Element,
        moduleOf: (Element) -> Element,
        isModule: (Element) -> Boolean,
        eligible: (Element) -> ElementType?,
    ): List<Group> {
        // Siblings with the same signature, combined across parents with the same signature in the same module,
        // e.g. the product tiles of every slide in a carousel.
        val byKey = LinkedHashMap<Triple<ModuleKey, String, String>, MutableList<Element>>()
        for (parent in body.select("*")) {
            if (excluded(parent)) continue
            val siblings = parent.children().filter { !Interactables.isHidden(it) && !isModule(it) }
            for ((signature, members) in siblings.groupBy { signature(it) }) {
                if (signature == null || members.size < 2) continue
                if (members.any { card -> Interactables.find(card).isEmpty() }) continue
                val key = Triple(ModuleKey(moduleOf(members.first())), signature(parent) ?: parent.normalName(), signature)
                byKey.getOrPut(key) { mutableListOf() } += members
            }
        }
        val qualified = byKey.map { (key, cards) -> Group(key.first.el, cards) }
            .filter { group -> sharedLabels(group.cards, eligible).isNotEmpty() }
        // A group whose cards contain another group's cards (a carousel slide holding tiles) yields to the inner one.
        return qualified.filter { outer ->
            qualified.none { inner -> inner !== outer && inner.cards.any { c -> outer.cards.any { o -> c.parents().contains(o) } } }
        }
    }

    /** `<parent>/<card>[{n}]`, or `(<cards>)[{n}]` when the cards have several parents or no locatable one. */
    private fun cardsTemplate(group: Group, moduleAbsolute: String): String? {
        val cards = group.cards
        val tag = DefaultXpathGenerator.step(cards.first())
        val cardSteps = sharedClasses(cards).map { "$tag[${hasClass(it)}]" } + tag
        val parents = cards.mapNotNull { it.parent() }.distinctBy { System.identityHashCode(it) }
        val single = parents.singleOrNull()
        val parentSteps = mutableListOf<String>()
        if (single === group.module) parentSteps += "."
        val parentTag = DefaultXpathGenerator.step(parents.first())
        if (single != null) {
            DefaultXpathGenerator.TEST_ID_ATTRS.firstNotNullOfOrNull { a -> single.attr(a).takeIf { DynamicIds.isStable(it) }?.let { a to it } }
                ?.let { (a, v) -> parentSteps += ".//$parentTag[@$a=${xpathLiteral(v)}]" }
            if (DynamicIds.isStable(single.id())) parentSteps += ".//$parentTag[@id=${xpathLiteral(single.id())}]"
        }
        sharedClasses(parents).forEach { parentSteps += ".//$parentTag[${hasClass(it)}]" }

        val options = mutableListOf<Pair<String, String>>()
        for (p in parentSteps) for (c in cardSteps) {
            options += "$p/$c" to if (single != null) "$p/$c[{n}]" else "($p/$c)[{n}]"
        }
        for (c in cardSteps) options += "(.//$c)" to "(.//$c)[{n}]"
        val doc = group.module.ownerDocument() ?: return null
        return options.firstOrNull { (all, _) ->
            same(verifier.selectWithin(group.module, all), cards) &&
                same(verifier.select(doc, DefaultXpathGenerator.absoluteOf(moduleAbsolute, all)), cards)
        }?.second
    }

    private fun cardEntry(group: Group, moduleAbsolute: String, cards: String): Template? {
        val first = group.cards.first()
        val type = if (first.normalName() == "li") ElementType.LIST_ITEM else ElementType.DIV
        val source = (listOf(first) + first.select("*")).firstNotNullOfOrNull { el ->
            el.classNames().firstOrNull { isNameable(it) }
        }?.replace(WRAPPER_WORDS, "") ?: "card"
        return builder.indexed(group.module, moduleAbsolute, cards, "{n}",
            group.cards.indices.associate { it + 1 to group.cards[it] }, type,
            Names.withSuffix(Names.camel(source).ifEmpty { "card" }, "Card"))
    }

    private fun controls(
        group: Group,
        moduleAbsolute: String,
        cards: String,
        moduleOf: (Element) -> Element,
        eligible: (Element) -> ElementType?,
        used: (Element) -> Boolean,
    ): List<Template> {
        val shared = sharedLabels(group.cards, eligible) + sharedIconClasses(group.cards, eligible)
        data class Found(val card: Int, val el: Element, val type: ElementType, val step: Step)

        val found = group.cards.flatMapIndexed { i, card ->
            card.select("*").drop(1).mapNotNull { el ->
                val type = eligible(el) ?: return@mapNotNull null
                // A module nested in a card (a form per tile) has its own entries.
                if (moduleOf(el) !== group.module) return@mapNotNull null
                if (Interactables.isContent(type) && !anchored(el, type) && !used(el)) return@mapNotNull null
                val structural = !Interactables.isContent(type) || used(el)
                stepFor(card, el, type, shared, structural)?.let { Found(i + 1, el, type, it) }
            }
        }
        val out = mutableListOf<Template>()
        for ((step, members) in found.groupBy { it.step.xpath }) {
            val byCard = members.associate { it.card to it.el }
            if (byCard.size != members.size) continue
            val sample = members.first()
            val name = Names.withSuffix(Names.camel(sample.step.name).ifEmpty { "card" }, DefaultXpathGenerator.SUFFIX.getValue(sample.type))
            builder.indexed(group.module, moduleAbsolute, "$cards$step", "{n}", byCard, sample.type, name)?.let(out::add)
        }
        return out
    }

    private class Step(val xpath: String, val name: String)

    /**
     * The step from a card to [el], tried in order: a shared label; the nearest classed ancestor plus a shared
     * label or the bare tag; the bare tag; the tag's position in the card. Only the first matching exactly [el] counts.
     */
    private fun stepFor(card: Element, el: Element, type: ElementType, shared: Set<String>, structural: Boolean): Step? {
        val tag = DefaultXpathGenerator.step(el)
        val labels = labels(el, type).filter { "$tag|${it.first}" in shared } +
            listOfNotNull(Interactables.iconClass(el)?.let { hasClass(it) to it.substringAfter('-') }).filter { "$tag|${it.first}" in shared }
        val options = mutableListOf<Step>()
        labels.forEach { (pred, name) -> options += Step("//$tag[$pred]", name) }
        val qualifiers = el.parents().takeWhile { it !== card }.flatMap { anc ->
            anc.classNames().filter { isStableClass(it) }.map { "//${DefaultXpathGenerator.step(anc)}[${hasClass(it)}]" to it }
        }
        for ((q, qName) in qualifiers) {
            labels.forEach { (pred, name) -> options += Step("$q//$tag[$pred]", name) }
            options += Step("$q//$tag", qName.replace(WRAPPER_WORDS, ""))
        }
        options += Step("//$tag", el.normalName())
        if (structural) {
            val nth = card.select(el.normalName()).filter { it !== card }.indexOfFirst { it === el } + 1
            if (nth > 0) options += Step("/descendant::$tag[$nth]", el.normalName())
        }
        return options.firstOrNull { verifier.selectWithin(card, ".${it.xpath}") == listOf(el) }
    }

    /** `tag|predicate` labels present in every card. */
    private fun sharedLabels(cards: List<Element>, eligible: (Element) -> ElementType?): Set<String> =
        cards.map { card ->
            card.select("*").drop(1).flatMap { el ->
                val type = eligible(el) ?: return@flatMap emptyList()
                labels(el, type).map { "${DefaultXpathGenerator.step(el)}|${it.first}" }
            }.toSet()
        }.reduce { a, b -> a intersect b }

    private fun sharedIconClasses(cards: List<Element>, eligible: (Element) -> ElementType?): Set<String> =
        cards.map { card ->
            card.select("*").filter { eligible(it) != null }.mapNotNull { el ->
                Interactables.iconClass(el)?.let { "${DefaultXpathGenerator.step(el)}|${hasClass(it)}" }
            }.toSet()
        }.reduce { a, b -> a intersect b }

    /** Predicate and name source for each label of [el]. */
    private fun labels(el: Element, type: ElementType): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (a in DefaultXpathGenerator.TEST_ID_ATTRS) el.attr(a).takeIf { DynamicIds.isStable(it) }?.let { out += "@$a=${xpathLiteral(it)}" to it }
        el.attr("name").takeIf { DynamicIds.isStable(it) }?.let { out += "@name=${xpathLiteral(it)}" to it }
        for (a in listOf("aria-label", "title", "alt", "placeholder")) {
            el.attr(a).takeIf { it.isNotBlank() && !DynamicIds.isDynamic(it) }?.let { out += "@$a=${xpathLiteral(it)}" to it }
        }
        if (type !in NO_TEXT) Texts.usable(el.text())?.let { out += "normalize-space()=${xpathLiteral(it)}" to it }
        return out
    }

    private fun anchored(el: Element, type: ElementType): Boolean =
        Interactables.isAnchored(el) || type == ElementType.ICON &&
            (Interactables.iconClass(el) != null || listOf("alt", "title", "data-icon").any { el.attr(it).isNotBlank() } ||
                el.parents().any { Interactables.isInteractable(it) })

    /** Tag plus stable classes without state classes; null for elements that never form cards. */
    private fun signature(el: Element): String? {
        if (el.normalName() in NOT_CARDS) return null
        val classes = el.classNames().filter { isStableClass(it) }.sorted()
        return (listOf(el.normalName()) + classes).joinToString(".")
    }

    /** Classes every element has, the most specific (non-layout) first. */
    private fun sharedClasses(elements: List<Element>): List<String> {
        val shared = elements.map { it.classNames().filter { c -> isStableClass(c) }.toSet() }.reduce { a, b -> a intersect b }
        return elements.first().classNames().filter { it in shared }.sortedBy { if (LAYOUT.containsMatchIn(it)) 1 else 0 }
    }

    private fun excluded(parent: Element): Boolean =
        parent.normalName() in NOT_CARD_PARENTS ||
            parent.parents().any { it.normalName() in NOT_CARD_PARENTS } ||
            AgGrid.isHost(parent) || AgGrid.hostOf(parent) != null

    private fun isStableClass(c: String): Boolean = DynamicIds.isStable(c) && !STATE.matches(c)

    private fun isNameable(c: String): Boolean = isStableClass(c) && !LAYOUT.containsMatchIn(c)

    private class ModuleKey(val el: Element) {
        override fun equals(other: Any?): Boolean = other is ModuleKey && other.el === el
        override fun hashCode(): Int = System.identityHashCode(el)
    }

    private fun same(a: List<Element>, b: List<Element>): Boolean = a.size == b.size && a.zip(b).all { (x, y) -> x === y }

    private companion object {
        /** Structures with templates of their own (§5.9, §5.12) and elements that are never containers. */
        val NOT_CARD_PARENTS = setOf("table", "thead", "tbody", "tfoot", "tr", "select", "datalist", "svg", "head")
        val NOT_CARDS = setOf("script", "style", "option", "tr", "td", "th", "a", "button", "input", "select", "textarea", "label")
        val NO_TEXT = setOf(
            ElementType.PARAGRAPH, ElementType.SPAN, ElementType.DIV, ElementType.TABLE, ElementType.TABLE_CELL,
            ElementType.TABLE_ROW, ElementType.LIST, ElementType.LIST_ITEM, ElementType.IFRAME,
        )
        /** Toggled by scripts, so they differ between otherwise identical cards (a carousel's `active` slide). */
        val STATE = Regex("^(active|show|shown|in|open|opened|selected|current|disabled|hidden|collapsed|expanded|focus|hover|is-.+|has-.+)$")
        /** Grid-system and utility classes: fine for matching, poor for names. */
        val LAYOUT = Regex("^(col|row|container|pull|push|offset|text|clearfix|d|flex|m[trblxy]?|p[trblxy]?|g|gap|w|h)(-|$)")
        val WRAPPER_WORDS = Regex("[-_]?(wrapper|container|inner|outer|box|holder)$")
    }
}
