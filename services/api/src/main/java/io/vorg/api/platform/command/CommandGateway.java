package io.vorg.api.platform.command;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vorg.api.platform.Ids;
import io.vorg.api.platform.audit.AuditEntry;
import io.vorg.api.platform.audit.AuditService;
import io.vorg.api.platform.authz.Decision;
import io.vorg.api.platform.authz.PolicyService;
import io.vorg.api.platform.events.EnvelopeCodec;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.CorrelationContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The only door through which state changes enter the platform (ADR-02, §16). One call:
 * <ol>
 *   <li>requires an {@code Idempotency-Key} (400 otherwise);</li>
 *   <li>inserts the command record; a repeat of the same (tenant, key) returns the stored ack when the canonical payload
 *       hash matches (same command id, HTTP 200) and 409 when it does not;</li>
 *   <li>authorizes through {@link PolicyService} (a denial persists REJECTED plus an audit DENIED row and yields 403);</li>
 *   <li>runs the handler in the same transaction as the command record, so state, events and outbox rows commit or
 *       roll back together, then records SUCCEEDED and the audit ALLOWED row.</li>
 * </ol>
 * A handler exception rolls the whole transaction back (no state, no event) and the gateway then persists a FAILED
 * command record in a new transaction (with the audit row), so the key stays bound to its outcome, and rethrows.
 * Concurrent duplicates serialize on the unique (tenant_id, idempotency_key) index.
 */
