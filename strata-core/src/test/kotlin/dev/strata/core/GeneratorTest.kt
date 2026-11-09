package dev.strata.core

import dev.strata.core.model.ElementType
import dev.strata.core.model.ModuleKind
import dev.strata.core.model.SnapshotRecord
import dev.strata.core.model.Stability
import dev.strata.core.model.Strategy
import dev.strata.core.model.Trigger
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val fixtures: Path = Path.of(System.getProperty("strata.fixtures"))
private val goldenRun: Path = fixtures.resolve("snapshots/20261107-120000")
private val goldenXml: Path = fixtures.resolve("snapshots/expected-locator-coverage.xml")

/** Copies the golden run so the generator's `generator.log` does not land in the fixtures. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun copyRun(tmp: Path): Path {
    val dir = tmp.resolve(goldenRun.fileName.toString())
    goldenRun.copyToRecursively(dir, followLinks = false, overwrite = true)
    return dir
}

private fun withoutGenerated(xml: String): String = xml.replace(Regex(" generated=\"[^\"]*\""), "")

class GoldenXmlTest {
    @Test
    fun matchesGoldenFile(@TempDir tmp: Path) {
        val run = StrataGenerator.generate(copyRun(tmp), Instant.parse("2026-11-07T12:01:00Z"))
        val xml = DefaultCoverageXmlWriter.render(run)
        if (System.getProperty("strata.updateGolden") == "true") goldenXml.writeText(xml)
        assertEquals(withoutGenerated(goldenXml.readText()).replace("\r\n", "\n"), withoutGenerated(xml))
    }

    @Test
    fun outputIsDeterministic(@TempDir tmp: Path) {
        val dir = copyRun(tmp)
        val first = DefaultCoverageXmlWriter.render(StrataGenerator.generate(dir, Instant.EPOCH))
        val second = DefaultCoverageXmlWriter.render(StrataGenerator.generate(dir, Instant.EPOCH))
        assertEquals(first, second)
    }

    @Test
    fun everyXpathReverifiesAndHasNoDynamicId(@TempDir tmp: Path) {
        val dir = copyRun(tmp)
        val run = StrataGenerator.generate(dir)
        val records = ManifestReader.read(dir.resolve(StrataGenerator.MANIFEST))
        val verifier = JsoupXpathVerifier()
        for (page in run.pages) {
            val docs = records.filter { it.routeKey == page.routeKey }.mapNotNull { StrataGenerator.load(dir, it) }
            for (module in page.modules) {
                assertTrue(docs.any { verifier.count(it, module.xpath) == 1 }, module.xpath)
                for (x in module.elements) {
                    assertEquals(1, x.matches)
                    assertTrue(x.value.startsWith("./") || x.value.startsWith("(.//"), x.value)
                    when {
                        x.strategy == Strategy.TEXT_TEMPLATE -> assertTrue(
                            docs.any { verifier.count(it, x.absolute.replace("[normalize-space()='%s']", "")) == x.instances },
                            "text template: ${x.absolute}",
                        )
                        x.isTemplate -> for (i in 1..x.instances) {
                            val concrete = x.absoluteFor(i)
                            assertTrue(docs.any { verifier.count(it, concrete) == 1 }, "template instance $i: $concrete")
                        }
                        else -> assertTrue(docs.any { verifier.count(it, x.absolute) == 1 }, "does not re-verify: ${x.absolute}")
                    }
                    DynamicIds.idLiterals(x.absolute).forEach { assertFalse(DynamicIds.isDynamic(it), x.absolute) }
                }
            }
        }
    }

    @Test
    fun pagesAreSortedByRouteAndNamedByUniqueTitle(@TempDir tmp: Path) {
        val run = StrataGenerator.generate(copyRun(tmp))
        assertEquals(listOf("/blotter/{id}", "/components", "/grid", "/login"), run.pages.map { it.routeKey })
        assertEquals(listOf("TradeBlotter", "Components", "OrderGrid", "Login"), run.pages.map { it.name })
        assertEquals("20261107-120000", run.runId)
    }

    /** M2 criterion 0 against the golden run: all 21 required components, with usage, frames and omitted counts. */
    @Test
    fun allRequiredComponentsAreCovered(@TempDir tmp: Path) {
        val page = StrataGenerator.generate(copyRun(tmp)).pages.single { it.routeKey == "/components" }
        val entries = page.modules.flatMap { it.elements }
        val types = entries.map { it.type }.toSet()
        val required = ElementType.entries - ElementType.TAB - ElementType.OTHER_INTERACTIVE
        assertEquals(emptySet(), required.toSet() - types)
        assertEquals("/frame.html", page.frames.single().routeKey)
        assertTrue(page.frames.single().modules.flatMap { it.elements }.any { it.name == "questionInput" && it.used })
        assertTrue(page.omitted.isNotEmpty())
        assertTrue(entries.single { it.name == "priceCell" }.let { it.used && it.stability == Stability.MEDIUM && it.instances == 3 })
        assertTrue(entries.single { it.name == "closeButton" }.let { it.strategy == Strategy.ROW_TEMPLATE && it.instances == 3 })
        assertTrue(entries.single { it.name == "countryOption" }.let { it.used && it.instances == 3 })
        assertTrue(entries.single { it.name == "currencyOption" }.let { it.used && it.instances == 3 })
        assertTrue(entries.none { it.strategy == Strategy.LABEL_FOR && it.value.contains("following::") })
    }

    /** §5.12 against a real AG Grid 36 snapshot. */
    @Test
    fun agGridIsModelledByColumn(@TempDir tmp: Path) {
        val page = StrataGenerator.generate(copyRun(tmp)).pages.single { it.routeKey == "/grid" }
        val grid = page.modules.single { it.kind == ModuleKind.GRID }
        assertEquals("//div[@data-testid='orders-grid']", grid.xpath)
        val names = grid.elements.associateBy { it.name }
        assertEquals(setOf("orderHeader", "clientHeader", "sideHeader", "notionalHeader", "statusHeader", "actionsHeader"),
            names.keys.filter { it.endsWith("Header") && it != "selectionHeader" }.toSet())
        assertTrue(names.getValue("cancelButton").used)
        assertEquals(4, names.getValue("notionalCell").instances)
        assertTrue(grid.elements.none { it.stability == Stability.LOW })
        assertEquals(emptyList(), page.omitted)
    }

    @Test
    fun coverageNumbersAreExact(@TempDir tmp: Path) {
        val run = StrataGenerator.generate(copyRun(tmp))
        val xml = DefaultCoverageXmlWriter.render(run)
        val all = run.pages.flatMap { p -> (p.modules + p.frames.flatMap { it.modules }).flatMap { it.elements } }
        assertTrue(xml.contains("schema=\"4\""))
        assertTrue(xml.contains("used=\"${all.count { it.used }}\" total=\"${all.size}\">"))
        assertEquals(emptyList(), run.unmatched)
    }
}

