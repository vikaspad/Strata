package dev.strata.core

import dev.strata.core.model.ModuleKind
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * CLAUDE.md §5.3. Returns every module candidate in document order, `<body>` (PAGE_ROOT) first.
 * Whether a candidate survives depends on its XPath verifying to exactly one match; see [ModuleXpaths].
 */
object DefaultModuleDetector : ModuleDetector {
    private val LANDMARK_TAGS = setOf("header", "main", "aside", "footer")
    private val GROUP_ROLES = setOf("tablist", "toolbar")
    private val LABELLED_GROUP_ROLES = setOf("group", "region")
    private val MENU_ROLES = setOf("menu", "listbox", "menubar")

    override fun detect(doc: Document): List<Pair<Element, ModuleKind>> {
        val body = doc.body()
        val candidates = body.select("*").drop(1).mapNotNull { el -> kindOf(el)?.let { el to it } }
        return listOf(body to ModuleKind.PAGE_ROOT) + candidates
    }

    fun kindOf(el: Element): ModuleKind? {
        val tag = el.normalName()
        val role = el.attr("role").lowercase()
        return when {
            AgGrid.isHost(el) -> ModuleKind.GRID
            tag == "form" -> ModuleKind.FORM
            tag == "nav" || role == "navigation" -> ModuleKind.NAV
            tag == "table" -> ModuleKind.TABLE
            tag == "dialog" || role == "dialog" || role == "alertdialog" -> ModuleKind.DIALOG
            role in MENU_ROLES || el.hasClass("dropdown-menu") || el.hasClass("cdk-overlay-pane") -> ModuleKind.MENU
            tag == "section" -> ModuleKind.SECTION
            tag in LANDMARK_TAGS -> ModuleKind.LANDMARK
            tag == "fieldset" || role in GROUP_ROLES ||
                (role in LABELLED_GROUP_ROLES && el.attr("aria-label").isNotBlank()) -> ModuleKind.GROUP
            Interactables.isInteractable(el) -> null
            // An anchored element is a container only if something interactive lives in it; a `<p id>` is content.
            (stableTestId(el) != null || DynamicIds.isStable(el.id())) &&
                el.select("*").drop(1).any { Interactables.isInteractable(it) } -> ModuleKind.ANCHORED
            else -> null
        }
    }

    fun stableTestId(el: Element): String? = el.attr("data-testid").takeIf { DynamicIds.isStable(it) }
}

/** A module whose XPath verified to exactly one match in its snapshot. */
data class ResolvedModule(val element: Element, val kind: ModuleKind, val xpath: String, val name: String)

/**
 * Module XPath priority (§5.3): data-testid → stable id → aria-label → tag + heading text → tag + role → for a form,
 * `@name`, `@action` and its classes → tag alone. The later ones only win when they are unique in the snapshot,
 * e.g. the one `<header>`, the one `[role=listbox]` or the one `form[@action='/login']`.
 */
class ModuleXpaths(private val verifier: JsoupXpathVerifier) {

    fun resolve(doc: Document, el: Element, kind: ModuleKind): ResolvedModule? {
        if (kind == ModuleKind.PAGE_ROOT) return ResolvedModule(el, kind, "//body", "Page")
        for ((xpath, nameSource) in candidates(el)) {
            if (verifier.select(doc, xpath) == listOf(el)) {
                return ResolvedModule(el, kind, xpath, moduleName(nameSource, kind))
            }
        }
        return null
    }

