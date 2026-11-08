package dev.strata.core

/** CLAUDE.md §5.5. Dynamic ids never appear in any emitted XPath. */
object DynamicIds {
    private val patterns: List<Regex> = listOf(
        Regex("\\d{4,}"),
        Regex("[0-9a-f]{8}-"),
        Regex("^ember\\d+"),
        Regex("^mat-[a-z-]+-\\d+"),
        Regex("^react-select-\\d+"),
        Regex("^cdk-"),
        Regex("^:r[0-9a-z]+:$"),
        Regex("^ext-gen\\d+"),
        Regex("^ui-id-\\d+"),
        Regex("^ag-\\d+-"),
    )

    fun isDynamic(id: String): Boolean = patterns.any { it.containsMatchIn(id) }

    fun isStable(id: String?): Boolean = !id.isNullOrBlank() && !isDynamic(id)

    /** Every quoted literal inside `@id=` predicates of [xpath]. Used by tests to assert §10.6. */
    fun idLiterals(xpath: String): List<String> =
        Regex("@id\\s*=\\s*'([^']*)'").findAll(xpath).map { it.groupValues[1] }.toList()
}