class MergeTest {
    private fun record(seq: Int, route: String, title: String) = SnapshotRecord(
        seq = seq, sessionId = "s", url = "http://h$route", routeKey = route, title = title, trigger = Trigger.GET,
        containerHash = "h$seq", domFile = "dom/$seq.dom.html", capturedAt = "2026-11-07T12:00:00Z", settleMs = 500,
    )

    private fun writeRun(dir: Path, vararg snapshots: Pair<SnapshotRecord, String>) {
        dir.resolve("dom").createDirectories()
        val lines = snapshots.map { (r, html) ->
            dir.resolve(r.domFile).writeText(html)
            """{"seq":${r.seq},"sessionId":"s","url":"${r.url}","routeKey":"${r.routeKey}","title":"${r.title}",""" +
                """"trigger":"GET","containerHash":"${r.containerHash}","domFile":"${r.domFile}",""" +
                """"capturedAt":"${r.capturedAt}","settleMs":500,"skipped":[]}"""
        }
        Files.write(dir.resolve("manifest.jsonl"), lines)
    }

    @Test
    fun snapshotsOfOneRouteMerge(@TempDir tmp: Path) {
        val dir = tmp.resolve("20261107-000000")
        writeRun(
            dir,
            record(1, "/orders/{id}", "Order") to
                "<body><form id='f'><input name='qty'><button>Save</button></form></body>",
            record(2, "/orders/{id}", "Order") to
                "<body><form id='f'><input name='qty'><button>Save</button></form>" +
                "<div role='dialog' id='confirm'><button>OK</button></div></body>",
        )
        val run = StrataGenerator.generate(dir)
        val page = run.pages.single()
        assertEquals(2, page.visits)
        assertEquals("Order", page.name)
        assertEquals(listOf("//form[@id='f']", "//div[@id='confirm']"), page.modules.map { it.xpath })
        assertEquals(listOf("qtyInput", "saveButton"), page.modules[0].elements.map { it.name })
        assertEquals(listOf("okButton"), page.modules[1].elements.map { it.name })
    }

