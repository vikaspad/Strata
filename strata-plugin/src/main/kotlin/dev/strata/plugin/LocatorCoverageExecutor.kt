package dev.strata.plugin

import com.intellij.execution.Executor
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowId
import javax.swing.Icon

class LocatorCoverageExecutor : Executor() {
    override fun getToolWindowId(): String = ToolWindowId.RUN

    override fun getToolWindowIcon(): Icon = ICON

    override fun getIcon(): Icon = ICON

    override fun getDisabledIcon(): Icon = IconLoader.getDisabledIcon(ICON)

    override fun getDescription(): String = TEXT

    override fun getActionName(): String = "Locator Coverage"

    override fun getId(): String = ID

    override fun getStartActionText(): String = TEXT

    override fun getContextActionId(): String = "RunWithLocatorCoverage"

    override fun getHelpId(): String? = null

    companion object {
        const val ID: String = "StrataLocatorCoverage"
        const val TEXT: String = "Run with Locator Coverage"
        val ICON: Icon = IconLoader.getIcon("/icons/locatorCoverage.svg", LocatorCoverageExecutor::class.java)
    }
}
