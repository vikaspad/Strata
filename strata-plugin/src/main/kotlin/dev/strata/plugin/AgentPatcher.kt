package dev.strata.plugin

import com.intellij.execution.CantRunException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.JavaProgramPatcher
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Key
import com.intellij.util.lang.JavaVersion
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * For the Strata executor only: refuses JREs below 17 (the agent is Java 17 bytecode), otherwise attaches
 * `-javaagent:<bundled jar>` and `-Dstrata.out=<project>/.strata/runs/<runId>`.
 */
class AgentPatcher : JavaProgramPatcher() {

    override fun patchJavaParameters(executor: Executor, configuration: RunProfile, javaParameters: JavaParameters) {
        if (executor.id != LocatorCoverageExecutor.ID) return
        attach(configuration, javaParameters)
    }

    /**
     * Adds the agent arguments; a no-op if they are already there. [LocatorCoverageRunner] calls this directly,
     * because `DefaultJavaProgramRunner.patch` always reports the Run executor to programPatcher extensions.
     */
    fun attach(configuration: RunProfile, javaParameters: JavaParameters) {
        if (javaParameters.vmParametersList.hasProperty("strata.out")) return
        val config = configuration as? RunConfigurationBase<*> ?: return
        val project = config.project

        val feature = featureVersion(javaParameters.jdk)
        refusal(feature)?.let { message ->
            StrataNotifications.error(project, message)
            throw CantRunException(message)
        }
        val agentJar = agentJar() ?: run {
            val message = "Locator Coverage agent jar is missing from the Strata plugin installation"
            StrataNotifications.error(project, message)
            throw CantRunException(message)
        }
        val base = project.basePath ?: throw CantRunException("Locator Coverage needs a project directory")

        val runDir = newRunDir(Path.of(base))
        javaParameters.vmParametersList.add("-javaagent:$agentJar")
        javaParameters.vmParametersList.addProperty("strata.out", runDir.toString())
        config.putUserData(RUN_DIR, runDir)
    }

    companion object {
        const val MIN_JAVA: Int = 17
        val RUN_DIR: Key<Path> = Key.create("strata.runDir")
        private val RUN_ID: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** The error to show, or null when [feature] can run the agent. An unknown version is allowed through. */
        fun refusal(feature: Int?): String? =
            if (feature != null && feature < MIN_JAVA) "Locator Coverage requires Java $MIN_JAVA+ (configured: $feature)" else null

        fun featureVersion(sdk: Sdk?): Int? {
            if (sdk == null) return null
            val known = (sdk.sdkType as? JavaSdk)?.getVersion(sdk)
            if (known != null) return known.maxLanguageLevel.toJavaVersion().feature
            return sdk.versionString?.let { JavaVersion.tryParse(it)?.feature }
        }

        fun agentJar(): Path? {
            val plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.strata")) ?: return null
            return plugin.pluginPath.resolve("lib").resolve("strata-agent.jar").takeIf { Files.isRegularFile(it) }
        }

        /** `<project>/.strata/runs/<yyyyMMdd-HHmmss>`, suffixed `-2`, `-3`… if a run already used that second. */
        fun newRunDir(projectDir: Path, now: LocalDateTime = LocalDateTime.now()): Path {
            val runs = projectDir.resolve(".strata").resolve("runs")
            val id = RUN_ID.format(now)
            var candidate = runs.resolve(id)
            var n = 2
            while (Files.exists(candidate)) candidate = runs.resolve("$id-${n++}")
            return candidate
        }
    }
}
