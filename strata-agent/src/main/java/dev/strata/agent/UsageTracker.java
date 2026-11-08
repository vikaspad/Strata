package dev.strata.agent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Records the first time a test looks up or interacts with each element (CLAUDE.md §4.5). One {@code PATHS}
 * script call per command that brings new elements, so repeated use of an element costs nothing.
 */
final class UsageTracker {
    /** W3C WebDriver web element reference key. */
    static final String ELEMENT_KEY = "element-6066-11e4-a52e-4f735466cecf";
    static final int MAX_PER_LOOKUP = 200;

    private final AgentOutput output;

    UsageTracker(final AgentOutput output) {
        this.output = output;
    }

    String onLookup(
            final Object driver,
            final String sessionId,
            final SnapshotCapture.SessionState session,
            final String command,
            final Map<String, ?> parameters,
            final Object result) {
        final List<String> ids = new ArrayList<>();
        for (final String id : elementIds(result)) {
            if (!session.seenElements.contains(id) && !ids.contains(id) && ids.size() < MAX_PER_LOOKUP) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return null;
        }
        return record(driver, sessionId, session, command, stringOrNull(parameters.get("using")),
                stringOrNull(parameters.get("value")), ids);
    }

    String onInteraction(
            final Object driver,
            final String sessionId,
            final SnapshotCapture.SessionState session,
            final String command,
            final Map<String, ?> parameters) {
        final String id = stringOrNull(parameters.get("id"));
        if (id == null || session.seenElements.contains(id)) {
            return null;
        }
        return record(driver, sessionId, session, command, null, null, List.of(id));
    }

    private String record(
            final Object driver,
            final String sessionId,
            final SnapshotCapture.SessionState session,
            final String command,
            final String using,
            final String value,
            final List<String> ids) {
        final Object[] refs = new Object[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            refs[i] = Map.of(ELEMENT_KEY, ids.get(i));
        }
        session.seenElements.addAll(ids);
        if (!(SnapshotCapture.call(driver, "executeScript", Scripts.PATHS, refs) instanceof List<?> paths)) {
            return "usage paths returned nothing";
        }
        int recorded = 0;
        final String usedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        for (final Object p : paths) {
            if (!(p instanceof Map<?, ?> path)) {
                continue;
            }
            try {
                output.appendUsage(Json.usageRecord(new Json.UsageLine(
                        sessionId,
                        session.lastSeq,
                        session.lastRouteKey == null ? "" : session.lastRouteKey,
                        command,
                        using,
                        value,
                        ints(path.get("framePath")),
                        ints(path.get("path")),
                        SnapshotCapture.string(path.get("tag")),
                        usedAt)));
                recorded++;
            } catch (java.io.IOException e) {
                output.log().error("could not write usage", e);
                return "usage write failed";
            }
        }
        return "used " + recorded + " element(s)";
    }

    /** Element ids from a lookup result: {@code RemoteWebElement}s (via {@code getId()}) or raw W3C references. */
    static List<String> elementIds(final Object result) {
        final List<String> out = new ArrayList<>();
        if (result instanceof List<?> list) {
            for (final Object item : list) {
                out.addAll(elementIds(item));
            }
            return out;
        }
        if (result instanceof Map<?, ?> map && map.get(ELEMENT_KEY) instanceof String id) {
            out.add(id);
            return out;
        }
        if (result != null) {
            try {
                final Object id = result.getClass().getMethod("getId").invoke(result);
                if (id instanceof String s) {
                    out.add(s);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Not an element.
            }
        }
        return out;
    }

    private static List<Integer> ints(final Object o) {
        final List<Integer> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (final Object item : list) {
                out.add(SnapshotCapture.number(item));
            }
        }
        return out;
    }

    private static String stringOrNull(final Object o) {
        return o == null ? null : o.toString();
    }
}
