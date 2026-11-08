package dev.strata.core

import dev.strata.core.model.LocatorCoverageRun
import dev.strata.core.model.ModuleKind
import dev.strata.core.model.ModuleModel
import dev.strata.core.model.PageModel
import dev.strata.core.model.SnapshotRecord
import dev.strata.core.model.XpathEntry
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.IdentityHashMap

/** Snapshots → page/module/xpath model (CLAUDE.md §5). Drops and merges are written to `<runDir>/generator.log`. */
object StrataGenerator {

    fun generate(runDir: Path): LocatorCoverageRun = generate(runDir, Instant.now())

    fun generate(runDir: Path, now: Instant): LocatorCoverageRun {
        val log = mutableListOf<String>()
        val records = ManifestReader.read(runDir.resolve(MANIFEST), log::add).sortedBy { it.seq }
        val byRoute = records.groupBy { it.routeKey }.toSortedMap()
        val titleUse = byRoute.values.mapNotNull { pageTitle(it) }.groupingBy { it }.eachCount()

        val pages = byRoute.map { (route, visits) ->
            val title = pageTitle(visits)
            val name = if (title != null && titleUse[title] == 1) title else Names.pascal(route).ifEmpty { "Root" }
            PageModel(name = name, routeKey = route, visits = visits.size, modules = mergeVisits(runDir, visits, log))
        }
        writeLog(runDir.resolve(LOG), log)
        return LocatorCoverageRun(
            runId = runDir.fileName.toString(),
            generatedAt = now.truncatedTo(ChronoUnit.SECONDS).toString(),
            pages = pages,
        )
    }

    /** One snapshot's verified modules, in document order, each with its verified entries. */
    fun analyze(doc: Document, log: (String) -> Unit = {}): List<Pair<ResolvedModule, List<XpathEntry>>> {
        val verifier = JsoupXpathVerifier()
        val moduleXpaths = ModuleXpaths(verifier)
        val resolved = IdentityHashMap<Element, ResolvedModule>()
        val order = mutableListOf<ResolvedModule>()
        for ((el, kind) in DefaultModuleDetector.detect(doc)) {
            val module = moduleXpaths.resolve(doc, el, kind)
            if (module == null) {
                log("merged module ${outline(el)} into parent: no unique module xpath")
            } else {
                resolved[el] = module
                order += module
            }
        }

        val generator = DefaultXpathGenerator(verifier) { resolved[it]?.xpath }
        val entries = IdentityHashMap<Element, MutableList<XpathEntry>>()
        for (el in Interactables.find(doc.body())) {
            val module = el.parents().firstOrNull { resolved.containsKey(it) } ?: doc.body()
            val entry = generator.generate(el, module)
            if (entry == null) {
                log("dropped ${outline(el)} in ${resolved[module]?.xpath}: no strategy matched exactly 1")
            } else {
                entries.getOrPut(module) { mutableListOf() } += entry
            }
        }
        return order.mapNotNull { m -> entries[m.element]?.let { m to it } }
    }

    private fun mergeVisits(runDir: Path, visits: List<SnapshotRecord>, log: MutableList<String>): List<ModuleModel> {
        class Merged(val kind: ModuleKind, val name: String, val entries: LinkedHashMap<String, XpathEntry>)

        val merged = LinkedHashMap<String, Merged>()
        for (record in visits) {
            val doc = load(runDir, record) { log += "seq=${record.seq} ${record.domFile}: $it" } ?: continue
            for ((module, entries) in analyze(doc) { log += "seq=${record.seq} ${record.routeKey}: $it" }) {
                val target = merged.getOrPut(module.xpath) { Merged(module.kind, module.name, LinkedHashMap()) }
                for (entry in entries) target.entries.putIfAbsent(entry.absolute, entry)
            }
        }

        val moduleNames = Names.dedupe(merged.values.map { it.name })
        return merged.entries.mapIndexed { i, (xpath, m) ->
            val elements = m.entries.values.toList()
            val names = Names.dedupe(elements.map { it.name })
            ModuleModel(
                name = moduleNames[i],
                kind = m.kind,
                xpath = xpath,
                elements = elements.mapIndexed { j, e -> e.copy(name = names[j]) },
            )
        }
    }

    fun load(runDir: Path, record: SnapshotRecord, onError: (String) -> Unit = {}): Document? = try {
        Jsoup.parse(runDir.resolve(record.domFile).toFile(), "UTF-8", record.url)
    } catch (e: IOException) {
        onError("unreadable snapshot: ${e.message}")
        null
    }

    private fun pageTitle(visits: List<SnapshotRecord>): String? =
        visits.firstNotNullOfOrNull { r -> Names.pascal(r.title).takeIf { it.isNotEmpty() } }

    private fun outline(el: Element): String {
        val attrs = el.attributes().joinToString(" ") { "${it.key}=\"${it.value}\"" }
        return "<${el.normalName()}${if (attrs.isEmpty()) "" else " $attrs"}>".take(160)
    }

    private fun writeLog(path: Path, lines: List<String>) {
        try {
            Files.write(path, lines.map { it.replace('\n', ' ') }, Charsets.UTF_8)
        } catch (_: IOException) {
            // The log is diagnostics only; a read-only run dir must not fail generation.
        }
    }

    const val MANIFEST: String = "manifest.jsonl"
    const val LOG: String = "generator.log"
}
