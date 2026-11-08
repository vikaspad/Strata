package dev.strata.core

import dev.strata.core.model.SnapshotRecord
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
                    assertTrue(x.value.startsWith(".//") || x.value.startsWith("(.//"), x.value)
                    assertTrue(docs.any { verifier.count(it, x.absolute) == 1 }, "does not re-verify: ${x.absolute}")
                    DynamicIds.idLiterals(x.absolute).forEach { assertFalse(DynamicIds.isDynamic(it), x.absolute) }
                }
            }
        }
    }

    @Test
    fun pagesAreSortedByRouteAndNamedByUniqueTitle(@TempDir tmp: Path) {
        val run = StrataGenerator.generate(copyRun(tmp))
        assertEquals(listOf("/blotter/{id}", "/login"), run.pages.map { it.routeKey })
        assertEquals(listOf("TradeBlotter", "Login"), run.pages.map { it.name })
        assertEquals("20261107-120000", run.runId)
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
        assertEquals(4, snapshots.size)
    }

    @Test
    fun noTbodyTextSurvives() {
        for (file in snapshots) {
            val doc = Jsoup.parse(file.toFile(), "UTF-8")
            for (el in doc.select("tbody, tbody *")) {
                val own = el.ownText().trim()
                assertTrue(own.isEmpty() || own == Texts.REDACTED, "tbody text '$own' in $file")
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
        }
    }
}
