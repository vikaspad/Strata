package dev.strata.core

import dev.strata.core.model.ElementType
import dev.strata.core.model.FrameModel
import dev.strata.core.model.LocatorCoverageRun
import dev.strata.core.model.ModuleKind
import dev.strata.core.model.ModuleModel
import dev.strata.core.model.OmittedCount
import dev.strata.core.model.PageModel
import dev.strata.core.model.SnapshotRecord
import dev.strata.core.model.UnmatchedReason
import dev.strata.core.model.UnmatchedUsage
import dev.strata.core.model.UsageRecord
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

/** Snapshots + usage → page/module/xpath model (CLAUDE.md §5). Drops and merges are written to `<runDir>/generator.log`. */
object StrataGenerator {

    fun generate(runDir: Path): LocatorCoverageRun = generate(runDir, Instant.now())

    fun generate(runDir: Path, now: Instant): LocatorCoverageRun {
        val log = mutableListOf<String>()
        val records = ManifestReader.read(runDir.resolve(MANIFEST), log::add).sortedBy { it.seq }
        val usage = ManifestReader.readUsage(runDir.resolve(USAGE), log::add)
        val unmatched = mutableListOf<UnmatchedUsage>()
        val known = records.map { it.seq }.toSet()
        usage.filter { it.snapshotSeq !in known }.forEach { unmatched += unmatchedOf(it, UnmatchedReason.NO_SNAPSHOT) }
        val usageBySeq = usage.filter { it.snapshotSeq in known }.groupBy { it.snapshotSeq }

        val byRoute = records.groupBy { it.routeKey }.toSortedMap()
        val titleUse = byRoute.values.mapNotNull { pageTitle(it) }.groupingBy { it }.eachCount()

        val pages = byRoute.map { (route, visits) ->
            val title = pageTitle(visits)
            val name = if (title != null && titleUse[title] == 1) title else Names.pascal(route).ifEmpty { "Root" }
            buildPage(runDir, name, route, visits, usageBySeq, unmatched, log)
        }
        writeLog(runDir.resolve(LOG), log)
        return LocatorCoverageRun(
            runId = runDir.fileName.toString(),
            generatedAt = now.truncatedTo(ChronoUnit.SECONDS).toString(),
            pages = pages,
            unmatched = unmatched.distinct(),
        )
    }

    /** One snapshot's verified modules, in document order, each with its verified entries. */
    fun analyze(doc: Document, log: (String) -> Unit = {}): List<Pair<ResolvedModule, List<XpathEntry>>> =
        SnapshotAnalyzer(log).analyze(doc).modules

    private fun buildPage(
        runDir: Path,
        name: String,
        route: String,
        visits: List<SnapshotRecord>,
        usageBySeq: Map<Int, List<UsageRecord>>,
        unmatched: MutableList<UnmatchedUsage>,
        log: MutableList<String>,
    ): PageModel {
        val modules = ModuleMerger()
        val frames = LinkedHashMap<String, Pair<FrameHeader, ModuleMerger>>()
        val omitted = sortedMapOf<ElementType, Int>()

        for (record in visits) {
            val logFor: (String) -> Unit = { log += "seq=${record.seq} ${record.routeKey}: $it" }
            val records = usageBySeq[record.seq].orEmpty()
            val doc = load(runDir, record.domFile, record.url) { logFor(it) }
            if (doc == null) {
                records.forEach { unmatched += unmatchedOf(it, UnmatchedReason.STATE_NOT_CAPTURED) }
                continue
            }
            val top = analyzeWithUsage(doc, records.filter { it.framePath.isEmpty() }, unmatched, logFor)
            modules.add(top.modules)
            top.omitted.forEach { (type, count) -> omitted.merge(type, count) { a, b -> maxOf(a, b) } }

            val iframes = doc.select("iframe, frame")
            val captured = record.frames.associateBy { it.index }
            for ((index, ref) in captured) {
                val iframe = iframes.getOrNull(index)
                val frameEntry = iframe?.let { top.covered[it] }
                val frameDoc = load(runDir, ref.domFile, ref.url) { logFor(it) }
                val frameUsage = records.filter { it.framePath == listOf(index) }
                if (frameEntry == null || frameDoc == null) {
                    logFor("frame $index (${ref.url}) skipped: no iframe entry or unreadable frame snapshot")
                    frameUsage.forEach { unmatched += unmatchedOf(it, UnmatchedReason.STATE_NOT_CAPTURED) }
                    continue
                }
                val analysis = analyzeWithUsage(frameDoc, frameUsage, unmatched, logFor)
                val frameName = Names.pascal(iframe.attr("title")).ifEmpty { Names.pascal(frameEntry.name.removeSuffix("Frame")) }
                    .ifEmpty { "Frame" }
                frames.getOrPut(frameEntry.absolute) {
                    FrameHeader(frameName, DefaultRouteNormalizer.normalize(ref.url)) to ModuleMerger()
                }.second.add(analysis.modules)
            }
            records.filter { it.framePath.isNotEmpty() && it.framePath.singleOrNull() !in captured.keys }
                .forEach { unmatched += unmatchedOf(it, UnmatchedReason.STATE_NOT_CAPTURED) }
        }

        return PageModel(
            name = name,
            routeKey = route,
            visits = visits.size,
            modules = modules.build(),
            frames = frames.map { (xpath, f) -> FrameModel(f.first.name, xpath, f.first.routeKey, f.second.build()) },
            omitted = omitted.map { (type, count) -> OmittedCount(type, count) },
        )
    }

