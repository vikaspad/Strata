package dev.strata.plugin

import com.intellij.execution.JavaRunConfigurationBase
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.impl.DefaultJavaProgramRunner

/** Runs Java run configurations (Application, JUnit, TestNG, Cucumber-Java) under the Strata executor. */
class LocatorCoverageRunner : DefaultJavaProgramRunner() {
    override fun getRunnerId(): String = "StrataLocatorCoverageRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == LocatorCoverageExecutor.ID && profile is JavaRunConfigurationBase
}
