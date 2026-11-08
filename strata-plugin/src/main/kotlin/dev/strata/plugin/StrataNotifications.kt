package dev.strata.plugin

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

object StrataNotifications {
    private const val GROUP = "Strata"
    private const val TITLE = "Locator Coverage"

    fun error(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(TITLE, message, NotificationType.ERROR)
            .notify(project)
    }

    fun warning(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(TITLE, message, NotificationType.WARNING)
            .notify(project)
    }

    fun generated(project: Project, pages: Int, elements: Int, used: Int, xml: VirtualFile) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(TITLE, summary(pages, elements, used), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Open XML") {
                FileEditorManager.getInstance(project).openFile(xml, true)
            })
            .notify(project)
    }

    /** "2 pages, 26 elements, 9 used (35%)". */
    fun summary(pages: Int, elements: Int, used: Int): String {
        val percent = if (elements == 0) 0 else Math.round(used * 100.0 / elements).toInt()
        return "${plural(pages, "page")}, ${plural(elements, "element")}, $used used ($percent%)"
    }

    private fun plural(n: Int, word: String): String = if (n == 1) "1 $word" else "$n ${word}s"
}
