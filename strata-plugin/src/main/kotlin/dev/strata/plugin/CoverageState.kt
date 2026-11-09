package dev.strata.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.strata.core.StrataGenerator
import dev.strata.core.model.LocatorCoverageRun
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/** The run the tool window shows. Published on the EDT. */
@Service(Service.Level.PROJECT)
class CoverageState(private val project: Project) {
    private val listeners = CopyOnWriteArrayList<(LocatorCoverageRun) -> Unit>()

    @Volatile
    var run: LocatorCoverageRun? = null
        private set

    fun publish(newRun: LocatorCoverageRun) {
        run = newRun
        listeners.forEach { it(newRun) }
    }

    fun subscribe(parent: Disposable, listener: (LocatorCoverageRun) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /** After an IDE restart: rebuilds the model of the newest run, off the EDT. A run that finishes meanwhile wins. */
    fun loadLatest() {
        val base = project.basePath ?: return
        val dir = latestRunDir(Path.of(base)) ?: return
        object : Task.Backgroundable(project, "Loading locator coverage", false) {
            private var loaded: LocatorCoverageRun? = null

            override fun run(indicator: ProgressIndicator) {
                loaded = StrataGenerator.generate(dir)
            }

            override fun onSuccess() {
                val result = loaded
                if (result != null && run == null && !project.isDisposed) publish(result)
            }

            override fun onThrowable(error: Throwable) {
                LOG.warn("Cannot load locator coverage from $dir", error)
            }
        }.queue()
    }

    companion object {
        private val LOG = logger<CoverageState>()

        /** The most recently written run under `<project>/.strata/runs`, or null if there is none. */
        fun latestRunDir(projectDir: Path): Path? {
            val runs = projectDir.resolve(".strata/runs")
            if (!runs.isDirectory()) return null
            return try {
                runs.listDirectoryEntries()
                    .filter { Files.isRegularFile(it.resolve(StrataGenerator.MANIFEST)) }
                    .maxByOrNull { Files.getLastModifiedTime(it.resolve(StrataGenerator.MANIFEST)) }
            } catch (e: IOException) {
                LOG.warn("Cannot list $runs", e)
                null
            }
        }
    }
}
