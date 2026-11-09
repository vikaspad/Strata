package dev.strata.plugin

import com.intellij.execution.JavaRunConfigurationBase
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.impl.DefaultJavaProgramRunner

/** Runs Java run configurations (Application, JUnit, TestNG, Cucumber-Java) under the Strata executor. */
class LocatorCoverageRunner : DefaultJavaProgramRunner() {
    override fun getRunnerId(): String = "StrataLocatorCoverageRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == LocatorCoverageExecutor.ID && profile is JavaRunConfigurationBase

    /** The base class hands programPatcher extensions the Run executor, so [AgentPatcher] can't recognise this run there. */
    override fun patch(javaParameters: JavaParameters, settings: RunnerSettings?, runProfile: RunProfile, beforeExecution: Boolean) {
        super.patch(javaParameters, settings, runProfile, beforeExecution)
        if (beforeExecution) AgentPatcher().attach(runProfile, javaParameters)
    }
}
