package dev.strata.agent;

import net.bytebuddy.asm.Advice;
import org.openqa.selenium.remote.CommandPayload;

/**
 * Inlined into {@code RemoteWebDriver#execute(CommandPayload)}. Every command funnels through that overload:
 * {@code execute(String, Map)} only wraps its arguments in a payload and delegates, while {@code get} and element
 * clicks call the payload overload directly. Runs on normal exit only; any throwable is suppressed.
 */
public final class ExecuteAdvice {
    private ExecuteAdvice() {
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This final Object driver, @Advice.Argument(0) final CommandPayload payload) {
        Dispatcher.onCommandCompleted(driver, payload.getName());
    }
}
