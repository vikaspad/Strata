package dev.strata.core

/** XPath 1.0 string literal with `'` quoting; text containing `'` becomes a `concat()`. */
fun xpathLiteral(text: String): String {
    if (!text.contains('\'')) return "'$text'"
    val parts = text.split('\'')
    val pieces = mutableListOf<String>()
    parts.forEachIndexed { index, part ->
        if (part.isNotEmpty()) pieces += "'$part'"
        if (index < parts.lastIndex) pieces += "\"'\""
    }
    return "concat(${pieces.joinToString(", ")})"
}