    @Test
    fun sharedTitleFallsBackToRouteName(@TempDir tmp: Path) {
        val dir = tmp.resolve("r")
        writeRun(
            dir,
            record(1, "/a", "Portal") to "<body><button>A</button></body>",
            record(2, "/b/{id}", "Portal") to "<body><button>B</button></body>",
        )
        assertEquals(listOf("A", "BId"), StrataGenerator.generate(dir).pages.map { it.name })
    }

    @Test
    fun nameCollisionsGetNumbered(@TempDir tmp: Path) {
        val dir = tmp.resolve("r")
        writeRun(dir, record(1, "/a", "A") to "<body><a href='/1'>Next</a><a href='/2' title='x'>Next</a></body>")
        val names = StrataGenerator.generate(dir).pages.single().modules.single().elements.map { it.name }
        assertEquals(listOf("nextLink", "nextLink2"), names)
    }

    @Test
    fun droppedAndMergedAreLogged(@TempDir tmp: Path) {
        val dir = tmp.resolve("r")
        writeRun(dir, record(1, "/a", "A") to "<body><section><button>x</button></section></body>")
        StrataGenerator.generate(dir)
        assertTrue(dir.resolve(StrataGenerator.LOG).readText().contains("merged module <section>"))
    }

    @Test
    fun routesCarryTheHostOnlyWhenARunSpansSites(@TempDir tmp: Path) {
        val dir = tmp.resolve("r")
        writeRun(
            dir,
            record(1, "/", "One").copy(url = "https://www.one.test/") to "<body><button>A</button></body>",
            record(2, "/", "Two").copy(url = "https://two.test/") to "<body><button>B</button></body>",
        )
        val pages = StrataGenerator.generate(dir).pages
        assertEquals(listOf("two.test/", "www.one.test/"), pages.map { it.routeKey })
        assertEquals(listOf(1, 1), pages.map { it.visits })

        val single = tmp.resolve("s")
        writeRun(single, record(1, "/a", "A") to "<body><button>A</button></body>", record(2, "/b", "B") to "<body><button>B</button></body>")
        assertEquals(listOf("/a", "/b"), StrataGenerator.generate(single).pages.map { it.routeKey })
    }

    private fun failed(session: String, value: String, outcome: String, url: String = "http://h/a") =
        """{"sessionId":"$session","url":"$url","routeKey":"/a","command":"findElement","locatorUsing":"id",""" +
            """"locatorValue":"$value","outcome":"$outcome","recordedAt":"2026-11-08T12:00:00.000Z"}"""

    @Test
    fun failedLookupsDropRecoveredOnesAndAreWrittenSorted(@TempDir tmp: Path) {
        val dir = tmp.resolve("r")
        writeRun(dir, record(1, "/a", "A") to "<body><button>A</button></body>")
        Files.write(
            dir.resolve(StrataGenerator.FAILED_LOOKUPS),
            listOf(
                failed("s", "polled", "NOT_FOUND"),
                failed("s", "zed", "NOT_FOUND"),
                failed("s", "polled", "RECOVERED"),
                failed("t", "zed", "NOT_FOUND"),
                failed("t", "bad[", "INVALID_SELECTOR"),
            ),
        )
        val run = StrataGenerator.generate(dir)
        assertEquals(listOf("id: bad[", "id: zed"), run.failedLookups.map { it.locator }.sorted())
        val xml = DefaultCoverageXmlWriter.render(run)
        assertTrue(xml.indexOf("locator=\"id: bad[\" command=\"findElement\" reason=\"INVALID_SELECTOR\"") in 0 until xml.indexOf("locator=\"id: zed\""), xml)
    }

    @Test
    fun aRecoveryInOneSessionDoesNotClearAnotherSession(@TempDir tmp: Path) {
        val dir = tmp.resolve("r")
        writeRun(dir, record(1, "/a", "A") to "<body><button>A</button></body>")
        Files.write(
            dir.resolve(StrataGenerator.FAILED_LOOKUPS),
            listOf(failed("s", "x", "NOT_FOUND"), failed("s", "x", "RECOVERED"), failed("t", "x", "NOT_FOUND")),
        )
        assertEquals(listOf("id: x"), StrataGenerator.generate(dir).failedLookups.map { it.locator })
    }

