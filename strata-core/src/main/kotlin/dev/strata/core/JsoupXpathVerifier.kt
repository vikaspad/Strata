package dev.strata.core

import org.jaxen.JaxenException
import org.jaxen.dom.DOMXPath
import org.jsoup.helper.W3CDom
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.IdentityHashMap

/**
 * Evaluates XPath 1.0 over one W3C conversion of each snapshot. Jaxen navigates the DOM directly; the JDK evaluator
 * walks the document on every call, which made verifying a 1000-row grid take minutes (CLAUDE.md §5.9).
 * Malformed or non-matching expressions count as 0. Not thread-safe: one verifier per snapshot analysis.
 */
class JsoupXpathVerifier : XpathVerifier {
    private val converted = IdentityHashMap<Document, Converted>()
    private val compiled = HashMap<String, DOMXPath?>()

    private class Converted(val w3c: org.w3c.dom.Document, val nodes: Map<Element, org.w3c.dom.Node>)

    override fun count(doc: Document, xpath: String): Int = select(doc, xpath).size

    /** Elements of [doc] matched by an absolute [xpath]. */
    fun select(doc: Document, xpath: String): List<Element> = evaluate(xpath, convert(doc).w3c)

    /** Elements matched by [relative] evaluated with [context] as the context node. */
    fun selectWithin(context: Element, relative: String): List<Element> {
        val doc = context.ownerDocument() ?: return emptyList()
        val node = convert(doc).nodes[context] ?: return emptyList()
        return evaluate(relative, node)
    }

    fun countWithin(context: Element, relative: String): Int = selectWithin(context, relative).size

    private fun evaluate(expression: String, contextNode: org.w3c.dom.Node): List<Element> {
        val path = compiled.getOrPut(expression) {
            try {
                DOMXPath(expression)
            } catch (_: JaxenException) {
                null
            }
        } ?: return emptyList()
        val nodes = try {
            path.selectNodes(contextNode)
        } catch (_: JaxenException) {
            return emptyList()
        }
        return nodes.mapNotNull { (it as? org.w3c.dom.Node)?.getUserData(W3CDom.SourceProperty) as? Element }
    }

    private fun convert(doc: Document): Converted = converted.getOrPut(doc) {
        val w3c = W3CDom().namespaceAware(false).fromJsoup(doc)
        val nodes = IdentityHashMap<Element, org.w3c.dom.Node>()
        val all = w3c.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val node = all.item(i)
            (node.getUserData(W3CDom.SourceProperty) as? Element)?.let { nodes[it] = node }
        }
        Converted(w3c, nodes)
    }
}
