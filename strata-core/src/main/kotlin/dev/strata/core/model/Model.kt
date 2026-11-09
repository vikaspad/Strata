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
    val frames: List<FrameRef> = emptyList(),
)

/** A same-origin frame captured with its parent snapshot. [index] is the frame's position among `iframe, frame`. */
data class FrameRef(
    val index: Int,
    val url: String,
    val domFile: String,
)

enum class Trigger { GET, CLICK, BACK, FORWARD, REFRESH, CONTAINER_CHANGE }

/** One line of `usage.jsonl`: the first time a test looked up or interacted with an element (CLAUDE.md §3.1). */
data class UsageRecord(
    val sessionId: String,
    val snapshotSeq: Int,
    val routeKey: String,
    val command: String,
    val locatorUsing: String?,
    val locatorValue: String?,
    val framePath: List<Int>,
    val elementPath: List<Int>,
    val tag: String,
    val usedAt: String,
)

data class LocatorCoverageRun(
    val runId: String,
    val generatedAt: String,
    val pages: List<PageModel>,
    val unmatched: List<UnmatchedUsage> = emptyList(),
    val failedLookups: List<FailedLookup> = emptyList(),
)

data class PageModel(
    val name: String,
    val routeKey: String,
    val visits: Int,
    val modules: List<ModuleModel>,
    val frames: List<FrameModel> = emptyList(),
    val omitted: List<OmittedCount> = emptyList(),
)

/** Content of a same-origin frame. [xpath] locates the `<iframe>` in its page; switch into it before using [modules]. */
data class FrameModel(
    val name: String,
    val xpath: String,
    val routeKey: String,
    val modules: List<ModuleModel>,
)

data class ModuleModel(
    val name: String,
    val kind: ModuleKind,
    val xpath: String,
    val elements: List<XpathEntry>,
)

enum class ModuleKind { FORM, NAV, TABLE, GRID, DIALOG, SECTION, LANDMARK, GROUP, MENU, ANCHORED, PAGE_ROOT }

data class XpathEntry(
    val name: String,
    val type: ElementType,
    val value: String,
    val absolute: String,
    val strategy: Strategy,
    /** 1 for every emitted entry; for a template, 1 per instance. */
    val matches: Int,
    val stability: Stability = Stability.of(strategy, value),
    val used: Boolean = false,
    /** How many instances a template verified against; 1 for plain entries. */
    val instances: Int = 1,
) {
    /**
     * A template's value and absolute hold one `%s` for the dynamic part: the 1-based row, item or card number, or
     * the option text (§5.9). A literal `%` is written `%%`, so `String.format(absolute, x)` is always safe.
     */
    val isTemplate: Boolean get() = strategy == Strategy.ROW_TEMPLATE || strategy == Strategy.TEXT_TEMPLATE

    /** [absolute] with the dynamic part filled in; a plain entry's absolute as is. */
    fun absoluteFor(arg: Any): String = if (isTemplate) String.format(java.util.Locale.ROOT, absolute, arg) else absolute
}

enum class ElementType {
    BUTTON, LINK, TEXT_INPUT, PASSWORD, DROPDOWN, OPTION, CHECKBOX, RADIO, TEXTAREA, TAB, ICON,
    TABLE, TABLE_ROW, TABLE_HEADER, TABLE_CELL, HEADING, LABEL, PARAGRAPH, SPAN, DIV, LIST, LIST_ITEM, IFRAME,
    OTHER_INTERACTIVE,
}

enum class Strategy {
    TESTID, ID, NAME, ARIA_LABEL, LABEL_FOR, TEXT, PLACEHOLDER, ATTRIBUTE, CLASS,
    ROW_TEMPLATE, TEXT_TEMPLATE, STRUCTURE, POSITIONAL,
}

enum class Stability {
    HIGH, MEDIUM, LOW;

    companion object {
        /** CLAUDE.md §5.8. */
        fun of(strategy: Strategy, value: String): Stability = when {
            value.contains("following::") -> LOW
            strategy == Strategy.TESTID || strategy == Strategy.ID || strategy == Strategy.NAME -> HIGH
            strategy == Strategy.TEXT || strategy == Strategy.POSITIONAL -> LOW
            // A template whose column or item is found by position rather than by header text.
            (strategy == Strategy.ROW_TEMPLATE || strategy == Strategy.STRUCTURE) && POSITIONAL_STEP.containsMatchIn(value) -> LOW
            else -> MEDIUM
        }

        /** An index step such as `td[4]`; `ancestor::table[1]` (the nearest table) is not positional. */
        private val POSITIONAL_STEP = Regex("(?<!ancestor::table)\\[\\d+]")
    }
}

data class OmittedCount(val type: ElementType, val count: Int)

data class UnmatchedUsage(
    val routeKey: String,
    val locator: String,
    val command: String,
    val reason: UnmatchedReason,
)

enum class UnmatchedReason { NO_SNAPSHOT, STATE_NOT_CAPTURED, NOT_ADDRESSABLE }

/** One line of `failed-lookups.jsonl`, written by the agent. Field names and order are a contract (CLAUDE.md §3.1). */
data class FailedLookupRecord(
    val sessionId: String,
    val url: String,
    val routeKey: String,
    val command: String,
    val locatorUsing: String,
    val locatorValue: String,
    val outcome: LookupOutcome,
    val recordedAt: String,
)

/** RECOVERED retracts an earlier failure of the same locator in the same session: a wait polled, then found it. */
enum class LookupOutcome { NOT_FOUND, INVALID_SELECTOR, RECOVERED }

/** A locator a test used that never found anything in its session. */
data class FailedLookup(
    val routeKey: String,
    val locator: String,
    val command: String,
    val reason: LookupOutcome,
)
