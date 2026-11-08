package dev.strata.core

/** Deterministic naming rules (CLAUDE.md §5.4). */
object Names {
    private const val MAX_WORDS = 6

    fun words(source: String): List<String> {
        val spaced = source
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
        return spaced.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .take(MAX_WORDS)
    }

    fun pascal(source: String): String {
        val joined = words(source).joinToString("") { word ->
            word.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
        return if (joined.firstOrNull()?.isDigit() == true) "N$joined" else joined
    }

    fun camel(source: String): String = pascal(source).replaceFirstChar { it.lowercaseChar() }

    /** Appends [suffix] unless the name already ends with it; a name that is only the suffix stays as given. */
    fun withSuffix(name: String, suffix: String): String = when {
        name.equals(suffix, ignoreCase = true) -> name
        name.endsWith(suffix, ignoreCase = true) -> name.dropLast(suffix.length) + suffix
        else -> name + suffix
    }

    /** `a, a, b, a` → `a, a2, b, a3`. */
    fun dedupe(names: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return names.map { name ->
            var candidate = name
            var n = 2
            while (!used.add(candidate)) candidate = name + n++
            candidate
        }
    }
}
