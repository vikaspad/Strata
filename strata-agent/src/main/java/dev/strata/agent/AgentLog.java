package dev.strata.agent;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Appends to {@code <out>/agent.log}. Never throws; if the file cannot be written the message is dropped. */
public final class AgentLog {
    private final Path file;
    private volatile boolean broken;

    AgentLog(final Path file) {
        this.file = file;
    }

    public void info(final String message) {
        write("INFO  " + message);
    }

    public void error(final String message, final Throwable t) {
        final StringWriter trace = new StringWriter();
        if (t != null) {
            t.printStackTrace(new PrintWriter(trace));
        }
        write("ERROR " + message + (t == null ? "" : System.lineSeparator() + trace));
    }

    private synchronized void write(final String line) {
        if (broken) {
            return;
        }
        try {
            Files.writeString(file, Instant.now() + " " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            broken = true;
            System.err.println("[strata] cannot write " + file + "; agent logging disabled: " + e);
        }
    }
}
