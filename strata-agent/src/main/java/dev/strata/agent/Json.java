package dev.strata.agent;

import java.util.List;

/** Hand-written JSON for {@code manifest.jsonl}; the agent carries no JSON library. */
final class Json {
    private Json() {
    }

    static String quote(final String s) {
        final StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    static String quoteOrNull(final String s) {
        return s == null ? "null" : quote(s);
    }

    static String ints(final List<Integer> values) {
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.append(']').toString();
    }

    static String array(final List<String> values) {
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(values.get(i)));
        }
        return sb.append(']').toString();
    }

    /** One SnapshotRecord; field names and order are fixed by CLAUDE.md §3.1. */
    static String snapshotRecord(final SnapshotLine r) {
        return "{\"seq\":" + r.seq()
                + ",\"sessionId\":" + quote(r.sessionId())
                + ",\"url\":" + quote(r.url())
                + ",\"routeKey\":" + quote(r.routeKey())
                + ",\"title\":" + quote(r.title())
                + ",\"trigger\":" + quote(r.trigger())
                + ",\"containerHash\":" + quote(r.containerHash())
                + ",\"domFile\":" + quote(r.domFile())
                + ",\"capturedAt\":" + quote(r.capturedAt())
                + ",\"settleMs\":" + r.settleMs()
                + ",\"skipped\":" + array(r.skipped())
                + ",\"frames\":" + frames(r.frames())
                + "}";
    }

    private static String frames(final List<FrameLine> frames) {
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < frames.size(); i++) {
            final FrameLine f = frames.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(f.index())
                    .append(",\"url\":").append(quote(f.url()))
                    .append(",\"domFile\":").append(quote(f.domFile()))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    /** One UsageRecord; field names and order are fixed by CLAUDE.md §3.1. */
    static String usageRecord(final UsageLine u) {
        return "{\"sessionId\":" + quote(u.sessionId())
                + ",\"snapshotSeq\":" + u.snapshotSeq()
                + ",\"routeKey\":" + quote(u.routeKey())
                + ",\"command\":" + quote(u.command())
                + ",\"locatorUsing\":" + quoteOrNull(u.locatorUsing())
                + ",\"locatorValue\":" + quoteOrNull(u.locatorValue())
                + ",\"framePath\":" + ints(u.framePath())
                + ",\"elementPath\":" + ints(u.elementPath())
                + ",\"tag\":" + quote(u.tag())
                + ",\"usedAt\":" + quote(u.usedAt())
                + "}";
    }

    record SnapshotLine(
            int seq,
            String sessionId,
            String url,
            String routeKey,
            String title,
            String trigger,
            String containerHash,
            String domFile,
            String capturedAt,
            int settleMs,
            List<String> skipped,
            List<FrameLine> frames) {
    }

    record FrameLine(int index, String url, String domFile) {
    }

    record UsageLine(
            String sessionId,
            int snapshotSeq,
            String routeKey,
            String command,
            String locatorUsing,
            String locatorValue,
            List<Integer> framePath,
            List<Integer> elementPath,
            String tag,
            String usedAt) {
    }
}
