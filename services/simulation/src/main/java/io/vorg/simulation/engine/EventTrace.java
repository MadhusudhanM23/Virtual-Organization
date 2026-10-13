package io.vorg.simulation.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Running SHA-256 over every executed event (ADR-47). Each event contributes, in this order: time (8 bytes, big-endian),
 * priority (4 bytes), sequence (8 bytes), the UTF-8 label and a {@code 0x0A} separator. Cancelled events are not part of
 * the trace. Two runs with the same inputs and seed must produce the same {@link #hash()}; the first
 * {@code keepLines} events are additionally retained as text for debugging.
 */
public final class EventTrace {

    private final MessageDigest digest;
    private final int keepLines;
    private final List<String> lines = new ArrayList<>();
    private final byte[] buf = new byte[20];
    private long count;
    private String hash;

    public EventTrace(int keepLines) {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        this.keepLines = Math.max(0, keepLines);
    }

    void record(ScheduledEvent e) {
        if (hash != null) {
            throw new IllegalStateException("trace already finished");
        }
        putLong(0, e.time());
        putInt(8, e.priority());
        putLong(12, e.sequence());
        digest.update(buf, 0, 20);
        digest.update(e.label().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0x0A);
        if (lines.size() < keepLines) {
            lines.add(e.toString());
        }
        count++;
    }

    /** Finishes the digest (idempotent) and returns the lowercase hex SHA-256. */
    public String hash() {
        if (hash == null) {
            hash = HexFormat.of().formatHex(digest.digest());
        }
        return hash;
    }

    public long count() {
        return count;
    }

    public List<String> lines() {
        return Collections.unmodifiableList(lines);
    }

    private void putLong(int off, long v) {
        for (int i = 7; i >= 0; i--) {
            buf[off + i] = (byte) v;
            v >>>= 8;
        }
    }

    private void putInt(int off, int v) {
        for (int i = 3; i >= 0; i--) {
            buf[off + i] = (byte) v;
            v >>>= 8;
        }
    }
}