    private fun candidates(el: Element): List<Pair<String, String>> {
        val tag = el.normalName()
        val role = el.attr("role").lowercase()
        val out = mutableListOf<Pair<String, String>>()
        DefaultModuleDetector.stableTestId(el)?.let { out += "//$tag[@data-testid=${xpathLiteral(it)}]" to it }
        if (DynamicIds.isStable(el.id())) out += "//$tag[@id=${xpathLiteral(el.id())}]" to el.id()
        el.attr("aria-label").takeIf { it.isNotBlank() }?.let { out += "//$tag[@aria-label=${xpathLiteral(it)}]" to it }
        heading(el)?.let { (headingTag, text) ->
            out += "//$tag[.//$headingTag[normalize-space()=${xpathLiteral(text)}]]" to text
        }
        if (role.isNotEmpty()) out += "//$tag[@role=${xpathLiteral(role)}]" to role
        if (tag == "form") {
            el.attr("name").takeIf { DynamicIds.isStable(it) }?.let { out += "//form[@name=${xpathLiteral(it)}]" to it }
            stableAction(el.attr("action"))?.let { source -> out += "//form[@action=${xpathLiteral(el.attr("action"))}]" to source }
            el.classNames().filter { DynamicIds.isStable(it) }.forEach { out += "//form[${hasClass(it)}]" to it }
        }
        if (el.hasClass("cdk-overlay-pane")) out += "//$tag[${hasClass("cdk-overlay-pane")}]" to "overlay"
        if (el.hasClass("dropdown-menu")) out += "//$tag[${hasClass("dropdown-menu")}]" to "dropdown menu"
        // Landmarks are normally one per page; a second one makes this fail verification and fall through.
        if (tag in SINGLETON_TAGS) out += "//$tag" to tag
        if (AgGrid.isHost(el)) out += AgGrid.fallbackModuleXpath() to "grid"
        return out
    }

    /**
     * A form `action` that names a route rather than a record: no query, no id-like segment (§5.1), no dynamic id.
     * Returns its last path segment as the name source, e.g. `login` for `/login`.
     */
    private fun stableAction(action: String): String? {
        val trimmed = action.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("javascript:", ignoreCase = true)) return null
        if (trimmed.contains('?') || DynamicIds.isDynamic(trimmed)) return null
        val route = DefaultRouteNormalizer.normalize(trimmed)
        if (route.contains("{id}")) return null
        return route.substringAfterLast('/')
    }

    /** The first heading that belongs to [el] itself, not to a module nested inside it. */
    private fun heading(el: Element): Pair<String, String>? {
        val heading = el.select("h1, h2, h3, h4, h5, h6, legend, caption").firstOrNull { h ->
            h.parents().takeWhile { it !== el }.none { DefaultModuleDetector.kindOf(it) != null }
        } ?: return null
        val text = Texts.usable(heading.text()) ?: return null
        return heading.normalName() to text
    }

    private fun moduleName(source: String, kind: ModuleKind): String {
        val base = Names.pascal(source).ifEmpty { Names.pascal(kind.name) }
        return when (kind) {
            ModuleKind.FORM -> Names.withSuffix(base, "Form")
            ModuleKind.DIALOG -> Names.withSuffix(base, "Dialog")
            ModuleKind.MENU -> Names.withSuffix(base, "Menu")
            ModuleKind.GRID -> Names.withSuffix(base, "Grid")
            else -> base
        }
    }

    private companion object {
        val SINGLETON_TAGS = setOf("header", "main", "aside", "footer", "nav")
    }
}

/** XPath 1.0 predicate body for "has CSS class [name]". */
fun hasClass(name: String): String = "contains(concat(' ', normalize-space(@class), ' '), ${xpathLiteral(" $name ")})"

object Texts {
    /** The agent replaces redacted text with this marker (§4.4). */
    const val REDACTED: String = "···"

    /** The agent truncates kept text to 60 chars, so text this long may not match the live page. */
    private const val TRUNCATION_LIMIT = 60

    /** Four or more digits in a row reads as data (ids, amounts, dates), not as UI copy. */
    private val DATA_LIKE = Regex("\\d{4,}")

    /** Normalized text that is safe to match on, or null. */
    fun usable(raw: String): String? {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        return text.takeIf {
            it.isNotEmpty() && !it.contains(REDACTED) && it.length < TRUNCATION_LIMIT && !DATA_LIKE.containsMatchIn(it)
        }
    }
}
