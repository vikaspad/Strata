package dev.strata.plugin

import com.intellij.execution.CantRunException
import com.intellij.execution.Executor
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import javax.swing.Icon

class AgentPatcherTest : BasePlatformTestCase() {

    private val strataExecutor: Executor
        get() = Executor.EXECUTOR_EXTENSION_NAME.findExtensionOrFail(LocatorCoverageExecutor::class.java)

    private fun jdk(version: String): Sdk {
        val sdk = ProjectJdkTable.getInstance().createSdk("jdk-$version", JavaSdk.getInstance())
        val modificator = sdk.sdkModificator
        modificator.versionString = "java version \"$version\""
        WriteAction.runAndWait<RuntimeException> { modificator.commitChanges() }
        return sdk
    }

    private fun params(version: String): JavaParameters = JavaParameters().apply { jdk = jdk(version) }

    private fun config(): ApplicationConfiguration = ApplicationConfiguration("Blotter", project)

    fun testDetectsJdkFeatureVersion() {
        assertEquals(17, AgentPatcher.featureVersion(jdk("17.0.1")))
        assertEquals(21, AgentPatcher.featureVersion(jdk("21.0.9")))
        assertEquals(11, AgentPatcher.featureVersion(jdk("11.0.2")))
        assertNull(AgentPatcher.featureVersion(null))
    }

    fun testAddsArgsOnlyForStrataExecutor() {
        val runParams = params("21.0.9")
        AgentPatcher().patchJavaParameters(DefaultRunExecutor.getRunExecutorInstance(), config(), runParams)
        assertTrue(runParams.vmParametersList.list.isEmpty())

        val strataParams = params("21.0.9")
        val config = config()
        AgentPatcher().patchJavaParameters(strataExecutor, config, strataParams)
        val vm = strataParams.vmParametersList.list
        val agentArg = vm.single { it.startsWith("-javaagent:") }
        assertTrue(agentArg, agentArg.endsWith("strata-agent.jar"))
        assertTrue(Files.isRegularFile(Path.of(agentArg.removePrefix("-javaagent:"))))
        val out = strataParams.vmParametersList.getPropertyValue("strata.out")!!
        assertTrue(out, Path.of(out).startsWith(Path.of(project.basePath!!, ".strata", "runs")))
        assertEquals(Path.of(out), config.getUserData(AgentPatcher.RUN_DIR))
    }

    fun testRunnerAttachesAgentOnce() {
        val p = params("21.0.9")
        val config = config()
        val runner = LocatorCoverageRunner()
        runner.patch(p, null, config, true)
        runner.patch(p, null, config, true)
        assertEquals(1, p.vmParametersList.list.count { it.startsWith("-javaagent:") })
        assertEquals(Path.of(p.vmParametersList.getPropertyValue("strata.out")!!), config.getUserData(AgentPatcher.RUN_DIR))
    }

    fun testAcceptsJava17() {
        val p = params("17.0.1")
        AgentPatcher().patchJavaParameters(strataExecutor, config(), p)
        assertTrue(p.vmParametersList.list.any { it.startsWith("-javaagent:") })
    }

    fun testRefusesJreBelow17() {
        val p = params("11.0.2")
        try {
            AgentPatcher().patchJavaParameters(strataExecutor, config(), p)
            fail("expected the launch to be refused")
        } catch (e: CantRunException) {
            assertEquals("Locator Coverage requires Java 17+ (configured: 11)", e.message)
        }
        assertTrue(p.vmParametersList.list.isEmpty())
    }

    fun testRefusalMessage() {
        assertEquals("Locator Coverage requires Java 17+ (configured: 8)", AgentPatcher.refusal(8))
        assertNull(AgentPatcher.refusal(17))
        assertNull(AgentPatcher.refusal(21))
        assertNull(AgentPatcher.refusal(null))
    }

    fun testRunIdFormatAndCollision() {
        val dir = Files.createTempDirectory("strata-runs")
        val now = LocalDateTime.of(2026, 11, 6, 22, 30, 12)
        val first = AgentPatcher.newRunDir(dir, now)
        assertEquals(dir.resolve(".strata/runs/20261106-223012"), first)
        Files.createDirectories(first)
        assertEquals(dir.resolve(".strata/runs/20261106-223012-2"), AgentPatcher.newRunDir(dir, now))
    }

    fun testCanRunOnlyJavaConfigsUnderStrata() {
        val runner = LocatorCoverageRunner()
        assertTrue(runner.canRun(LocatorCoverageExecutor.ID, config()))
        assertFalse(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, config()))
        val notJava = object : RunProfile {
            override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null
            override fun getName(): String = "shell"
            override fun getIcon(): Icon? = null
        }
        assertFalse(runner.canRun(LocatorCoverageExecutor.ID, notJava))
    }

    fun testNotificationSummary() {
        assertEquals("2 pages, 26 elements, 9 used (35%)", StrataNotifications.summary(2, 26, 9))
        assertEquals("1 page, 1 element, 0 used (0%)", StrataNotifications.summary(1, 1, 0))
        assertEquals("0 pages, 0 elements, 0 used (0%)", StrataNotifications.summary(0, 0, 0))
    }

    fun testExecutorIsRegistered() {
        assertEquals("StrataLocatorCoverage", strataExecutor.id)
        assertEquals("Run with Locator Coverage", strataExecutor.startActionText)
    }
}
