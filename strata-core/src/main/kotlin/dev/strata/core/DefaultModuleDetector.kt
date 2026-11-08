package dev.strata.core

import dev.strata.core.model.ModuleKind
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * CLAUDE.md §5.3. Returns every module candidate in document order, `<body>` (PAGE_ROOT) first.
 * Whether a candidate survives depends on its XPath verifying to exactly one match; see [ModuleXpaths].
 */
object DefaultModuleDetector : ModuleDetector {
    override fun detect(doc: Document): List<Pair<Element, ModuleKind>> {
        val body = doc.body()
        val candidates = body.select("*").drop(1).mapNotNull { el -> kindOf(el)?.let { el to it } }
        return listOf(body to ModuleKind.PAGE_ROOT) + candidates
    }

    fun kindOf(el: Element): ModuleKind? {
        val role = el.attr("role").lowercase()
        return when {
            el.normalName() == "form" -> ModuleKind.FORM
            el.normalName() == "nav" -> ModuleKind.NAV
            el.normalName() == "table" -> ModuleKind.TABLE
            el.normalName() == "dialog" || role == "dialog" || role == "alertdialog" -> ModuleKind.DIALOG
            el.normalName() == "section" -> ModuleKind.SECTION
            Interactables.isInteractable(el) -> null
            stableTestId(el) != null || DynamicIds.isStable(el.id()) -> ModuleKind.ANCHORED
            else -> null
        }
    }

    fun stableTestId(el: Element): String? = el.attr("data-testid").takeIf { DynamicIds.isStable(it) }
}

/** A module whose XPath verified to exactly one match in its snapshot. */
data class ResolvedModule(val element: Element, val kind: ModuleKind, val xpath: String, val name: String)

/** Module XPath priority (§5.3): data-testid → stable id → aria-label → tag + heading text. */
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
        val out = mutableListOf<Pair<String, String>>()
        DefaultModuleDetector.stableTestId(el)?.let { out += "//$tag[@data-testid=${xpathLiteral(it)}]" to it }
        if (DynamicIds.isStable(el.id())) out += "//$tag[@id=${xpathLiteral(el.id())}]" to el.id()
        el.attr("aria-label").takeIf { it.isNotBlank() }?.let { out += "//$tag[@aria-label=${xpathLiteral(it)}]" to it }
        heading(el)?.let { (headingTag, text) ->
            out += "//$tag[.//$headingTag[normalize-space()=${xpathLiteral(text)}]]" to text
        }
        return out
    }

    private fun heading(el: Element): Pair<String, String>? {
        val heading = el.selectFirst("h1, h2, h3, h4, h5, h6, legend, caption") ?: return null
        val text = Texts.usable(heading.text()) ?: return null
        return heading.normalName() to text
    }

    private fun moduleName(source: String, kind: ModuleKind): String {
        val base = Names.pascal(source).ifEmpty { Names.pascal(kind.name) }
        return when (kind) {
            ModuleKind.FORM -> Names.withSuffix(base, "Form")
            ModuleKind.DIALOG -> Names.withSuffix(base, "Dialog")
            else -> base
        }
    }
}

object Texts {
    /** The agent replaces redacted text with this marker (§4.4). */
    const val REDACTED: String = "···"

    /** The agent truncates kept text to 60 chars, so text this long may not match the live page. */
    private const val TRUNCATION_LIMIT = 60

    /** Normalized text that is safe to match on, or null. */
    fun usable(raw: String): String? {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        return text.takeIf { it.isNotEmpty() && !it.contains(REDACTED) && it.length < TRUNCATION_LIMIT }
    }
}
