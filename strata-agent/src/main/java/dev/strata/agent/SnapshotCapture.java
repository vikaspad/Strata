package dev.strata.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Page capture (probe → settle if needed → redact and write) and usage tracking, behind one {@link CaptureSink}.
 * Runs on the test's own thread. The driver is used reflectively so the agent works with whichever class loader
 * loaded Selenium.
 */
final class SnapshotCapture implements CaptureSink {
    private final AgentOutput output;
    private final int quietMs;
    private final int capMs;
    private final UsageTracker usage;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    SnapshotCapture(final AgentOutput output, final int quietMs, final int capMs) {
        this.output = output;
        this.quietMs = quietMs;
        this.capMs = capMs;
        this.usage = new UsageTracker(output);
    }

    static final class SessionState {
        final Set<String> captured = new HashSet<>();
        final Set<String> seenElements = new HashSet<>();
        String lastRouteKey;
        String lastDocumentUrl;
        int lastSeq;
    }

    @Override
    public void onCommandCompleted(
            final Object driver, final String command, final Map<String, ?> parameters, final Object result) {
        if (!output.usable()) {
            return;
        }
        final String sessionId = sessionId(driver);
        if (sessionId == null) {
            return;
        }
        final SessionState session = sessions.computeIfAbsent(sessionId, k -> new SessionState());
        final long started = System.nanoTime();
        final List<String> outcomes = new ArrayList<>(2);
        synchronized (session) {
            if (Dispatcher.LOOKUPS.contains(command)) {
                addIfPresent(outcomes, usage.onLookup(driver, sessionId, session, command, parameters, result));
            }
            if (Dispatcher.INTERACTIONS.contains(command)) {
                // Before any capture: a click may navigate away from the element.
                addIfPresent(outcomes, usage.onInteraction(driver, sessionId, session, command, parameters));
            }
            final String trigger = triggerFor(command);
            if (trigger != null) {
                outcomes.add(capture(driver, sessionId, session, trigger));
            }
        }
        if (!outcomes.isEmpty()) {
            output.log().info(command + " -> " + String.join("; ", outcomes)
                    + " (" + (System.nanoTime() - started) / 1_000_000 + " ms)");
        }
    }

    private static void addIfPresent(final List<String> outcomes, final String outcome) {
        if (outcome != null) {
            outcomes.add(outcome);
        }
    }

    /** Returns a short description of what happened, for {@code agent.log}. */
    private String capture(final Object driver, final String sessionId, final SessionState session, final String trigger) {
        if (!(call(driver, "executeScript", Scripts.PROBE) instanceof Map<?, ?> first)) {
            return "probe returned nothing";
        }
        if (Boolean.TRUE.equals(first.get("crossOrigin"))) {
            return "inside a cross-origin frame, not captured";
        }
        // §4.2 cheap probe: an already-captured state on a loaded, quiet (or just-loaded) document that is not
        // in the middle of navigating away needs no wait.
        final boolean quiet = Boolean.TRUE.equals(first.get("fresh")) || number(first.get("quietFor")) >= quietMs;
        final boolean leaving = Boolean.TRUE.equals(first.get("leaving"));
        if (session.captured.contains(key(first)) && "complete".equals(first.get("ready")) && quiet && !leaving) {
            return "unchanged " + routeKey(first) + ", no wait";
        }
        if (!(settle(driver, leaving) instanceof Map<?, ?> probe)) {
            return "settle script returned no probe";
        }
        final String url = RouteKeys.stripQuery(string(probe.get("url")));
        final String routeKey = RouteKeys.normalize(url);
        final String containerHash = containerHash(probe.get("ids"));
        final String documentUrl = url.contains("#") ? url.substring(0, url.indexOf('#')) : url;
        final String key = routeKey + '\n' + containerHash;
        if (session.captured.contains(key)) {
            return "unchanged " + routeKey + ", settle " + number(probe.get("settleMs")) + " ms";
        }
        final boolean sameDocument = routeKey.equals(session.lastRouteKey) && documentUrl.equals(session.lastDocumentUrl);
        final String effectiveTrigger = "CLICK".equals(trigger) && sameDocument ? "CONTAINER_CHANGE" : trigger;

        final Object redacted = call(driver, "executeScript", Scripts.REDACT);
        if (!(redacted instanceof Map<?, ?> page) || !(page.get("html") instanceof String html)) {
            return "redaction script returned no html for " + routeKey;
        }
        final int seq = output.nextSeq();
        final String stem = "dom/" + String.format("%04d", seq) + "-" + RouteKeys.fileSafe(routeKey);
        final String domFile = stem + ".dom.html";
        final List<Json.FrameLine> frames = new ArrayList<>();
        try {
            output.writeDom(domFile, html);
            if (page.get("frames") instanceof List<?> captured) {
                for (final Object f : captured) {
                    if (f instanceof Map<?, ?> frame && frame.get("html") instanceof String frameHtml) {
                        final int index = number(frame.get("index"));
                        final String frameFile = stem + ".frame" + index + ".dom.html";
                        output.writeDom(frameFile, frameHtml);
                        frames.add(new Json.FrameLine(index, RouteKeys.stripQuery(string(frame.get("url"))), frameFile));
                    }
                }
            }
            output.appendManifest(Json.snapshotRecord(new Json.SnapshotLine(
                    seq,
                    sessionId,
                    url,
                    routeKey,
                    string(probe.get("title")),
                    effectiveTrigger,
                    containerHash,
                    domFile,
                    Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                    number(probe.get("settleMs")),
                    strings(page.get("skipped")),
                    frames)));
        } catch (java.io.IOException e) {
            output.log().error("could not write snapshot " + domFile, e);
            return "write failed";
        }
        session.captured.add(key);
        session.lastRouteKey = routeKey;
        session.lastDocumentUrl = documentUrl;
        session.lastSeq = seq;
        return "captured seq " + seq + " " + effectiveTrigger + " " + routeKey + ", settle "
                + number(probe.get("settleMs")) + " ms" + (frames.isEmpty() ? "" : ", " + frames.size() + " frame(s)");
    }

