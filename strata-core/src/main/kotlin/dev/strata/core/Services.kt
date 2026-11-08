package dev.strata.core

import dev.strata.core.model.LocatorCoverageRun
import dev.strata.core.model.ModuleKind
import dev.strata.core.model.XpathEntry
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.nio.file.Path

interface RouteNormalizer {
    fun normalize(url: String): String
}

interface ModuleDetector {
    fun detect(doc: Document): List<Pair<Element, ModuleKind>>
}

interface XpathGenerator {
    /** Returns null when no strategy yields an XPath that uniquely matches [el]. */
    fun generate(el: Element, module: Element): XpathEntry?
}

interface XpathVerifier {
    fun count(doc: Document, xpath: String): Int
}

interface CoverageXmlWriter {
    fun write(run: LocatorCoverageRun, out: Path)
}
