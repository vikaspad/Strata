package dev.strata.core

import dev.strata.core.model.LocatorCoverageRun
import java.nio.file.Files
import java.nio.file.Path

/** Schema v1 (CLAUDE.md §8). Output is byte-identical for identical input; line endings are always `\n`. */
object DefaultCoverageXmlWriter : CoverageXmlWriter {

    override fun write(run: LocatorCoverageRun, out: Path) {
        out.parent?.let { Files.createDirectories(it) }
        Files.write(out, render(run).toByteArray(Charsets.UTF_8))
    }

    fun render(run: LocatorCoverageRun): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<locator-coverage schema=\"1\" run=\"${esc(run.runId)}\" generated=\"${esc(run.generatedAt)}\">\n")
        for (page in run.pages.sortedBy { it.routeKey }) {
            append("  <page name=\"${esc(page.name)}\" route=\"${esc(page.routeKey)}\" visits=\"${page.visits}\">\n")
            for (module in page.modules) {
                append("    <module name=\"${esc(module.name)}\" kind=\"${module.kind}\" xpath=\"${esc(module.xpath)}\">\n")
                for (x in module.elements) {
                    append("      <xpath name=\"${esc(x.name)}\" type=\"${x.type}\" strategy=\"${x.strategy}\" matches=\"${x.matches}\"\n")
                    append("             value=\"${esc(x.value)}\"\n")
                    append("             absolute=\"${esc(x.absolute)}\"/>\n")
                }
                append("    </module>\n")
            }
            append("  </page>\n")
        }
        append("</locator-coverage>\n")
    }

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\n' -> append("&#10;")
                '\r' -> append("&#13;")
                '\t' -> append("&#9;")
                else -> append(c)
            }
        }
    }
}
