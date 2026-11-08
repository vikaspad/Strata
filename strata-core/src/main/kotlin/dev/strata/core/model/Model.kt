package dev.strata.core.model

/** One line of `manifest.jsonl`, written by the agent. Field names and order are a contract (CLAUDE.md §3.1). */
data class SnapshotRecord(
    val seq: Int,
    val sessionId: String,
    val url: String,
    val routeKey: String,
    val title: String,
    val trigger: Trigger,
    val containerHash: String,
    val domFile: String,
    val capturedAt: String,
    val settleMs: Int,
    val skipped: List<String> = emptyList(),
)

enum class Trigger { GET, CLICK, BACK, FORWARD, REFRESH, CONTAINER_CHANGE }

data class LocatorCoverageRun(
    val runId: String,
    val generatedAt: String,
    val pages: List<PageModel>,
)

data class PageModel(
    val name: String,
    val routeKey: String,
    val visits: Int,
    val modules: List<ModuleModel>,
)

data class ModuleModel(
    val name: String,
    val kind: ModuleKind,
    val xpath: String,
    val elements: List<XpathEntry>,
)

enum class ModuleKind { FORM, NAV, TABLE, DIALOG, SECTION, ANCHORED, PAGE_ROOT }

data class XpathEntry(
    val name: String,
    val type: ElementType,
    val value: String,
    val absolute: String,
    val strategy: Strategy,
    val matches: Int,
)

enum class ElementType { BUTTON, LINK, TEXT_INPUT, DROPDOWN, CHECKBOX, RADIO, TEXTAREA, TAB, OTHER_INTERACTIVE }

enum class Strategy { TESTID, ID, NAME, ARIA_LABEL, LABEL_FOR, TEXT, PLACEHOLDER, POSITIONAL }
