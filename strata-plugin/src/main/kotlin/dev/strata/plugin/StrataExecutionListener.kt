package dev.strata.plugin

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder

/** Attaches [PostRunHandler] to processes started by the Strata executor, before they can terminate. */
class StrataExecutionListener(private val project: Project) : ExecutionListener {
    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (executorId != LocatorCoverageExecutor.ID) return
        val runDir = (env.runProfile as? UserDataHolder)?.getUserData(AgentPatcher.RUN_DIR) ?: return
        handler.addProcessListener(PostRunHandler(project, runDir))
    }
}
