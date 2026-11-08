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
 * Settle → probe → (if the page changed) redact and write. Runs on the test's own thread. The driver is used
 * reflectively so the agent works with whichever class loader loaded Selenium.
 */
final class SnapshotCapture implements CaptureSink {
    private final AgentOutput output;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    SnapshotCapture(final AgentOutput output) {
        this.output = output;
    }

    private static final class SessionState {
        final Set<String> captured = new HashSet<>();
        String lastRouteKey;
        String lastDocumentUrl;
    }

    @Override
    public void onCommandCompleted(final Object driver, final String command) {
        final String trigger = triggerFor(command);
        if (trigger == null || !output.usable()) {
            return;
        }
        final String sessionId = sessionId(driver);
        if (sessionId == null) {
            return;
        }
        final long started = System.nanoTime();
        final String outcome = capture(driver, trigger, sessionId);
        output.log().info(command + " -> " + outcome + " (" + (System.nanoTime() - started) / 1_000_000 + " ms)");
    }

    /** Returns a short description of what happened, for {@code agent.log}. */
    private String capture(final Object driver, final String trigger, final String sessionId) {
        final long t0 = System.nanoTime();
        final Object probeResult = call(driver, "executeAsyncScript", Scripts.SETTLE);
        final long probeMs = (System.nanoTime() - t0) / 1_000_000;
        if (!(probeResult instanceof Map<?, ?> probe)) {
            return "settle script returned no probe";
        }
        final String url = RouteKeys.stripQuery(string(probe.get("url")));
        final String routeKey = RouteKeys.normalize(url);
        final String containerHash = containerHash(probe.get("ids"));
        final String documentUrl = url.contains("#") ? url.substring(0, url.indexOf('#')) : url;

        final SessionState session = sessions.computeIfAbsent(sessionId, k -> new SessionState());
        synchronized (session) {
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
            final String domFile = "dom/" + String.format("%04d", seq) + "-" + RouteKeys.fileSafe(routeKey) + ".dom.html";
            try {
                output.writeDom(domFile, html);
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
                        strings(page.get("skipped")))));
            } catch (java.io.IOException e) {
                output.log().error("could not write snapshot " + domFile, e);
                return "write failed";
            }
            session.captured.add(key);
            session.lastRouteKey = routeKey;
            session.lastDocumentUrl = documentUrl;
            return "captured seq " + seq + " " + effectiveTrigger + " " + routeKey + ", settle "
                    + number(probe.get("settleMs")) + " ms, probe round trip " + probeMs + " ms";
        }
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

    /** sha1 of the sorted container id / data-testid values (§4.3). */
    static String containerHash(final Object ids) {
        final List<String> sorted = strings(ids);
        Collections.sort(sorted);
        try {
            final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(sha1.digest(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sessionId(final Object driver) {
        final Object id = call(driver, "getSessionId");
        return id == null ? null : id.toString();
    }

    private static Object call(final Object driver, final String method, final String script) {
        try {
            final Method m = driver.getClass().getMethod(method, String.class, Object[].class);
            return m.invoke(driver, script, new Object[0]);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(method + " failed: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(method + " unavailable on " + driver.getClass().getName(), e);
        }
    }

    private static Object call(final Object driver, final String method) {
        try {
            return driver.getClass().getMethod(method).invoke(driver);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(method + " failed: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(method + " unavailable on " + driver.getClass().getName(), e);
        }
    }

    private static String string(final Object o) {
        return o == null ? "" : o.toString();
    }

    private static int number(final Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static List<String> strings(final Object o) {
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
