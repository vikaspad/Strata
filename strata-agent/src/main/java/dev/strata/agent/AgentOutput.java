package dev.strata.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

/** The run directory: {@code manifest.jsonl}, {@code dom/} and {@code agent.log} (CLAUDE.md §3.1). */
final class AgentOutput {
    static final String MANIFEST = "manifest.jsonl";
    static final String USAGE = "usage.jsonl";
    static final String FAILED_LOOKUPS = "failed-lookups.jsonl";

    private final Path dir;
    private final AgentLog log;
    private final AtomicInteger seq;
    private final boolean usable;

    private AgentOutput(final Path dir, final AgentLog log, final int lastSeq, final boolean usable) {
        this.dir = dir;
        this.log = log;
        this.seq = new AtomicInteger(lastSeq);
        this.usable = usable;
    }

    /** Never throws: if the directory cannot be created the output is marked unusable and capture is skipped. */
    static AgentOutput open(final Path dir) {
        final Path abs = dir.toAbsolutePath();
        boolean usable = true;
        try {
            Files.createDirectories(abs.resolve("dom"));
        } catch (IOException | RuntimeException e) {
            usable = false;
            System.err.println("[strata] cannot create " + abs + "; capture disabled: " + e);
        }
        return new AgentOutput(abs, new AgentLog(abs.resolve("agent.log")), existingLines(abs.resolve(MANIFEST)), usable);
    }

    private static int existingLines(final Path manifest) {
        try {
            if (!Files.isRegularFile(manifest)) {
                return 0;
            }
            try (var lines = Files.lines(manifest, StandardCharsets.UTF_8)) {
                return (int) lines.filter(l -> !l.isBlank()).count();
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** False when the run directory could not be created; capture is skipped so the test pays nothing. */
    boolean usable() {
        return usable;
    }

    AgentLog log() {
        return log;
    }

    int nextSeq() {
        return seq.incrementAndGet();
    }

    void writeDom(final String relative, final String html) throws IOException {
        Files.writeString(dir.resolve(relative), html, StandardCharsets.UTF_8);
    }

    void appendManifest(final String jsonLine) throws IOException {
        append(MANIFEST, jsonLine);
    }

    void appendFailedLookup(final String jsonLine) throws IOException {
        append(FAILED_LOOKUPS, jsonLine);
    }

    void appendUsage(final String jsonLine) throws IOException {
        append(USAGE, jsonLine);
    }

    /** Appends one line under a file lock so forked JVMs sharing a run dir do not interleave. */
    private void append(final String file, final String jsonLine) throws IOException {
        final byte[] bytes = (jsonLine + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(dir.resolve(file),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             FileLock ignored = ch.lock()) {
            final ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
        }
    }
}
