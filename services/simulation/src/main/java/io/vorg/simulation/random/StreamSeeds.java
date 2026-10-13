package io.vorg.simulation.random;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Seed derivation scheme {@value #SCHEME} (ADR-47). A child seed is the first 8 bytes (big-endian) of
 * {@code SHA-256("vorg-des/v1|" + parentSeed + "|" + name)} with {@code parentSeed} in decimal. Every named stream and
 * every replication gets its seed from its own name only, so adding, removing or reordering streams (or replications)
 * never changes the numbers drawn by any other stream: a pure function, no shared generator state.
 */
public final class StreamSeeds {

    public static final String SCHEME = "sha256-name-v1";
    private static final String PREFIX = "vorg-des/v1|";

    private StreamSeeds() {}

    public static long derive(long parentSeed, String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("stream name required");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((PREFIX + parentSeed + "|" + name).getBytes(StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (h[i] & 0xFFL);
            }
            return v;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The master seed of replication {@code index} (0-based) of a run with master seed {@code masterSeed}. */
    public static long replicationSeed(long masterSeed, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        return derive(masterSeed, "replication/" + index);
    }
}
