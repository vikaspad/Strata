package dev.strata.agent;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/** {@code -javaagent} entry point. Requires {@code -Dstrata.out=<abs path>}; without it the agent stays inert. */
public final class StrataAgent {
    static final String TARGET = "org.openqa.selenium.remote.RemoteWebDriver";
    static final String PAYLOAD = "org.openqa.selenium.remote.CommandPayload";

    private StrataAgent() {
    }

    public static void premain(final String args, final Instrumentation inst) {
        start(inst);
    }

    public static void agentmain(final String args, final Instrumentation inst) {
        start(inst);
    }

    private static void start(final Instrumentation inst) {
        final String out = System.getProperty("strata.out");
        if (out == null || out.isBlank()) {
            System.err.println("[strata] -Dstrata.out is not set; locator coverage agent disabled");
            return;
        }
        try {
            final AgentOutput output = AgentOutput.open(Path.of(out));
            final int quietMs = Integer.getInteger("strata.quietMs", 500);
            final int capMs = Integer.getInteger("strata.capMs", 5000);
            Dispatcher.install(new SnapshotCapture(output, quietMs, capMs), output.log());
            install(inst, output.log());
            output.log().info("agent started on Java " + Runtime.version() + ", out=" + out
                    + ", quietMs=" + quietMs + ", capMs=" + capMs);
        } catch (Throwable t) {
            System.err.println("[strata] agent failed to start; the test runs without it: " + t);
        }
    }

    private static void install(final Instrumentation inst, final AgentLog log) {
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new ErrorListener(log))
                .ignore(nameStartsWith("dev.strata.").or(nameStartsWith("java.")).or(nameStartsWith("jdk.")))
                .type(named(TARGET))
                .transform(StrataAgent::transform)
                .installOn(inst);
    }

    private static DynamicType.Builder<?> transform(
            final DynamicType.Builder<?> builder,
            final TypeDescription type,
            final ClassLoader loader,
            final JavaModule module,
            final java.security.ProtectionDomain domain) {
        return builder.visit(Advice.to(ExecuteAdvice.class)
                .on(named("execute").and(takesArguments(1)).and(takesArgument(0, named(PAYLOAD)))));
    }

    private static final class ErrorListener extends AgentBuilder.Listener.Adapter {
        private final AgentLog log;

        ErrorListener(final AgentLog log) {
            this.log = log;
        }

        @Override
        public void onTransformation(
                final TypeDescription type,
                final ClassLoader loader,
                final JavaModule module,
                final boolean loaded,
                final DynamicType dynamicType) {
            log.info("instrumented " + type.getName());
        }

        @Override
        public void onError(
                final String typeName,
                final ClassLoader loader,
                final JavaModule module,
                final boolean loaded,
                final Throwable throwable) {
            log.error("could not instrument " + typeName, throwable);
        }
    }
}
