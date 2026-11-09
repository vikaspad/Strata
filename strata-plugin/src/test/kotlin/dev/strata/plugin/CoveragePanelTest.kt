package dev.strata.plugin

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.strata.core.model.ElementType
import dev.strata.core.model.FailedLookup
import dev.strata.core.model.LocatorCoverageRun
import dev.strata.core.model.LookupOutcome
import dev.strata.core.model.ModuleKind
import dev.strata.core.model.ModuleModel
import dev.strata.core.model.PageModel
import dev.strata.core.model.Strategy
import dev.strata.core.model.XpathEntry
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class CoveragePanelTest : BasePlatformTestCase() {
    private fun entry(type: ElementType, used: Boolean) =
        XpathEntry("n", type, "./x", "//x", Strategy.ID, 1, used = used)

    private fun sampleRun() = LocatorCoverageRun(
        runId = "r",
        generatedAt = "t",
        pages = listOf(
            PageModel(
                "Login", "/login", 1,
                listOf(ModuleModel("F", ModuleKind.FORM, "//form", listOf(
                    entry(ElementType.BUTTON, true),
                    entry(ElementType.TEXT_INPUT, false),
                    entry(ElementType.HEADING, true),
                ))),
            ),
        ),
        failedLookups = listOf(FailedLookup("/login", "id: gone", "findElement", LookupOutcome.NOT_FOUND)),
    )

    fun testShowsTheFourTilesAndTables() {
        val panel = CoveragePanel()
        panel.show(sampleRun())
        assertEquals(listOf("1", "2", "1 (50%)", "1"), panel.tileValues())
        assertEquals(1, panel.pageRowCount())
        assertEquals(1, panel.brokenRowCount())
    }

    fun testASecondRunReplacesTheRows() {
        val panel = CoveragePanel()
        panel.show(sampleRun())
        panel.show(LocatorCoverageRun("r2", "t", emptyList()))
        assertEquals(listOf("0", "0", "0 (0%)", "0"), panel.tileValues())
        assertEquals(0, panel.pageRowCount())
        assertEquals(0, panel.brokenRowCount())
    }

    fun testListenersReceiveRunsUntilDisposed() {
        val state = project.service<CoverageState>()
        val parent = Disposer.newDisposable(testRootDisposable, "panel")
        val seen = mutableListOf<String>()
        state.subscribe(parent) { seen += it.runId }
        state.publish(sampleRun())
        Disposer.dispose(parent)
        state.publish(LocatorCoverageRun("later", "t", emptyList()))
        assertEquals(listOf("r"), seen)
        assertEquals("later", state.run?.runId)
    }

    fun testLatestRunDirIsTheNewestRunWithAManifest() {
        val dir = Files.createTempDirectory("strata-latest")
        assertNull(CoverageState.latestRunDir(dir))
        val old = Files.createDirectories(dir.resolve(".strata/runs/old"))
        val new = Files.createDirectories(dir.resolve(".strata/runs/new"))
        Files.createDirectories(dir.resolve(".strata/runs/empty"))
        val oldManifest = Files.writeString(old.resolve("manifest.jsonl"), "")
        val newManifest = Files.writeString(new.resolve("manifest.jsonl"), "")
        Files.setLastModifiedTime(oldManifest, FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(newManifest, FileTime.fromMillis(2_000))
        assertEquals(new, CoverageState.latestRunDir(dir))
    }
}
