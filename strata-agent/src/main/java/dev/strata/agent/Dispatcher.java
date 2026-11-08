package dev.strata.agent;

import java.util.Set;

/**
 * Static entry point called from the advice inlined into {@code RemoteWebDriver}. Filters commands, guards against
 * re-entrancy (the agent's own script calls go through {@code execute} too) and swallows every failure.
 */
public final class Dispatcher {
    static final Set<String> COMMANDS = Set.of("get", "clickElement", "goBack", "goForward", "refresh");

    private static final ThreadLocal<Boolean> IN_CAPTURE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static volatile CaptureSink sink;
    private static volatile AgentLog log;

    private Dispatcher() {
    }

    static void install(final CaptureSink captureSink, final AgentLog agentLog) {
        log = agentLog;
        sink = captureSink;
    }

    public static void onCommandCompleted(final Object driver, final String command) {
        final CaptureSink current = sink;
        if (current == null || command == null || !COMMANDS.contains(command) || IN_CAPTURE.get()) {
            return;
        }
        IN_CAPTURE.set(Boolean.TRUE);
        try {
            current.onCommandCompleted(driver, command);
        } catch (Throwable t) {
            final AgentLog l = log;
            if (l != null) {
                l.error("capture failed after '" + command + "'", t);
            }
        } finally {
            IN_CAPTURE.set(Boolean.FALSE);
        }
    }
}
