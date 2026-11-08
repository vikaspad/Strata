package dev.strata.core

import dev.strata.core.model.FrameRef
import dev.strata.core.model.SnapshotRecord
import dev.strata.core.model.Trigger
import dev.strata.core.model.UsageRecord
import java.nio.file.Files
import java.nio.file.Path

/** Reads `manifest.jsonl` and `usage.jsonl`. Lines that do not parse are reported through [onError] and skipped. */
object ManifestReader {

    fun read(manifest: Path, onError: (String) -> Unit = {}): List<SnapshotRecord> = lines(manifest, onError, ::parseLine)

    fun readUsage(usage: Path, onError: (String) -> Unit = {}): List<UsageRecord> = lines(usage, onError, ::parseUsage)

    private fun <T> lines(file: Path, onError: (String) -> Unit, parse: (String) -> Result<T>): List<T> {
        if (!Files.isRegularFile(file)) return emptyList()
        return Files.readAllLines(file, Charsets.UTF_8).mapIndexedNotNull { index, line ->
            if (line.isBlank()) return@mapIndexedNotNull null
            parse(line).onFailure { onError("${file.fileName} line ${index + 1}: ${it.message}") }.getOrNull()
        }
    }

    fun parseUsage(line: String): Result<UsageRecord> = runCatching {
        val obj = Json(line).parseObject()
        UsageRecord(
            sessionId = obj.string("sessionId"),
            snapshotSeq = obj.int("snapshotSeq"),
            routeKey = obj.string("routeKey"),
            command = obj.string("command"),
            locatorUsing = obj["locatorUsing"] as? String,
            locatorValue = obj["locatorValue"] as? String,
            framePath = obj.ints("framePath"),
            elementPath = obj.ints("elementPath"),
            tag = obj.string("tag"),
            usedAt = obj.string("usedAt"),
        )
    }

    fun parseLine(line: String): Result<SnapshotRecord> = runCatching {
        val obj = Json(line).parseObject()
        SnapshotRecord(
            seq = obj.int("seq"),
            sessionId = obj.string("sessionId"),
            url = obj.string("url"),
            routeKey = obj.string("routeKey"),
            title = obj.string("title"),
            trigger = Trigger.valueOf(obj.string("trigger")),
            containerHash = obj.string("containerHash"),
            domFile = obj.string("domFile"),
            capturedAt = obj.string("capturedAt"),
            settleMs = obj.int("settleMs"),
            skipped = (obj["skipped"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            frames = (obj["frames"] as? List<*>)?.map { f ->
                @Suppress("UNCHECKED_CAST")
                val frame = f as? Map<String, Any?> ?: throw IllegalArgumentException("bad frame")
                FrameRef(index = frame.int("index"), url = frame.string("url"), domFile = frame.string("domFile"))
            } ?: emptyList(),
        )
    }

    private fun Map<String, Any?>.ints(key: String): List<Int> =
        (this[key] as? List<*>)?.map { (it as? Number)?.toInt() ?: throw IllegalArgumentException("bad '$key'") }
            ?: throw IllegalArgumentException("missing list '$key'")

    private fun Map<String, Any?>.string(key: String): String =
        this[key] as? String ?: throw IllegalArgumentException("missing string '$key'")

    private fun Map<String, Any?>.int(key: String): Int =
        (this[key] as? Number)?.toInt() ?: throw IllegalArgumentException("missing number '$key'")

    /** Minimal JSON reader: objects, arrays, strings, numbers, booleans, null. */
    private class Json(private val s: String) {
        private var i = 0

        fun parseObject(): Map<String, Any?> {
            val value = parseValue()
            skipWs()
            require(i == s.length) { "trailing characters at $i" }
            @Suppress("UNCHECKED_CAST")
            return value as? Map<String, Any?> ?: throw IllegalArgumentException("not an object")
        }

        private fun parseValue(): Any? {
            skipWs()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> parseMap()
                '[' -> parseList()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else throw IllegalArgumentException("bad char '$c' at $i")
            }
        }

        private fun parseMap(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                out[key] = parseValue()
                skipWs()
                if (peek() == ',') {
                    i++
                    continue
                }
                expect('}')
                return out
            }
        }

        private fun parseList(): List<Any?> {
            val out = mutableListOf<Any?>()
            i++
            skipWs()
            if (peek() == ']') {
                i++
                return out
            }
            while (true) {
                out += parseValue()
                skipWs()
                if (peek() == ',') {
                    i++
                    continue
                }
                expect(']')
                return out
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("bad escape '\\$e'")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return value
        }

        private fun peek(): Char? = s.getOrNull(i)

        private fun expect(c: Char) {
            require(peek() == c) { "expected '$c' at $i" }
            i++
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