    @Test
    fun summaryCountsOnlyInteractableEntriesAndBrokenPerPage(@TempDir tmp: Path) {
        val dir = tmp.resolve("r")
        writeRun(dir, record(1, "/a", "A") to "<body><h1>Title</h1><button>Go</button><a href='/x'>Next</a></body>")
        Files.write(dir.resolve("usage.jsonl"), listOf(
            """{"sessionId":"s","snapshotSeq":1,"routeKey":"/a","command":"findElement","locatorUsing":"id","locatorValue":"g",""" +
                """"framePath":[],"elementPath":[1,1],"tag":"button","usedAt":"2026-11-08T12:00:00.000Z"}""",
        ))
        Files.write(dir.resolve(StrataGenerator.FAILED_LOOKUPS), listOf(failed("s", "gone", "NOT_FOUND")))
        val summary = RunSummaries.of(StrataGenerator.generate(dir))
        assertEquals(2, summary.interactable)
        assertEquals(1, summary.covered)
        assertEquals(50, summary.coveredPercent)
        assertEquals(listOf("id: gone"), summary.broken.map { it.locator })
        assertEquals(listOf(PageSummary("A", "/a", 1, 2, 1, 1)), summary.pages)
    }

    @Test
    fun missingManifestYieldsEmptyRun(@TempDir tmp: Path) {
        assertEquals(emptyList(), StrataGenerator.generate(tmp).pages)
    }
}

class ManifestReaderTest {
    @Test
    fun parsesAgentLine() {
        val line = """{"seq":3,"sessionId":"s1","url":"http://h/a\"b","routeKey":"/a","title":"T\\itle\n",""" +
            """"trigger":"CONTAINER_CHANGE","containerHash":"abc","domFile":"dom/0003-a.dom.html",""" +
            """"capturedAt":"2026-11-06T22:33:40.123Z","settleMs":512,"skipped":["iframe#chart","shadow:x-grid"]}"""
        val r = ManifestReader.parseLine(line).getOrThrow()
        assertEquals(3, r.seq)
        assertEquals("http://h/a\"b", r.url)
        assertEquals("T\\itle\n", r.title)
        assertEquals(Trigger.CONTAINER_CHANGE, r.trigger)
        assertEquals(listOf("iframe#chart", "shadow:x-grid"), r.skipped)
    }

    @Test
    fun badLinesAreReportedNotThrown(@TempDir tmp: Path) {
        val f = tmp.resolve("manifest.jsonl")
        f.writeText("{not json}\n\n")
        val errors = mutableListOf<String>()
        assertEquals(emptyList(), ManifestReader.read(f) { errors += it })
        assertEquals(1, errors.size)
    }
}

/** §4.4 / §10.5: the snapshots the agent wrote for the fixture site carry no table data and no values. */
class RedactionContractTest {
    private val snapshots: List<Path> = Files.walk(fixtures.resolve("snapshots")).use { s ->
        s.filter { it.toString().endsWith(".dom.html") }.sorted().toList()
    }

    @Test
    fun snapshotsExist() {
        assertEquals(11, snapshots.size)
    }

    /** Only `···`, or an action label repeated across >= 2 rows (the §4.4 repeated-label exception). */
    @Test
    fun noTbodyTextSurvives() {
        for (file in snapshots) {
            val doc = Jsoup.parse(file.toFile(), "UTF-8")
            for (tbody in doc.select("tbody")) {
                for (el in tbody.select("*")) {
                    val own = el.ownText().trim()
                    val repeatedAction = el.`is`("button, a, [role=button], [role=link]") &&
                        tbody.select("tr").count { row -> row.select("button, a").any { it.text() == el.text() } } >= 2
                    assertTrue(own.isEmpty() || own == Texts.REDACTED || repeatedAction, "tbody text '$own' in $file")
                }
            }
        }
    }

    @Test
    fun optionAndContentTextIsRedacted() {
        for (file in snapshots) {
            val doc = Jsoup.parse(file.toFile(), "UTF-8")
            for (el in doc.select("option, [role=option], mat-option, p, li:not([role]), td")) {
                assertTrue(el.ownText().isBlank() || el.ownText().trim() == Texts.REDACTED, "'${el.ownText()}' in $file")
            }
        }
    }

    @Test
    fun noValueAttributeSurvives() {
        for (file in snapshots) {
            val html = file.readText()
            assertFalse(html.contains("value="), "value= in $file")
            val doc = Jsoup.parse(html)
            assertTrue(doc.select("[value]").isEmpty(), "value attribute in $file")
            assertTrue(doc.select("textarea").all { it.text().isEmpty() }, "textarea text in $file")
            assertTrue(doc.select("script, style, noscript").isEmpty(), "script/style in $file")
            assertTrue(doc.select("[style], [onclick]").isEmpty(), "style/on* in $file")
            assertTrue(doc.select("[row-id], [row-business-key], [role=gridcell][title], td[title]").isEmpty(), "row keys in $file")
        }
    }
}
