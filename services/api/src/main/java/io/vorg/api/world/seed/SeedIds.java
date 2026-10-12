package io.vorg.api.world.seed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Deterministic identifiers for seeded data: a name-based UUID shaped as version 7 (SHA-256 of the name; the version and
 * variant bits are set so the ids look like every other platform id). The same name always yields the same id on every
 * machine and run, which is what makes the seed reproducible and re-runnable without duplicates.
 */
public final class SeedIds {

    private SeedIds() {}

    public static UUID of(String name) {
        byte[] hash = sha256(name.getBytes(StandardCharsets.UTF_8));
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (hash[i] & 0xffL);
            lsb = (lsb << 8) | (hash[8 + i] & 0xffL);
        }
        msb = (msb & 0xffffffffffff0fffL) | 0x0000000000007000L; // version 7
        lsb = (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L; // RFC 4122 variant
        return new UUID(msb, lsb);
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex); // SHA-256 is mandatory in every JRE
        }
    }

    public static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256(text.getBytes(StandardCharsets.UTF_8)));
    }
}