@Service
public class CommandGateway {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:\\-]{1,200}$");

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final AuditService audit;
    private final EnvelopeCodec codec;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;

    public CommandGateway(JdbcClient jdbc, PolicyService policy, AuditService audit, EnvelopeCodec codec,
            MeterRegistry meters, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.audit = audit;
        this.codec = codec;
        this.meters = meters;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private enum Kind { SUCCEEDED, REPLAYED, REJECTED, REJECTED_REPLAY, CONFLICT, FAILED }

    private record Outcome(Kind kind, CommandAck ack, String detail, RuntimeException failure) {}

    /** How a command is authorized and audited: a human subject through the policy engine, or a service credential. */
    private record Mode(String actorType, String auditAction, Function<UUID, Decision> authorizer) {}

    public CommandAck execute(CommandSpec spec, Supplier<CommandResult> handler) {
        return execute(spec, new Mode("HUMAN", spec.action().name(), commandId -> policy.authorizeAudited(
                spec.subject(), spec.action(), spec.resource(), spec.policyContext(), commandId)), handler);
    }

    /**
     * A command sent by an authenticated service credential (ADR-69), not a human: same idempotency, transaction and
     * command record, but authorization is the credential's own scope check ({@code authorized}, decided by the
     * caller from the server-loaded resource) instead of the role matrix, and the audit rows carry actor type
     * {@code SERVICE} and {@code auditAction}. {@code spec.subject()} is the service's pseudonymous actor (no roles).
     */
    public CommandAck executeAsService(CommandSpec spec, String auditAction, Decision authorized,
            Supplier<CommandResult> handler) {
        return execute(spec, new Mode("SERVICE", auditAction, commandId -> authorized), handler);
    }

    private CommandAck execute(CommandSpec spec, Mode mode, Supplier<CommandResult> handler) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            CommandAck ack = TenantContext.callWith(spec.subject().tenantId(), () -> run(spec, mode, handler));
            outcome = ack.status().equals("SUCCEEDED") ? "succeeded" : ack.status().toLowerCase();
            return ack;
        } catch (ApiProblemException ex) {
            outcome = "problem_" + ex.status().value();
            throw ex;
        } finally {
            Timer.builder("vo.command.ack").description("Time to acknowledge a command")
                    .tag("outcome", outcome).publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(30))
                    .register(meters).record(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private CommandAck run(CommandSpec spec, Mode mode, Supplier<CommandResult> handler) {
        String key = requireKey(spec.idempotencyKey());
        UUID tenantId = spec.subject().tenantId();
        UUID correlationId = CorrelationContext.current().map(UUID::fromString).orElseGet(Ids::newId);
        String canonicalPayload = codec.write(canonical(spec.payload()));
        String hash = sha256(spec.commandType() + "\n" + canonicalPayload);

        Outcome outcome = tx.execute(status -> attempt(status, spec, mode, handler, key, tenantId, correlationId,
                canonicalPayload, hash));
        if (outcome == null) {
            throw new IllegalStateException("Command transaction returned no outcome");
        }
        return switch (outcome.kind()) {
            case SUCCEEDED, REPLAYED -> outcome.ack();
            case CONFLICT -> throw new ApiProblemException(HttpStatus.CONFLICT, "Idempotency-Key conflict",
                    "This Idempotency-Key was already used with a different request payload.");
            case REJECTED, REJECTED_REPLAY -> throw new ApiProblemException(HttpStatus.FORBIDDEN, "Forbidden",
                    outcome.detail());
            case FAILED -> {
                recordFailure(spec, mode, key, tenantId, correlationId, canonicalPayload, hash, outcome.failure());
                throw outcome.failure();
            }
        };
    }

    private Outcome attempt(org.springframework.transaction.TransactionStatus status, CommandSpec spec, Mode mode,
            Supplier<CommandResult> handler, String key, UUID tenantId, UUID correlationId, String canonicalPayload,
            String hash) {
        UUID commandId = Ids.newId();
        int inserted = insertCommand(commandId, tenantId, key, spec, canonicalPayload, hash, "ACCEPTED", null,
                correlationId);
        if (inserted == 0) {
            return replay(tenantId, key, hash);
        }

        Decision decision = mode.authorizer().apply(commandId);
        if (!decision.allowed()) {
            complete(commandId, "REJECTED", codec.write(Map.of("code", decision.code(), "reason", decision.reason())));
            return new Outcome(Kind.REJECTED, null, decision.reason(), null);
        }

        CommandResult result;
        try {
            result = handler.get();
        } catch (RuntimeException ex) {
            status.setRollbackOnly();
            return new Outcome(Kind.FAILED, null, null, ex);
        }
        JsonNode resultNode = codec.toTree(result.data());
        complete(commandId, "SUCCEEDED", resultNode.toString());
        audit.record(allowedEntry(spec, mode, tenantId, commandId, correlationId, Map.of("outcome", "SUCCEEDED")));
        return new Outcome(Kind.SUCCEEDED, new CommandAck(commandId, "SUCCEEDED", correlationId, resultNode), null,
                null);
    }

    private Outcome replay(UUID tenantId, String key, String hash) {
        record Stored(UUID commandId, String hash, String status, String result, UUID correlationId) {}
        Stored stored = jdbc.sql("""
                select command_id, payload_hash, status, result::text as result, correlation_id
                from cmd.commands where tenant_id = :tenant and idempotency_key = :key
                """)
                .param("tenant", tenantId).param("key", key)
                .query((rs, n) -> new Stored(rs.getObject("command_id", UUID.class), rs.getString("payload_hash"),
                        rs.getString("status"), rs.getString("result"), rs.getObject("correlation_id", UUID.class)))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Idempotency conflict without a visible command row"));
        if (!stored.hash().equals(hash)) {
            return new Outcome(Kind.CONFLICT, null, null, null);
        }
        JsonNode result = stored.result() == null ? null : codec.readTree(stored.result());
        if ("REJECTED".equals(stored.status())) {
            String reason = result != null && result.hasNonNull("reason") ? result.get("reason").asString()
                    : "Access is denied.";
            return new Outcome(Kind.REJECTED_REPLAY, null, reason, null);
        }
        return new Outcome(Kind.REPLAYED,
                new CommandAck(stored.commandId(), stored.status(), stored.correlationId(), result), null, null);
    }

    private void recordFailure(CommandSpec spec, Mode mode, String key, UUID tenantId, UUID correlationId,
            String canonicalPayload, String hash, RuntimeException failure) {
        String error = failure.getClass().getSimpleName();
        tx.executeWithoutResult(status -> {
            UUID commandId = Ids.newId();
            int inserted = insertCommand(commandId, tenantId, key, spec, canonicalPayload, hash, "FAILED",
                    codec.write(Map.of("error", error)), correlationId);
            if (inserted == 1) {
                audit.record(allowedEntry(spec, mode, tenantId, commandId, correlationId,
                        Map.of("outcome", "FAILED", "error", error)));
            }
        });
    }

    private AuditEntry allowedEntry(CommandSpec spec, Mode mode, UUID tenantId, UUID commandId, UUID correlationId,
            Map<String, Object> details) {
        Map<String, Object> merged = new LinkedHashMap<>(details);
        merged.put("command_type", spec.commandType());
        return new AuditEntry(tenantId, spec.subject().actorRef().toString(), mode.actorType(), mode.auditAction(),
                spec.resource().kind().name(), spec.resource().siteId(), AuditEntry.Decision.ALLOWED,
                "Allowed by policy", commandId, correlationId, merged);
    }

    private int insertCommand(UUID commandId, UUID tenantId, String key, CommandSpec spec, String canonicalPayload,
            String hash, String status, String resultJson, UUID correlationId) {
        return jdbc.sql("""
                insert into cmd.commands (command_id, tenant_id, idempotency_key, actor_ref, command_type, payload,
                    payload_hash, status, result, correlation_id, completed_at)
                values (:id, :tenant, :key, :actor, :type, cast(:payload as jsonb), :hash, :status,
                    cast(:result as jsonb), :correlation, case when :status = 'ACCEPTED' then null else now() end)
                on conflict (tenant_id, idempotency_key) do nothing
                """)
                .param("id", commandId).param("tenant", tenantId).param("key", key)
                .param("actor", spec.subject().actorRef().toString()).param("type", spec.commandType())
                .param("payload", canonicalPayload).param("hash", hash).param("status", status)
                .param("result", resultJson).param("correlation", correlationId)
                .update();
    }

    private void complete(UUID commandId, String status, String resultJson) {
        jdbc.sql("""
                update cmd.commands set status = :status, result = cast(:result as jsonb), completed_at = now()
                where command_id = :id
                """)
                .param("status", status).param("result", resultJson).param("id", commandId).update();
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Idempotency-Key required",
                    "Commands require an Idempotency-Key header.",
                    List.of(new ApiProblemException.FieldError("Idempotency-Key", "is required")));
        }
        String trimmed = key.trim();
        if (!KEY_PATTERN.matcher(trimmed).matches()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Idempotency-Key invalid",
                    "Idempotency-Key must be 1 to 200 characters of letters, digits, '.', '_', ':' or '-'.",
                    List.of(new ApiProblemException.FieldError("Idempotency-Key", "has an invalid format")));
        }
        return trimmed;
    }

    /** Key-sorted deep copy so equal payloads serialize identically regardless of insertion order. */
    @SuppressWarnings("unchecked")
    static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> {
                if (v != null) {
                    sorted.put(String.valueOf(k), canonical(v));
                }
            });
            return sorted;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> out = new java.util.ArrayList<>();
            items.forEach(i -> out.add(canonical(i)));
            return out;
        }
        return value;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
