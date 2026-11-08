package dev.strata.core

import dev.strata.core.model.LocatorCoverageRun
import dev.strata.core.model.ModuleModel
import dev.strata.core.model.XpathEntry
import java.nio.file.Files
import java.nio.file.Path

/** Schema v2 (CLAUDE.md §8). Output is byte-identical for identical input; line endings are always `\n`. */
object DefaultCoverageXmlWriter : CoverageXmlWriter {

    override fun write(run: LocatorCoverageRun, out: Path) {
        out.parent?.let { Files.createDirectories(it) }
        Files.write(out, render(run).toByteArray(Charsets.UTF_8))
    }

    fun render(run: LocatorCoverageRun): String = buildString {
        val pages = run.pages.sortedBy { it.routeKey }
        val all = pages.flatMap { p -> p.modules + p.frames.flatMap { it.modules } }
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<locator-coverage schema=\"2\" run=\"${esc(run.runId)}\" generated=\"${esc(run.generatedAt)}\"${counts(all)}>\n")
        for (page in pages) {
            val pageModules = page.modules + page.frames.flatMap { it.modules }
            append("  <page name=\"${esc(page.name)}\" route=\"${esc(page.routeKey)}\" visits=\"${page.visits}\"${counts(pageModules)}>\n")
            page.modules.forEach { module(it, "    ") }
            for (frame in page.frames) {
                append("    <frame name=\"${esc(frame.name)}\" xpath=\"${esc(frame.xpath)}\" route=\"${esc(frame.routeKey)}\"${counts(frame.modules)}>\n")
                frame.modules.forEach { module(it, "      ") }
                append("    </frame>\n")
            }
            for (o in page.omitted) append("    <omitted type=\"${o.type}\" count=\"${o.count}\"/>\n")
            append("  </page>\n")
        }
        for (u in run.unmatched.sortedWith(compareBy({ it.routeKey }, { it.locator }, { it.command }, { it.reason }))) {
            append("  <unmatched route=\"${esc(u.routeKey)}\" locator=\"${esc(u.locator)}\" command=\"${esc(u.command)}\" reason=\"${u.reason}\"/>\n")
        }
        append("</locator-coverage>\n")
    }

    private fun StringBuilder.module(module: ModuleModel, indent: String) {
        append("$indent<module name=\"${esc(module.name)}\" kind=\"${module.kind}\" xpath=\"${esc(module.xpath)}\"${counts(listOf(module))}>\n")
        for (x in module.elements) xpath(x, "$indent  ")
        append("$indent</module>\n")
    }

    private fun StringBuilder.xpath(x: XpathEntry, indent: String) {
        val pad = " ".repeat(indent.length + 7)
        val instances = if (x.isTemplate) " instances=\"${x.instances}\"" else ""
        append("$indent<xpath name=\"${esc(x.name)}\" type=\"${x.type}\" strategy=\"${x.strategy}\" stability=\"${x.stability}\"")
        append(" matches=\"${x.matches}\"$instances used=\"${x.used}\"\n")
        append("${pad}value=\"${esc(x.value)}\"\n")
        append("${pad}absolute=\"${esc(x.absolute)}\"/>\n")
    }

    private fun counts(modules: List<ModuleModel>): String {
        val entries = modules.flatMap { it.elements }
        return " used=\"${entries.count { it.used }}\" total=\"${entries.size}\""
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
