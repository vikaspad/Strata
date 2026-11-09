package dev.strata.core

import dev.strata.core.model.ElementType
import dev.strata.core.model.FailedLookup
import dev.strata.core.model.LocatorCoverageRun

data class PageSummary(
    val name: String,
    val routeKey: String,
    val visits: Int,
    val interactable: Int,
    val covered: Int,
    val broken: Int,
)

/** The numbers the tool window shows (CLAUDE.md §7). A template counts as one element, as in the XML. */
data class RunSummary(
    val pages: List<PageSummary>,
    val interactable: Int,
    val covered: Int,
    val broken: List<FailedLookup>,
) {
    val coveredPercent: Int get() = percent(covered, interactable)
}

object RunSummaries {
    /** Controls a test can act on; structure and content such as headings and table parts are excluded. */
    val INTERACTABLE_TYPES: Set<ElementType> = setOf(
        ElementType.BUTTON, ElementType.LINK, ElementType.TEXT_INPUT, ElementType.PASSWORD, ElementType.DROPDOWN,
        ElementType.OPTION, ElementType.CHECKBOX, ElementType.RADIO, ElementType.TEXTAREA, ElementType.TAB,
        ElementType.OTHER_INTERACTIVE,
    )

    fun of(run: LocatorCoverageRun): RunSummary {
        val brokenByRoute = run.failedLookups.groupingBy { it.routeKey }.eachCount()
        val pages = run.pages.sortedBy { it.routeKey }.map { page ->
            val interactable = (page.modules + page.frames.flatMap { it.modules }).flatMap { it.elements }
                .filter { it.type in INTERACTABLE_TYPES }
            PageSummary(
                name = page.name,
                routeKey = page.routeKey,
                visits = page.visits,
                interactable = interactable.size,
                covered = interactable.count { it.used },
                broken = brokenByRoute[page.routeKey] ?: 0,
            )
        }
        return RunSummary(
            pages = pages,
            interactable = pages.sumOf { it.interactable },
            covered = pages.sumOf { it.covered },
            broken = run.failedLookups.sortedWith(compareBy({ it.routeKey }, { it.locator }, { it.command }, { it.reason })),
        )
    }
}

fun percent(part: Int, whole: Int): Int = if (whole == 0) 0 else Math.round(part * 100.0 / whole).toInt()
