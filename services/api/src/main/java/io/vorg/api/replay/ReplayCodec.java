package io.vorg.api.replay;

import io.vorg.api.replay.ReplayModel.ReplayState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical JSON for replay state and hashes (ADR-44): snake_case, record fields in declaration order, map keys sorted,
 * instants as ISO-8601 UTC strings. Independent of the web mapper, so a configuration change there cannot change a
 * stored snapshot's bytes or a state hash. Gzip output carries no timestamp, so equal state compresses to equal bytes.
 */
public final class ReplayCodec {

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public byte[] canonical(Object value) {
        return mapper.writeValueAsBytes(value);
    }

    public String sha256(Object value) {
        return sha256(canonical(value));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    public static byte[] gunzip(byte[] compressed) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gz.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public ReplayState readState(byte[] canonicalJson) {
        return mapper.readValue(canonicalJson, ReplayState.class);
    }
}
