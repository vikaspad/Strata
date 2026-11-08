package dev.strata.core

import dev.strata.core.model.ElementType
import org.jsoup.nodes.Element

/**
 * CLAUDE.md §5.2: which elements get entries, and as what type. Three classes:
 * - interactive: always emitted (POSITIONAL as a last resort);
 * - structural: tables, rows, headers, cells, lists, list items, iframes; repeated parts become templates;
 * - content: headings, labels, paragraphs, spans, divs, icons; emitted only when anchored or used (rules R1, R2).
 */
object Interactables {
    const val SELECTOR: String =
        "button, a[href], input:not([type=hidden]), select, textarea, [role=button], [role=link], [role=tab], " +
            "[role=checkbox], [role=radio], [role=combobox], [role=menuitem], [role=option], [onclick], " +
            "[tabindex]:not([tabindex=\"-1\"]), mat-select, ng-select, mat-option, option"

    private const val ICON_TAGS = "svg, img, mat-icon, i"
    private val ICON_CLASS = Regex("^(fa|fas|far|fab|fal|fad|bi|glyphicon|material-icons.*|material-symbols.*)$|^(fa|bi|icon|glyphicon|ti|ri)-")
    private val CONTENT_TAGS = setOf("p", "span", "div")
    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val GENERIC_ROLES = setOf("", "presentation", "none", "generic")

    fun find(root: Element): List<Element> = root.select(SELECTOR).filter { isInteractable(it) && !isHidden(it) }

    fun isInteractable(el: Element): Boolean = el.`is`(SELECTOR) || isTextLink(el)

    /** `<a>` without `href` that still reads as a link (SPA routers, click handlers stripped by redaction). */
    private fun isTextLink(el: Element): Boolean =
        el.normalName() == "a" && !el.hasAttr("href") && (el.hasAttr("aria-label") || el.text().isNotBlank())

    fun isHidden(el: Element): Boolean {
        var current: Element? = el
        while (current != null) {
            if (current.attr("aria-hidden").equals("true", ignoreCase = true) || current.hasAttr("hidden")) return true
            current = current.parent()
        }
        return false
    }

    fun isIcon(el: Element): Boolean {
        if (el.normalName() == "svg") return el.parents().none { it.normalName() == "svg" }
        if (el.`is`(ICON_TAGS)) return true
        return el.normalName() == "span" && el.classNames().any { ICON_CLASS.containsMatchIn(it) }
    }

    /** The most specific icon class, e.g. `fa-download` from `fa fa-download fa-lg`. */
    fun iconClass(el: Element): String? {
        val classes = el.classNames().filter { ICON_CLASS.containsMatchIn(it) && !DynamicIds.isDynamic(it) }
        val sizesAndStyles = Regex("^(fa|bi)-(xs|sm|lg|xl|[0-9]+x|fw|spin|pulse|solid|regular|light|thin|brands|duotone)$")
        return classes.filterNot { sizesAndStyles.matches(it) }.maxByOrNull { if (it.contains('-')) 1 else 0 }
            ?.takeIf { it.contains('-') || it.startsWith("material-") }
    }

    /** The entry type of [el], or null if it is not something M2 addresses. Interactive types win over the rest. */
    fun typeOf(el: Element): ElementType? {
        val tag = el.normalName()
        val role = el.attr("role").lowercase()
        val type = el.attr("type").lowercase()
        val popup = el.attr("aria-haspopup").lowercase()
        if (isInteractable(el)) {
            return when {
                role == "tab" -> ElementType.TAB
                role == "option" || role == "menuitem" || tag == "option" || tag == "mat-option" -> ElementType.OPTION
                role == "checkbox" || (tag == "input" && type == "checkbox") -> ElementType.CHECKBOX
                role == "radio" || (tag == "input" && type == "radio") -> ElementType.RADIO
                tag == "select" || tag == "mat-select" || tag == "ng-select" || role == "combobox" ||
                    popup in setOf("listbox", "menu", "true") -> ElementType.DROPDOWN
                tag == "textarea" -> ElementType.TEXTAREA
                tag == "input" && type == "password" -> ElementType.PASSWORD
                tag == "button" || role == "button" ||
                    (tag == "input" && type in setOf("submit", "button", "reset", "image")) -> ElementType.BUTTON
                tag == "a" || role == "link" -> ElementType.LINK
                tag == "input" -> ElementType.TEXT_INPUT
                else -> ElementType.OTHER_INTERACTIVE
            }
        }
        return when {
            isIcon(el) -> ElementType.ICON
            tag == "table" || role == "grid" || role == "treegrid" -> ElementType.TABLE
            tag == "tr" -> ElementType.TABLE_ROW
            tag == "th" || role == "columnheader" -> ElementType.TABLE_HEADER
            tag == "td" -> ElementType.TABLE_CELL
            tag in HEADING_TAGS -> ElementType.HEADING
            tag == "label" -> ElementType.LABEL
            tag == "ul" || tag == "ol" || role == "list" -> ElementType.LIST
            tag == "li" -> ElementType.LIST_ITEM
            tag == "iframe" || tag == "frame" -> ElementType.IFRAME
            tag == "p" -> ElementType.PARAGRAPH
            tag == "span" -> ElementType.SPAN
            tag == "div" -> ElementType.DIV
            else -> null
        }
    }

    /** Content types are only emitted when anchored (R1) or used (R2). */
    fun isContent(type: ElementType): Boolean =
        type == ElementType.PARAGRAPH || type == ElementType.SPAN || type == ElementType.DIV || type == ElementType.ICON

    /** R1 for paragraphs, spans and divs: a test id, a stable id, an aria-label, a meaningful role or a title. */
    fun isAnchored(el: Element): Boolean =
        DefaultXpathGenerator.TEST_ID_ATTRS.any { DynamicIds.isStable(el.attr(it)) } ||
            DynamicIds.isStable(el.id()) ||
            el.attr("aria-label").isNotBlank() ||
            el.attr("role").lowercase() !in GENERIC_ROLES ||
            el.attr("title").isNotBlank()

    fun isContentTag(el: Element): Boolean = el.normalName() in CONTENT_TAGS
}
