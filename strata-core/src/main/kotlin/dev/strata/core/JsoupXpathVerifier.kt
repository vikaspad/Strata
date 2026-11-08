package dev.strata.core

import org.jsoup.helper.W3CDom
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.IdentityHashMap
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathExpressionException
import javax.xml.xpath.XPathFactory

/**
 * Evaluates XPath 1.0 with the same W3C conversion Jsoup's `selectXpath` uses, but converts each snapshot
 * once instead of once per query. Malformed or non-matching expressions count as 0.
 */
class JsoupXpathVerifier : XpathVerifier {
    private val converted = IdentityHashMap<Document, Converted>()
    private val xpath = XPathFactory.newInstance().newXPath()

    private class Converted(val w3c: org.w3c.dom.Document, val nodes: Map<Element, org.w3c.dom.Node>)

    override fun count(doc: Document, xpath: String): Int = select(doc, xpath).size

    /** Elements of [doc] matched by an absolute [xpath]. */
    fun select(doc: Document, xpath: String): List<Element> {
        val c = convert(doc)
        return evaluate(xpath, c.w3c)
    }

    /** Elements matched by [relative] evaluated with [context] as the context node. */
    fun selectWithin(context: Element, relative: String): List<Element> {
        val doc = context.ownerDocument() ?: return emptyList()
        val node = convert(doc).nodes[context] ?: return emptyList()
        return evaluate(relative, node)
    }

    fun countWithin(context: Element, relative: String): Int = selectWithin(context, relative).size

    private fun evaluate(expression: String, contextNode: org.w3c.dom.Node): List<Element> {
        val nodeList = try {
            xpath.evaluate(expression, contextNode, XPathConstants.NODESET) as org.w3c.dom.NodeList
        } catch (_: XPathExpressionException) {
            return emptyList()
        } catch (_: ClassCastException) {
            return emptyList()
        }
        return (0 until nodeList.length).mapNotNull { i ->
            nodeList.item(i).getUserData(W3CDom.SourceProperty) as? Element
        }
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
