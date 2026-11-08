package dev.strata.agent;

import java.util.Map;

/**
 * The only thing interception calls. All capture logic lives behind this seam so a future
 * {@code WebDriverListener} fallback can drive the same sink (CLAUDE.md §6).
 */
public interface CaptureSink {
    /**
     * @param parameters the command's parameters (e.g. {@code using}/{@code value} for lookups, {@code id} for
     *                   element commands)
     * @param result     the command's return value after Selenium's element conversion: a {@code WebElement}, a
     *                   list of them, or anything else
     */
    void onCommandCompleted(Object driver, String command, Map<String, ?> parameters, Object result);
}
