package io.vorg.api.ai;

import io.vorg.api.platform.Ids;
import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.Decision;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.PolicyService;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.command.CommandResult;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.KeysetCursor;
import io.vorg.api.platform.web.PageParams;
import io.vorg.api.platform.web.PageParams.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI assistant exchange records (REQ-101, REQ-106, ADR-65). {@code services/ai} records every exchange with the
 * caller's own token; the actor is always the token subject and the tenant always the token tenant. Authorization is
 * {@code VIEW_OPERATIONAL_STATE} on the site the assistant answered for (every assistant tool reads that site). Reads
 * return only the caller's own exchanges.
 */
@Service
public class AiExchangeService {

    /** A validated exchange as the controller hands it over (already checked for secrets and sizes). */
    public record Exchange(UUID clientExchangeId, UUID siteId, UUID conversationId, String provider, String model,
            boolean external, String providerReason, String retention, String questionSha256, String questionText,
            String answerText, int promptTokens, int completionTokens, String tokenCountMethod,
            List<Object> toolCalls, List<Object> citations, Map<String, Object> redactions, List<Object> flags,
            boolean grounded, int latencyMs) {}

    public record ExchangeView(UUID id, UUID exchangeId, UUID siteId, UUID conversationId, String provider,
            String model, boolean external, String providerReason, String retention, String questionText,
            String answerText, int promptTokens, int completionTokens, String tokenCountMethod,
            List<Object> toolCalls, List<Object> citations, Map<String, Object> redactions, List<Object> flags,
            boolean grounded, int latencyMs, Instant createdAt) {}

    private static final String SELECT = """
            select id, client_exchange_id, site_id, conversation_id, provider, model, external, provider_reason,
                   retention, question_text, answer_text, prompt_tokens, completion_tokens, token_count_method,
                   tool_calls::text as tool_calls, citations::text as citations, redactions::text as redactions,
                   flags::text as flags, grounded, latency_ms, created_at
            from ai.exchanges
            """;

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final PolicyService policy;

    public AiExchangeService(JdbcClient jdbc, JsonMapper mapper, PolicyService policy) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.policy = policy;
    }

    /** Non-audited pre-flight (unknown site -> 404); the command gateway runs and audits the real decision. */
    public Decision check(Subject subject, UUID siteId) {
        return policy.authorize(subject, Action.VIEW_OPERATIONAL_STATE, ResourceRef.site(subject.tenantId(), siteId),
                PolicyContext.none());
    }

    /** Inserts the record inside the command gateway's transaction. */
    public CommandResult record(Subject subject, Exchange e) {
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into ai.exchanges (id, tenant_id, site_id, actor_ref, client_exchange_id, conversation_id,
                    provider, model, external, provider_reason, retention, question_sha256, question_text,
                    answer_text, prompt_tokens, completion_tokens, token_count_method, tool_calls, citations,
                    redactions, flags, grounded, latency_ms)
                values (:id, :tenant, :site, :actor, :client, :conv, :provider, :model, :external, :reason,
                    :retention, :sha, :q, :a, :pt, :ct, :method, cast(:tools as jsonb), cast(:cites as jsonb),
                    cast(:red as jsonb), cast(:flags as jsonb), :grounded, :latency)
                """)
                .param("id", id).param("tenant", subject.tenantId()).param("site", e.siteId())
                .param("actor", subject.actorRef()).param("client", e.clientExchangeId())
                .param("conv", e.conversationId()).param("provider", e.provider()).param("model", e.model())
                .param("external", e.external()).param("reason", e.providerReason())
                .param("retention", e.retention()).param("sha", e.questionSha256())
                .param("q", e.questionText()).param("a", e.answerText()).param("pt", e.promptTokens())
                .param("ct", e.completionTokens()).param("method", e.tokenCountMethod())
                .param("tools", mapper.writeValueAsString(e.toolCalls()))
                .param("cites", mapper.writeValueAsString(e.citations()))
                .param("red", mapper.writeValueAsString(e.redactions()))
                .param("flags", mapper.writeValueAsString(e.flags())).param("grounded", e.grounded())
                .param("latency", e.latencyMs()).update();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id.toString());
        result.put("exchange_id", e.clientExchangeId().toString());
        result.put("conversation_id", e.conversationId().toString());
        result.put("site_id", e.siteId().toString());
        result.put("provider", e.provider());
        return CommandResult.of(result);
    }

    /** The caller's own exchanges on a site, newest first. */
    public Page<ExchangeView> listOwn(Subject subject, UUID siteId, Integer limit, String cursor) {
        Decision decision = policy.authorizeAudited(subject, Action.VIEW_OPERATIONAL_STATE,
                ResourceRef.site(subject.tenantId(), siteId), PolicyContext.none(), null);
        if (!decision.allowed()) {
            throw denied(decision);
        }
        int lim = PageParams.limit(limit);
        KeysetCursor after = KeysetCursor.decode(cursor);
        StringBuilder sql = new StringBuilder(SELECT)
                .append(" where tenant_id = :tenant and site_id = :site and actor_ref = :actor");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenant", subject.tenantId());
        params.put("site", siteId);
        params.put("actor", subject.actorRef());
        if (after != null) {
            sql.append(" and (created_at, id) < (:at, :aid)");
            params.put("at", after.atUtc());
            params.put("aid", after.id());
        }
        sql.append(" order by created_at desc, id desc limit :lim");
        params.put("lim", lim + 1);
        List<ExchangeView> rows = jdbc.sql(sql.toString()).params(params).query(this::view).list();
        if (rows.size() <= lim) {
            return new Page<>(rows, null);
        }
        List<ExchangeView> items = new ArrayList<>(rows.subList(0, lim));
        ExchangeView last = items.get(items.size() - 1);
        return new Page<>(items, KeysetCursor.encode(last.createdAt(), last.id()));
    }

    public static ApiProblemException denied(Decision decision) {
        if ("DENY_UNKNOWN_RESOURCE".equals(decision.code())) {
            return new ApiProblemException(HttpStatus.NOT_FOUND, "Not found", "The requested site was not found.");
        }
        return new ApiProblemException(HttpStatus.FORBIDDEN, "Forbidden",
                "You are not allowed to use the assistant for this site.");
    }

    @SuppressWarnings("unchecked")
    private ExchangeView view(ResultSet rs, int n) throws SQLException {
        OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
        return new ExchangeView(rs.getObject("id", UUID.class), rs.getObject("client_exchange_id", UUID.class),
                rs.getObject("site_id", UUID.class), rs.getObject("conversation_id", UUID.class),
                rs.getString("provider"), rs.getString("model"), rs.getBoolean("external"),
                rs.getString("provider_reason"), rs.getString("retention"), rs.getString("question_text"),
                rs.getString("answer_text"), rs.getInt("prompt_tokens"), rs.getInt("completion_tokens"),
                rs.getString("token_count_method"), mapper.readValue(rs.getString("tool_calls"), List.class),
                mapper.readValue(rs.getString("citations"), List.class),
                mapper.readValue(rs.getString("redactions"), Map.class),
                mapper.readValue(rs.getString("flags"), List.class), rs.getBoolean("grounded"),
                rs.getInt("latency_ms"), created == null ? null : created.toInstant());
    }
}
