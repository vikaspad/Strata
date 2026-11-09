package dev.strata.plugin

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CoverageToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CoveragePanel()
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
        val state = project.service<CoverageState>()
        state.subscribe(toolWindow.disposable, panel::show)
        val current = state.run
        if (current != null) panel.show(current) else state.loadLatest()
    }

    companion object {
        const val ID: String = "Locator Coverage"
    }
}
