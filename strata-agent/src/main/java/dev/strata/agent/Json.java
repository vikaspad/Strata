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
            List<String> skipped) {
    }
}
