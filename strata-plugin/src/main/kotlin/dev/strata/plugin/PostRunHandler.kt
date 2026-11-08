package dev.strata.plugin

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.strata.core.DefaultCoverageXmlWriter
import dev.strata.core.StrataGenerator
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * When the test process ends: generate the coverage model off the EDT, write `<runDir>/locator-coverage.xml`,
 * copy it to `<project>/.strata/locator-coverage.xml` through the VFS, and notify.
 */
class PostRunHandler(private val project: Project, private val runDir: Path) : ProcessListener {

    override fun processTerminated(event: ProcessEvent) {
        object : Task.Backgroundable(project, "Generating locator coverage", false) {
            private var result: Generated? = null

            override fun run(indicator: ProgressIndicator) {
                result = generate(runDir)
            }

            override fun onSuccess() {
                val generated = result
                if (project.isDisposed) return
                if (generated == null) {
                    StrataNotifications.warning(project, "No snapshots were captured. See ${runDir.resolve("agent.log")}")
                    return
                }
                val xml = publish(generated.xml) ?: return
                StrataNotifications.generated(project, generated.pages, generated.elements, xml)
            }

            override fun onThrowable(error: Throwable) {
                LOG.warn("Locator coverage generation failed for $runDir", error)
                if (!project.isDisposed) StrataNotifications.error(project, "Generation failed: ${error.message}")
            }
        }.queue()
    }

    private class Generated(val xml: ByteArray, val pages: Int, val elements: Int)

    private fun generate(runDir: Path): Generated? {
        if (!Files.isRegularFile(runDir.resolve(StrataGenerator.MANIFEST))) return null
        val run = StrataGenerator.generate(runDir)
        val out = runDir.resolve(XML_NAME)
        DefaultCoverageXmlWriter.write(run, out)
        return Generated(
            xml = Files.readAllBytes(out),
            pages = run.pages.size,
            elements = run.pages.sumOf { page -> page.modules.sumOf { it.elements.size } },
        )
    }

    /** EDT: copies the XML to `.strata/` with a write action and refreshes the run dir. */
    private fun publish(bytes: ByteArray): VirtualFile? {
        val base = project.basePath ?: return null
        return try {
            val target = WriteAction.compute<VirtualFile, IOException> {
                val strataDir = VfsUtil.createDirectoryIfMissing("$base/.strata")
                    ?: throw IOException("cannot create $base/.strata")
                val file = strataDir.findChild(XML_NAME) ?: strataDir.createChildData(this, XML_NAME)
                file.setBinaryContent(bytes)
                file
            }
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(runDir)?.refresh(true, true)
            target
        } catch (e: IOException) {
            LOG.warn("Cannot write .strata/$XML_NAME", e)
            StrataNotifications.error(project, "Cannot write .strata/$XML_NAME: ${e.message}")
            null
        }
    }

    companion object {
        const val XML_NAME: String = "locator-coverage.xml"
        private val LOG = logger<PostRunHandler>()
    }
}
