package dev.strata.agent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Records the first time a test looks up or interacts with each element (CLAUDE.md §4.5). One {@code PATHS}
 * script call per command that brings new elements, so repeated use of an element costs nothing.
 */
final class UsageTracker {
    /** W3C WebDriver web element reference key. */
    static final String ELEMENT_KEY = "element-6066-11e4-a52e-4f735466cecf";
    static final int MAX_PER_LOOKUP = 200;
    static final String RECOVERED = "RECOVERED";

    private final AgentOutput output;

    UsageTracker(final AgentOutput output) {
        this.output = output;
    }

    /** Whether this lookup result holds an element not recorded yet in this session. */
    boolean bringsNewElements(final SnapshotCapture.SessionState session, final Object result) {
        for (final String id : elementIds(result)) {
            if (!session.seenElements.contains(id)) {
                return true;
            }
        }
        return false;
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

    /** Remembers a locator that found something, and retracts its failure if polling failed before this success. */
    void onLookupFound(
            final String sessionId,
            final SnapshotCapture.SessionState session,
            final String command,
            final Map<String, ?> parameters,
            final Object result) {
        if (elementIds(result).isEmpty()) {
            return;
        }
        final String key = locatorKey(parameters);
        session.foundLocators.add(key);
        if (session.failedLocators.remove(key)) {
            final String url = session.lastDocumentUrl == null ? "" : session.lastDocumentUrl;
            writeFailure(sessionId, url, command, parameters, RECOVERED);
        }
    }

    /**
     * Records the first failure of a locator that has not found anything in this session (§4.5a). Later failures are
     * free, so a {@code WebDriverWait} polling a missing element costs one probe.
     */
    String onLookupFailed(
            final String sessionId,
            final SnapshotCapture.SessionState session,
            final String command,
            final Map<String, ?> parameters,
            final Throwable thrown,
            final Supplier<String> currentUrl) {
        final String outcome = outcomeOf(thrown);
        final String key = locatorKey(parameters);
        if (outcome == null || session.foundLocators.contains(key) || !session.failedLocators.add(key)) {
            return null;
        }
        writeFailure(sessionId, currentUrl.get(), command, parameters, outcome);
        return "lookup failed: " + outcome;
    }

    private void writeFailure(
            final String sessionId,
            final String url,
            final String command,
            final Map<String, ?> parameters,
            final String outcome) {
        try {
            output.appendFailedLookup(Json.failedLookupRecord(new Json.FailedLookupLine(
                    sessionId,
                    url,
                    RouteKeys.normalize(url),
                    command,
                    SnapshotCapture.string(parameters.get("using")),
                    SnapshotCapture.string(parameters.get("value")),
                    outcome,
                    Instant.now().truncatedTo(ChronoUnit.MILLIS).toString())));
        } catch (java.io.IOException e) {
            output.log().error("could not write failed lookup", e);
        }
    }

    private static String locatorKey(final Map<?, ?> parameters) {
        return SnapshotCapture.string(parameters.get("using")) + '\n' + SnapshotCapture.string(parameters.get("value"));
    }

    /** {@code NOT_FOUND}, {@code INVALID_SELECTOR}, or null for failures that say nothing about the locator. */
    static String outcomeOf(final Throwable thrown) {
        for (Class<?> c = thrown.getClass(); c != null; c = c.getSuperclass()) {
            switch (c.getSimpleName()) {
                case "InvalidSelectorException":
                    return "INVALID_SELECTOR";
                case "NoSuchElementException":
                    return "NOT_FOUND";
                default:
                    break;
            }
        }
        return null;
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
