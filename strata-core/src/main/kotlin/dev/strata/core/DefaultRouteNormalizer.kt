package dev.strata.core

/** CLAUDE.md §5.1. The agent carries a Java copy of these rules (`dev.strata.agent.RouteKeys`); keep them in sync. */
object DefaultRouteNormalizer : RouteNormalizer {
    private val idSegments: List<Regex> = listOf(
        Regex("^\\d+$"),
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-"),
        Regex("^[A-Z0-9]{8,}$"),
        Regex("^[0-9a-f]{16,}$"),
    )

    override fun normalize(url: String): String {
        var path = url.substringBefore('#').substringBefore('?')
        val scheme = path.indexOf("://")
        if (scheme >= 0) {
            val afterScheme = path.substring(scheme + 3)
            val slash = afterScheme.indexOf('/')
            path = if (slash >= 0) afterScheme.substring(slash) else "/"
        }
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return "/"
        return segments.joinToString(separator = "/", prefix = "/") { segment ->
            if (idSegments.any { it.containsMatchIn(segment) }) "{id}" else segment
        }
    }

    /** Lower-case `host[:port]` of a URL, or empty if it has no scheme. */
    fun host(url: String): String {
        if (!url.contains("://")) return ""
        return url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').lowercase()
    }

    /** `/blotter/{id}` → `blotter_id`; `/` → `root`. */
    fun fileSafe(routeKey: String): String {
        val safe = routeKey.trim('/')
            .replace("{", "").replace("}", "")
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
        return safe.ifEmpty { "root" }
    }
}
