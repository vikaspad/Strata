package dev.strata.core

import dev.strata.core.model.ElementType
import org.jsoup.nodes.Element

/** CLAUDE.md §5.2: the only elements that get XPaths. */
object Interactables {
    const val SELECTOR: String =
        "button, a[href], input:not([type=hidden]), select, textarea, [role=button], [role=link], [role=tab], " +
            "[role=checkbox], [role=radio], [role=combobox], [role=menuitem], [onclick], " +
            "[tabindex]:not([tabindex=\"-1\"]), mat-select, ng-select"

    fun find(root: Element): List<Element> = root.select(SELECTOR).filterNot { isHidden(it) }

    fun isInteractable(el: Element): Boolean = el.`is`(SELECTOR)

    fun isHidden(el: Element): Boolean {
        var current: Element? = el
        while (current != null) {
            if (current.attr("aria-hidden").equals("true", ignoreCase = true) || current.hasAttr("hidden")) return true
            current = current.parent()
        }
        return false
    }

    fun typeOf(el: Element): ElementType {
        val tag = el.normalName()
        val role = el.attr("role").lowercase()
        val type = el.attr("type").lowercase()
        return when {
            role == "tab" -> ElementType.TAB
            role == "checkbox" || (tag == "input" && type == "checkbox") -> ElementType.CHECKBOX
            role == "radio" || (tag == "input" && type == "radio") -> ElementType.RADIO
            tag == "select" || tag == "mat-select" || tag == "ng-select" || role == "combobox" -> ElementType.DROPDOWN
            tag == "textarea" -> ElementType.TEXTAREA
            tag == "button" || role == "button" ||
                (tag == "input" && type in setOf("submit", "button", "reset", "image")) -> ElementType.BUTTON
            tag == "a" || role == "link" -> ElementType.LINK
            tag == "input" -> ElementType.TEXT_INPUT
            else -> ElementType.OTHER_INTERACTIVE
        }
    }
}