    /**
     * The settle wait. While the document is navigating away the script keeps waiting; when it unloads mid-wait the
     * driver reports an error, and the wait runs once more on the new document.
     */
    private Object settle(final Object driver, final boolean leaving) {
        try {
            return call(driver, "executeAsyncScript", Scripts.SETTLE, quietMs, capMs);
        } catch (IllegalStateException e) {
            if (!leaving) {
                throw e;
            }
            return call(driver, "executeAsyncScript", Scripts.SETTLE, quietMs, capMs);
        }
    }

    private static String key(final Map<?, ?> probe) {
        return routeKey(probe) + '\n' + containerHash(probe.get("ids"));
    }

    private static String routeKey(final Map<?, ?> probe) {
        return RouteKeys.normalize(RouteKeys.stripQuery(string(probe.get("url"))));
    }

    static String triggerFor(final String command) {
        return switch (command) {
            case "get" -> "GET";
            case "clickElement" -> "CLICK";
            case "goBack" -> "BACK";
            case "goForward" -> "FORWARD";
            case "refresh" -> "REFRESH";
            default -> null;
        };
    }

    /** sha1 of the sorted container id / data-testid / overlay markers, without dynamic ids (§4.3). */
    static String containerHash(final Object ids) {
        final List<String> sorted = new ArrayList<>();
        for (final String id : strings(ids)) {
            if (id.startsWith("overlay:") || !DynamicIds.isDynamic(id)) {
                sorted.add(id);
            }
        }
        Collections.sort(sorted);
        try {
            final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(sha1.digest(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sessionId(final Object driver) {
        try {
            final Object id = driver.getClass().getMethod("getSessionId").invoke(driver);
            return id == null ? null : id.toString();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("getSessionId failed: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("getSessionId unavailable on " + driver.getClass().getName(), e);
        }
    }

    /** {@code executeScript}/{@code executeAsyncScript} with the given arguments. */
    static Object call(final Object driver, final String method, final String script, final Object... args) {
        try {
            final Method m = driver.getClass().getMethod(method, String.class, Object[].class);
            return m.invoke(driver, script, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(method + " failed: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(method + " unavailable on " + driver.getClass().getName(), e);
        }
    }

    static String string(final Object o) {
        return o == null ? "" : o.toString();
    }

    static int number(final Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    static List<String> strings(final Object o) {
        final List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (final Object item : list) {
                if (item != null) {
                    out.add(item.toString());
                }
            }
        }
        return out;
    }
}
