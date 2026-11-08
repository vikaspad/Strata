package dev.strata.agent;

/**
 * The only thing interception calls. All capture logic lives behind this seam so a future
 * {@code WebDriverListener} fallback can drive the same sink (CLAUDE.md §6).
 */
public interface CaptureSink {
    void onCommandCompleted(Object driver, String command);
}
