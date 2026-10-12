package io.vorg.api.platform.audit;

import io.vorg.api.platform.Ids;
import io.vorg.api.platform.events.EnvelopeCodec;
import io.vorg.api.platform.web.CorrelationContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Append-only audit writer (§18). It only ever inserts; the database additionally rejects UPDATE, DELETE and TRUNCATE
 * (trigger, and vo_app holds no such privilege). It joins the caller's transaction when there is one, so an audit row
 * for an allowed command commits or rolls back with the command, while a denial recorded in a committing transaction
 * survives the request failing with 403.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final EnvelopeCodec codec;

    public AuditService(JdbcClient jdbc, EnvelopeCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public UUID record(AuditEntry entry) {
        UUID id = Ids.newId();
        UUID correlation = entry.correlationId() != null ? entry.correlationId()
                : CorrelationContext.current().map(UUID::fromString).orElse(null);
        jdbc.sql("""
                insert into audit.audit_log (id, tenant_id, actor_ref, actor_type, action, resource_type, resource_id,
                    decision, reason, command_id, correlation_id, details)
                values (:id, :tenant_id, :actor_ref, :actor_type, :action, :resource_type, :resource_id,
                    :decision, :reason, :command_id, :correlation_id, cast(:details as jsonb))
                """)
                .param("id", id)
                .param("tenant_id", entry.tenantId())
                .param("actor_ref", entry.actorRef())
                .param("actor_type", entry.actorType())
                .param("action", entry.action())
                .param("resource_type", entry.resourceType())
                .param("resource_id", entry.resourceId())
                .param("decision", entry.decision().name())
                .param("reason", entry.reason())
                .param("command_id", entry.commandId())
                .param("correlation_id", correlation)
                .param("details", entry.details() == null ? null : codec.write(entry.details()))
                .update();
        return id;
    }
}
