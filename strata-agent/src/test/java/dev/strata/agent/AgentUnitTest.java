package dev.strata.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AgentUnitTest {

    @ParameterizedTest
    @CsvSource({
            "http://localhost:8080/blotter/48213?x=1, /blotter/{id}",
            "https://h/blotter/99001#export, /blotter/{id}",
            "http://h/login, /login",
            "http://h/, /",
            "http://h, /",
            "http://h/orders/550e8400-e29b-41d4-a716-446655440000/edit, /orders/{id}/edit",
            "http://h/trade/AB12CD34EF, /trade/{id}",
            "http://h/blob/0123456789abcdef01, /blob/{id}",
            "http://h/Settings/Profile/, /Settings/Profile",
    })
    void normalizesRoutesLikeCore(final String url, final String expected) {
        assertEquals(expected, RouteKeys.normalize(url));
    }

    @Test
    void fileSafeRouteKeys() {
        assertEquals("blotter_id", RouteKeys.fileSafe("/blotter/{id}"));
        assertEquals("root", RouteKeys.fileSafe("/"));
        assertEquals("orders_id_edit", RouteKeys.fileSafe("/orders/{id}/edit"));
    }

    @Test
    void stripsQueryButKeepsFragment() {
        assertEquals("http://h/a#x", RouteKeys.stripQuery("http://h/a?u=1&p=2#x"));
        assertEquals("http://h/a", RouteKeys.stripQuery("http://h/a?u=1"));
        assertEquals("http://h/a#x", RouteKeys.stripQuery("http://h/a#x"));
    }

    @Test
    void containerHashIgnoresOrder() {
        assertEquals(SnapshotCapture.containerHash(List.of("b", "a")), SnapshotCapture.containerHash(List.of("a", "b")));
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", SnapshotCapture.containerHash(List.of()));
    }

    @Test
    void mapsOnlyM1Commands() {
        assertEquals("GET", SnapshotCapture.triggerFor("get"));
        assertEquals("CLICK", SnapshotCapture.triggerFor("clickElement"));
        assertEquals("BACK", SnapshotCapture.triggerFor("goBack"));
        assertEquals("FORWARD", SnapshotCapture.triggerFor("goForward"));
        assertEquals("REFRESH", SnapshotCapture.triggerFor("refresh"));
        assertNull(SnapshotCapture.triggerFor("executeScript"));
        assertNull(SnapshotCapture.triggerFor("sendKeysToElement"));
    }

    @Test
    void manifestLineHasContractFieldOrderAndEscaping() {
        final String line = Json.snapshotRecord(new Json.SnapshotLine(
                3, "s1", "http://h/a\"b", "/a", "T\\itle\n", "CLICK", "abc", "dom/0003-a.dom.html",
                "2026-11-06T22:33:40.123Z", 512, List.of("iframe#chart"),
                List.of(new Json.FrameLine(0, "http://h/f", "dom/0003-a.frame0.dom.html"))));
        assertEquals("{\"seq\":3,\"sessionId\":\"s1\",\"url\":\"http://h/a\\\"b\",\"routeKey\":\"/a\","
                + "\"title\":\"T\\\\itle\\n\",\"trigger\":\"CLICK\",\"containerHash\":\"abc\","
                + "\"domFile\":\"dom/0003-a.dom.html\",\"capturedAt\":\"2026-11-06T22:33:40.123Z\","
                + "\"settleMs\":512,\"skipped\":[\"iframe#chart\"],"
                + "\"frames\":[{\"index\":0,\"url\":\"http://h/f\",\"domFile\":\"dom/0003-a.frame0.dom.html\"}]}", line);
    }

    @Test
    void usageLineHasContractFieldOrder() {
        final String line = Json.usageRecord(new Json.UsageLine("s1", 2, "/a", "findElement", "css selector", "#x",
                List.of(), List.of(1, 0, 3), "button", "2026-11-07T12:00:00.000Z"));
        assertEquals("{\"sessionId\":\"s1\",\"snapshotSeq\":2,\"routeKey\":\"/a\",\"command\":\"findElement\","
                + "\"locatorUsing\":\"css selector\",\"locatorValue\":\"#x\",\"framePath\":[],"
                + "\"elementPath\":[1,0,3],\"tag\":\"button\",\"usedAt\":\"2026-11-07T12:00:00.000Z\"}", line);
        assertEquals("null", Json.quoteOrNull(null));
    }

    @Test
    void failedLookupLineHasContractFieldOrder() {
        final String line = Json.failedLookupRecord(new Json.FailedLookupLine("s1", "http://h/a", "/a", "findElement",
                "css selector", "#x", "NOT_FOUND", "2026-11-08T12:00:00.000Z"));
        assertEquals("{\"sessionId\":\"s1\",\"url\":\"http://h/a\",\"routeKey\":\"/a\",\"command\":\"findElement\","
                + "\"locatorUsing\":\"css selector\",\"locatorValue\":\"#x\",\"outcome\":\"NOT_FOUND\","
                + "\"recordedAt\":\"2026-11-08T12:00:00.000Z\"}", line);
    }

    /** Named like Selenium's classes: the agent matches by simple name so it needs no link to them. */
    static class NoSuchElementException extends RuntimeException {
    }

    static class InvalidSelectorException extends NoSuchElementException {
    }

    static class SubclassOfNoSuchElement extends NoSuchElementException {
    }

    @Test
    void onlyLocatorFailuresCount() {
        assertEquals("NOT_FOUND", UsageTracker.outcomeOf(new NoSuchElementException()));
        assertEquals("NOT_FOUND", UsageTracker.outcomeOf(new SubclassOfNoSuchElement()));
        assertEquals("INVALID_SELECTOR", UsageTracker.outcomeOf(new InvalidSelectorException()));
        assertNull(UsageTracker.outcomeOf(new IllegalStateException("session gone")));
    }

    @ParameterizedTest
    @CsvSource({
            "https://www.SauceDemo.com/a?x=1, www.saucedemo.com",
            "http://127.0.0.1:8080/a, 127.0.0.1:8080",
            "http://u:p@h/a, h",
            "https://h, h",
            "https://h?x=1, h",
    })
    void hostOfUrl(final String url, final String expected) {
        assertEquals(expected, RouteKeys.host(url));
    }

    @Test
    void hostIsEmptyWithoutScheme() {
        assertEquals("", RouteKeys.host("about:blank"));
    }

    @Test
    void failedLookupsAreDispatchedOnlyForLookupsAndSwallowFailures() {
        final List<String> seen = new java.util.ArrayList<>();
        Dispatcher.install(new CaptureSink() {
            @Override
            public void onCommandCompleted(
                    final Object driver, final String command, final Map<String, ?> parameters, final Object result) {
            }

            @Override
            public void onCommandFailed(
                    final Object driver, final String command, final Map<String, ?> parameters, final Throwable thrown) {
                seen.add(command);
                Dispatcher.onCommandFailed(driver, "findElements", Map.of(), thrown); // re-entrant: must be ignored
                throw new IllegalStateException("boom");
            }
        }, new AgentLog(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "strata-unit-agent.log")));
        final RuntimeException failure = new NoSuchElementException();
        Dispatcher.onCommandFailed(new Object(), "findElement", Map.of(), failure);
        Dispatcher.onCommandFailed(new Object(), "clickElement", Map.of(), failure);
        assertEquals(List.of("findElement"), seen);
        Dispatcher.install(null, null);
    }

    @Test
    void containerHashIgnoresDynamicIdsButKeepsOverlays() {
        assertEquals(SnapshotCapture.containerHash(List.of("a")), SnapshotCapture.containerHash(List.of("a", "mat-input-7", "ps-7731")));
        assertNotEquals(SnapshotCapture.containerHash(List.of("a")), SnapshotCapture.containerHash(List.of("a", "overlay:div:listbox:")));
    }

    @Test
    void elementIdsFromLookupResults() {
        final Map<String, String> ref = Map.of(UsageTracker.ELEMENT_KEY, "abc");
        assertEquals(List.of("abc", "abc"), UsageTracker.elementIds(List.of(ref, ref)));
        assertEquals(List.of(), UsageTracker.elementIds("not an element"));
        assertEquals(List.of(), UsageTracker.elementIds(null));
    }

    @Test
    void dispatcherSwallowsSinkFailuresAndIgnoresReentry() {
        final int[] calls = {0};
        Dispatcher.install((driver, command, parameters, result) -> {
            calls[0]++;
            Dispatcher.onCommandCompleted(driver, "get", Map.of(), null); // re-entrant: must be ignored
            throw new IllegalStateException("boom");
        }, new AgentLog(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "strata-unit-agent.log")));
        Dispatcher.onCommandCompleted(new Object(), "clickElement", Map.of(), null);
        Dispatcher.onCommandCompleted(new Object(), "findElement", Map.of(), null);
        Dispatcher.onCommandCompleted(new Object(), "executeScript", Map.of(), null);
        assertEquals(2, calls[0]);
        Dispatcher.install(null, null);
    }

    @Test
    void sessionEndingFiresOnlyForQuitAndCloseAndSwallowsFailures() {
        final java.util.List<String> seen = new java.util.ArrayList<>();
        Dispatcher.install(new CaptureSink() {
            @Override
            public void onCommandCompleted(
                    final Object driver, final String command, final Map<String, ?> parameters, final Object result) {
            }

            @Override
            public void onSessionEnding(final Object driver, final String command) {
                seen.add(command);
                Dispatcher.onCommandStarting(driver, "close"); // re-entrant: must be ignored
                throw new IllegalStateException("boom");
            }
        }, new AgentLog(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "strata-unit-agent.log")));
        Dispatcher.onCommandStarting(new Object(), "quit");
        Dispatcher.onCommandStarting(new Object(), "close");
        Dispatcher.onCommandStarting(new Object(), "get");
        Dispatcher.onCommandStarting(new Object(), null);
        assertEquals(List.of("quit", "close"), seen);
        Dispatcher.install(null, null);
    }
}
