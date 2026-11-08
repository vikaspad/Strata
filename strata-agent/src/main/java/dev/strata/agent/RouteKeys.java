package dev.strata.agent;

import java.util.List;
import java.util.regex.Pattern;

/** Java copy of {@code dev.strata.core.DefaultRouteNormalizer} (CLAUDE.md §5.1). Keep the two in sync. */
public final class RouteKeys {
    private static final List<Pattern> ID_SEGMENTS = List.of(
            Pattern.compile("^\\d+$"),
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-"),
            Pattern.compile("^[A-Z0-9]{8,}$"),
            Pattern.compile("^[0-9a-f]{16,}$"));

    private RouteKeys() {
    }

    public static String normalize(final String url) {
        String path = cut(cut(url, '#'), '?');
        final int scheme = path.indexOf("://");
        if (scheme >= 0) {
            final String afterScheme = path.substring(scheme + 3);
            final int slash = afterScheme.indexOf('/');
            path = slash >= 0 ? afterScheme.substring(slash) : "/";
        }
        final StringBuilder out = new StringBuilder();
        for (final String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            out.append('/').append(isId(segment) ? "{id}" : segment);
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    /** {@code /blotter/{id}} → {@code blotter_id}; {@code /} → {@code root}. */
    public static String fileSafe(final String routeKey) {
        String safe = routeKey.replace("{", "").replace("}", "");
        safe = safe.replaceAll("^/+|/+$", "").replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^_+|_+$", "");
        return safe.isEmpty() ? "root" : safe;
    }

    /** The raw URL with its query string removed; the fragment is kept. */
    public static String stripQuery(final String url) {
        final int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        final int hash = url.indexOf('#', q);
        return url.substring(0, q) + (hash >= 0 ? url.substring(hash) : "");
    }

    private static boolean isId(final String segment) {
        for (final Pattern p : ID_SEGMENTS) {
            if (p.matcher(segment).find()) {
                return true;
            }
        }
        return false;
    }

    private static String cut(final String s, final char c) {
        final int i = s.indexOf(c);
        return i >= 0 ? s.substring(0, i) : s;
    }
}