    private class FrameHeader(val name: String, val routeKey: String)

    private fun analyzeWithUsage(
        doc: Document,
        records: List<UsageRecord>,
        unmatched: MutableList<UnmatchedUsage>,
        log: (String) -> Unit,
    ): SnapshotAnalysis {
        val usedBy = IdentityHashMap<Element, UsageRecord>()
        for (r in records) {
            val el = resolvePath(doc, r.elementPath, r.tag)
            if (el == null) unmatched += unmatchedOf(r, UnmatchedReason.STATE_NOT_CAPTURED) else usedBy.putIfAbsent(el, r)
        }
        val analysis = SnapshotAnalyzer(log).analyze(doc, usedBy.keys)
        analysis.unaddressable.forEach { el -> usedBy[el]?.let { unmatched += unmatchedOf(it, UnmatchedReason.NOT_ADDRESSABLE) } }
        return analysis
    }

    /** Follows the agent's element-child indexes from `<html>` down (CLAUDE.md §3.1 `UsageRecord.elementPath`). */
    fun resolvePath(doc: Document, path: List<Int>, tag: String): Element? {
        var current: Element = doc.selectFirst("html") ?: return null
        for (index in path) current = current.children().getOrNull(index) ?: return null
        return current.takeIf { it.normalName() == tag.lowercase() }
    }

    private fun unmatchedOf(r: UsageRecord, reason: UnmatchedReason): UnmatchedUsage = UnmatchedUsage(
        routeKey = r.routeKey,
        locator = if (r.locatorUsing != null && r.locatorValue != null) "${r.locatorUsing}: ${r.locatorValue}" else "<${r.tag}>",
        command = r.command,
        reason = reason,
    )

    /** Merges visits: modules by xpath in first-seen order, entries by absolute; `used` ORs, `instances` takes the max. */
    private class ModuleMerger {
        private class Merged(val kind: ModuleKind, val name: String, val entries: LinkedHashMap<String, XpathEntry>)

        private val merged = LinkedHashMap<String, Merged>()

        fun add(modules: List<Pair<ResolvedModule, List<XpathEntry>>>) {
            for ((module, entries) in modules) {
                val target = merged.getOrPut(module.xpath) { Merged(module.kind, module.name, LinkedHashMap()) }
                for (entry in entries) {
                    val existing = target.entries[entry.absolute]
                    target.entries[entry.absolute] = if (existing == null) {
                        entry
                    } else {
                        existing.copy(used = existing.used || entry.used, instances = maxOf(existing.instances, entry.instances))
                    }
                }
            }
        }

        fun build(): List<ModuleModel> {
            val moduleNames = Names.dedupe(merged.values.map { it.name })
            return merged.entries.mapIndexed { i, (xpath, m) ->
                val elements = m.entries.values.toList()
                val names = Names.dedupe(elements.map { it.name })
                ModuleModel(moduleNames[i], m.kind, xpath, elements.mapIndexed { j, e -> e.copy(name = names[j]) })
            }
        }
    }

    fun load(runDir: Path, record: SnapshotRecord, onError: (String) -> Unit = {}): Document? =
        load(runDir, record.domFile, record.url, onError)

    private fun load(runDir: Path, domFile: String, url: String, onError: (String) -> Unit): Document? = try {
        Jsoup.parse(runDir.resolve(domFile).toFile(), "UTF-8", url)
    } catch (e: IOException) {
        onError("unreadable snapshot $domFile: ${e.message}")
        null
    }

    private fun pageTitle(visits: List<SnapshotRecord>): String? =
        visits.firstNotNullOfOrNull { r -> Names.pascal(r.title).takeIf { it.isNotEmpty() } }

    private fun writeLog(path: Path, lines: List<String>) {
        try {
            Files.write(path, lines.map { it.replace('\n', ' ') }, Charsets.UTF_8)
        } catch (_: IOException) {
            // The log is diagnostics only; a read-only run dir must not fail generation.
        }
    }

    const val MANIFEST: String = "manifest.jsonl"
    const val USAGE: String = "usage.jsonl"
    const val LOG: String = "generator.log"
}
