package dev.strata.agent;

import net.bytebuddy.asm.Advice;
import org.openqa.selenium.remote.CommandPayload;

/**
 * Inlined into {@code RemoteWebDriver#execute(CommandPayload)}. Every command funnels through that overload:
 * {@code execute(String, Map)} only wraps its arguments in a payload and delegates, while {@code get} and element
 * clicks call the payload overload directly. A throwable from the advice itself is suppressed; a throwable from the
 * command is observed for failed lookups and still propagates to the test unchanged.
 */
public final class ExecuteAdvice {
    private ExecuteAdvice() {
    }

    /** {@code quit} and {@code close} must be seen before they run: the session cannot answer scripts afterwards. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
            @Advice.This final Object driver,
            @Advice.Argument(0) final CommandPayload payload) {
        Dispatcher.onCommandStarting(driver, payload.getName());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
            @Advice.This final Object driver,
            @Advice.Argument(0) final CommandPayload payload,
            @Advice.Return final Object response,
            @Advice.Thrown final Throwable thrown) {
        if (thrown != null) {
            Dispatcher.onCommandFailed(driver, payload.getName(), payload.getParameters(), thrown);
        } else {
            Dispatcher.onCommandCompleted(driver, payload.getName(), payload.getParameters(), response);
        }
    }
}
