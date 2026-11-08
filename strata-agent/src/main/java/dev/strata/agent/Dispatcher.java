package dev.strata.agent;

import java.util.Map;
import java.util.Set;

/**
 * Static entry point called from the advice inlined into {@code RemoteWebDriver}. Filters commands, guards against
 * re-entrancy (the agent's own script calls go through {@code execute} too) and swallows every failure.
 */
public final class Dispatcher {
    static final Set<String> NAVIGATION = Set.of("get", "clickElement", "goBack", "goForward", "refresh");
    static final Set<String> LOOKUPS = Set.of("findElement", "findElements", "findChildElement", "findChildElements");
    static final Set<String> INTERACTIONS = Set.of(
            "clickElement", "sendKeysToElement", "clearElement", "submitElement", "getElementText",
            "getElementAttribute", "getElementDomAttribute", "getElementDomProperty", "isElementSelected",
            "isElementEnabled", "getElementTagName", "getElementRect", "getElementValueOfCssProperty",
            "getElementAriaRole", "getElementAccessibleName");

    private static final ThreadLocal<Boolean> IN_CAPTURE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static volatile CaptureSink sink;
    private static volatile AgentLog log;

    private Dispatcher() {
    }

    static void install(final CaptureSink captureSink, final AgentLog agentLog) {
        log = agentLog;
        sink = captureSink;
    }

    static boolean relevant(final String command) {
        return NAVIGATION.contains(command) || LOOKUPS.contains(command) || INTERACTIONS.contains(command);
    }

    public static void onCommandCompleted(
            final Object driver, final String command, final Map<String, ?> parameters, final Object response) {
        final CaptureSink current = sink;
        if (current == null || command == null || !relevant(command) || IN_CAPTURE.get()) {
            return;
        }
        IN_CAPTURE.set(Boolean.TRUE);
        try {
            current.onCommandCompleted(driver, command, parameters == null ? Map.of() : parameters, valueOf(response));
        } catch (Throwable t) {
            final AgentLog l = log;
            if (l != null) {
                l.error("capture failed after '" + command + "'", t);
            }
        } finally {
            IN_CAPTURE.set(Boolean.FALSE);
        }
    }

    /** {@code Response#getValue()}, read reflectively so the agent does not link against Selenium's Response. */
    private static Object valueOf(final Object response) {
        if (response == null) {
            return null;
        }
        try {
            return response.getClass().getMethod("getValue").invoke(response);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
