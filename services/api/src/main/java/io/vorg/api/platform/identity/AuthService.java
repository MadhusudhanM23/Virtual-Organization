package io.vorg.api.platform.identity;

import io.vorg.api.platform.Ids;
import io.vorg.api.platform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dev identity issuer logic (ADR-16): password login, rotating refresh tokens with reuse detection, logout.
 * Every failure collapses to {@link #INVALID} so callers cannot distinguish tenant/user/password errors.
 */
@Service
public class AuthService {

    /** Result of a successful login/refresh: the access token plus the opaque refresh cookie value to set. */
    public record Tokens(String accessToken, String refreshCookieValue, Duration refreshTtl) {}

    public static final Duration REFRESH_TTL = Duration.ofDays(7);
    static final Optional<Tokens> INVALID = Optional.empty();

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder encoder;
    private final AccessTokenService accessTokens;
    private final SubjectService subjects;
    /** Pre-computed hash compared against when the user is unknown, so timing does not reveal existence. */
    private final String dummyHash;

    public AuthService(JdbcClient jdbc, TransactionTemplate tx, PasswordEncoder encoder,
            AccessTokenService accessTokens, SubjectService subjects,
            @Value("${vo.auth.dummy-password:not-a-real-password}") String dummyPassword) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.encoder = encoder;
        this.accessTokens = accessTokens;
        this.subjects = subjects;
        this.dummyHash = encoder.encode(dummyPassword);
    }

    public Optional<Tokens> login(String tenantSlug, String email, String password) {
        Optional<UUID> tenantId = jdbc.sql("select id from iam.tenants where slug = :slug")
                .param("slug", tenantSlug == null ? "" : tenantSlug.trim().toLowerCase())
                .query(UUID.class).optional();
        if (tenantId.isEmpty()) {
            encoder.matches(password == null ? "" : password, dummyHash);
            return INVALID;
        }
        UUID tid = tenantId.get();
        return TenantContext.callWith(tid, () -> {
            record Row(UUID actorRef, String hash) {}
            Optional<Row> row = jdbc.sql("""
                    select actor_ref, password_hash from iam.identity_profiles
                    where tenant_id = :tid and lower(email) = lower(:email) and status = 'ACTIVE'
                    """)
                    .param("tid", tid).param("email", email == null ? "" : email.trim())
                    .query((rs, n) -> new Row(rs.getObject("actor_ref", UUID.class), rs.getString("password_hash")))
                    .optional();
            String candidate = password == null ? "" : password;
            boolean ok = encoder.matches(candidate, row.map(Row::hash).orElse(dummyHash));
            if (row.isEmpty() || !ok) {
                return INVALID;
            }
            UUID actor = row.get().actorRef();
            String secret = newSecret();
            tx.executeWithoutResult(s -> insertToken(tid, actor, hash(secret), Ids.newId()));
            return Optional.of(new Tokens(accessTokens.issue(actor, tid), tid + "." + secret, REFRESH_TTL));
        });
    }

    public Optional<Tokens> refresh(String cookieValue) {
        Optional<Parsed> parsed = parse(cookieValue);
        if (parsed.isEmpty()) {
            return INVALID;
        }
        UUID tid = parsed.get().tenantId();
        String presentedHash = hash(parsed.get().secret());
        return TenantContext.callWith(tid, () -> {
            record Row(UUID id, UUID actorRef, UUID familyId, Instant expiresAt, Instant revokedAt, UUID replacedBy) {}
            return tx.execute(status -> {
                Optional<Row> found = jdbc.sql("""
                        select id, actor_ref, family_id, expires_at, revoked_at, replaced_by
                        from iam.refresh_tokens where token_hash = :h for update
                        """)
                        .param("h", presentedHash)
                        .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("actor_ref", UUID.class),
                                rs.getObject("family_id", UUID.class),
                                rs.getObject("expires_at", java.time.OffsetDateTime.class).toInstant(),
                                Optional.ofNullable(rs.getObject("revoked_at", java.time.OffsetDateTime.class))
                                        .map(java.time.OffsetDateTime::toInstant).orElse(null),
                                rs.getObject("replaced_by", UUID.class)))
                        .optional();
                if (found.isEmpty()) {
                    return INVALID;
                }
                Row old = found.get();
                if (old.revokedAt() != null || old.replacedBy() != null) {
                    // Reuse of a rotated/revoked token: treat as theft and burn the whole family (ADR-16).
                    revokeFamily(tid, old.familyId());
                    return INVALID;
                }
                if (!old.expiresAt().isAfter(Instant.now()) || subjects.load(tid, old.actorRef()).isEmpty()) {
                    return INVALID;
                }
                UUID newId = Ids.newId();
                String secret = newSecret();
                insertToken(tid, old.actorRef(), hash(secret), old.familyId(), newId);
                jdbc.sql("update iam.refresh_tokens set replaced_by = :n where id = :id")
                        .param("n", newId).param("id", old.id()).update();
                return Optional.of(new Tokens(accessTokens.issue(old.actorRef(), tid), tid + "." + secret,
                        REFRESH_TTL));
            });
        });
    }

    /** Revokes the whole token family of the presented refresh cookie; silently ignores unknown/garbled cookies. */
    public void logout(String cookieValue) {
        Optional<Parsed> parsed = parse(cookieValue);
        if (parsed.isEmpty()) {
            return;
        }
        UUID tid = parsed.get().tenantId();
        String presentedHash = hash(parsed.get().secret());
        TenantContext.callWith(tid, () -> {
            tx.executeWithoutResult(status -> jdbc.sql(
                            "select family_id from iam.refresh_tokens where token_hash = :h")
                    .param("h", presentedHash).query(UUID.class).optional()
                    .ifPresent(family -> revokeFamily(tid, family)));
            return null;
        });
    }

    private void insertToken(UUID tid, UUID actor, String tokenHash, UUID id) {
        insertToken(tid, actor, tokenHash, id, id);
    }

    private void insertToken(UUID tid, UUID actor, String tokenHash, UUID familyId, UUID id) {
        jdbc.sql("""
                insert into iam.refresh_tokens (id, tenant_id, actor_ref, token_hash, family_id, expires_at)
                values (:id, :tid, :actor, :h, :fam, :exp)
                """)
                .param("id", id).param("tid", tid).param("actor", actor).param("h", tokenHash)
                .param("fam", familyId)
                .param("exp", java.sql.Timestamp.from(Instant.now().plus(REFRESH_TTL)))
                .update();
    }

    private void revokeFamily(UUID tid, UUID familyId) {
        jdbc.sql("""
                update iam.refresh_tokens set revoked_at = now()
                where tenant_id = :tid and family_id = :fam and revoked_at is null
                """)
                .param("tid", tid).param("fam", familyId).update();
    }

    private record Parsed(UUID tenantId, String secret) {}

    private static Optional<Parsed> parse(String cookieValue) {
        if (cookieValue == null) {
            return Optional.empty();
        }
        int dot = cookieValue.indexOf('.');
        if (dot <= 0 || dot == cookieValue.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Parsed(UUID.fromString(cookieValue.substring(0, dot)),
                    cookieValue.substring(dot + 1)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
