package io.vorg.api.recommendations;

import io.vorg.api.platform.web.KeysetCursor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL for {@code rec.recommendations} and {@code rec.approvals} on the application connection ({@code vo_app}, forced
 * RLS). Every statement also filters by tenant explicitly. Callers that change state lock the row first
 * ({@code forUpdate}) and bump {@code version}; nothing is ever deleted.
 */
@Component
public class RecommendationStore {

    /** A stored recommendation with its JSON columns parsed (keys exactly as stored, i.e. snake_case). */
    public record RecommendationView(UUID id, UUID siteId, String type, RecommendationStatus status, String rationale,
            List<Object> evidenceRefs, Map<String, Object> feasibility, Map<String, Object> objectiveBefore,
            Map<String, Object> objectiveAfter, List<Object> constraints, List<String> affectedEntities,
            Map<String, Object> baselineEntityVersions, Map<String, Object> policyVersions,
            Map<String, Object> executionPlan, Map<String, Object> requiresApproval, Instant expiresAt,
            UUID approvalId, UUID simulationRunId, UUID scenarioId, UUID proposedBy, UUID supersedesId,
            Map<String, Object> execution, String dataMode, int version, Instant createdAt, Instant updatedAt) {}

    public record ApprovalView(UUID id, UUID siteId, UUID recommendationId, ApprovalStatus status,
            ApprovalClass approvalClass, boolean separationOfDuties, String policyName, int policyVersion,
            List<String> approverRoles, UUID requestedBy, UUID decidedBy, Instant decidedAt, String reason,
            Instant expiresAt, int version, Instant createdAt, Instant updatedAt) {}

    /** Column values of a new recommendation; JSON columns already serialized. */
    record NewRecommendation(UUID id, UUID tenantId, UUID siteId, String type, String rationale, String evidenceRefs,
            String feasibility, String objectiveBefore, String objectiveAfter, String constraints,
            String affectedEntities, String baselineEntityVersions, String policyVersions, String executionPlan,
            String requiresApproval, Instant expiresAt, UUID approvalId, UUID simulationRunId, UUID scenarioId,
            UUID proposedBy, UUID supersedesId, String dataMode, Instant createdAt) {}

    record NewApproval(UUID id, UUID tenantId, UUID siteId, UUID recommendationId, ApprovalClass approvalClass,
            boolean separationOfDuties, String policyName, int policyVersion, String approverRoles,
            UUID requestedBy, Instant expiresAt, Instant createdAt) {}

    private static final String REC_SELECT = """
            select id, site_id, type, status, rationale, evidence_refs::text as evidence_refs,
                   feasibility::text as feasibility, objective_before::text as objective_before,
                   objective_after::text as objective_after, constraints::text as constraints,
                   affected_entities::text as affected_entities,
                   baseline_entity_versions::text as baseline_entity_versions,
                   policy_versions::text as policy_versions, execution_plan::text as execution_plan,
                   requires_approval::text as requires_approval, expires_at, approval_id, simulation_run_id,
                   scenario_id, proposed_by, supersedes_id, execution::text as execution, data_mode, version,
                   created_at, updated_at
            from rec.recommendations
            """;

    private static final String APPROVAL_SELECT = """
            select id, site_id, recommendation_id, status, approval_class, separation_of_duties, policy_name,
                   policy_version, approver_roles::text as approver_roles, requested_by, decided_by, decided_at,
                   reason, expires_at, version, created_at, updated_at
            from rec.approvals
            """;

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public RecommendationStore(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---- recommendations ----------------------------------------------------------------------------------------

    public Optional<RecommendationView> findRecommendation(UUID tenantId, UUID id, boolean forUpdate) {
        return jdbc.sql(REC_SELECT + " where tenant_id = :t and id = :id" + (forUpdate ? " for update" : ""))
                .param("t", tenantId).param("id", id).query((rs, n) -> recommendation(rs)).optional();
    }

    public List<RecommendationView> listRecommendations(UUID tenantId, UUID siteId, RecommendationStatus status,
            KeysetCursor after, int limit) {
        StringBuilder sql = new StringBuilder(REC_SELECT).append(" where tenant_id = :t and site_id = :s");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("t", tenantId);
        params.put("s", siteId);
        if (status != null) {
            sql.append(" and status = :status");
            params.put("status", status.name());
        }
        page(sql, params, after, limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> recommendation(rs)).list();
    }

    /** Serializes proposals from one simulation run (held to the end of the transaction). */
    void lockRun(UUID tenantId, UUID simulationRunId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))")
                .param("k", "rec:" + tenantId + ":" + simulationRunId).query((rs, n) -> 1).list();
    }

    /** The most recent recommendation proposed from a run, locked. */
    Optional<RecommendationView> latestForRun(UUID tenantId, UUID simulationRunId) {
        return jdbc.sql(REC_SELECT + """
                 where tenant_id = :t and simulation_run_id = :run
                order by created_at desc, id desc limit 1 for update
                """).param("t", tenantId).param("run", simulationRunId).query((rs, n) -> recommendation(rs))
                .optional();
    }

    void insert(NewRecommendation r) {
        jdbc.sql("""
                insert into rec.recommendations (id, tenant_id, site_id, type, status, rationale, evidence_refs,
                    feasibility, objective_before, objective_after, constraints, affected_entities,
                    baseline_entity_versions, policy_versions, execution_plan, requires_approval, expires_at,
                    approval_id, simulation_run_id, scenario_id, proposed_by, supersedes_id, data_mode, version,
                    created_at, updated_at)
                values (:id, :t, :s, :type, 'PROPOSED', :rationale, cast(:evidence as jsonb),
                    cast(:feasibility as jsonb), cast(:before as jsonb), cast(:after as jsonb),
                    cast(:constraints as jsonb), cast(:affected as jsonb), cast(:baseline as jsonb),
                    cast(:policies as jsonb), cast(:plan as jsonb), cast(:approval_req as jsonb), :expires,
                    :approval, :run, :scenario, :proposer, :supersedes, :dm, 1, :at, :at)
                """)
                .param("id", r.id()).param("t", r.tenantId()).param("s", r.siteId()).param("type", r.type())
                .param("rationale", r.rationale()).param("evidence", r.evidenceRefs())
                .param("feasibility", r.feasibility()).param("before", r.objectiveBefore())
                .param("after", r.objectiveAfter()).param("constraints", r.constraints())
                .param("affected", r.affectedEntities()).param("baseline", r.baselineEntityVersions())
                .param("policies", r.policyVersions()).param("plan", r.executionPlan())
                .param("approval_req", r.requiresApproval()).param("expires", utc(r.expiresAt()))
                .param("approval", r.approvalId()).param("run", r.simulationRunId())
                .param("scenario", r.scenarioId()).param("proposer", r.proposedBy())
                .param("supersedes", r.supersedesId()).param("dm", r.dataMode()).param("at", utc(r.createdAt()))
                .update();
    }

    /** Moves a recommendation to {@code next} (version + 1); returns the new version. */
    int updateStatus(UUID tenantId, RecommendationView current, RecommendationStatus next, Instant at) {
        if (!current.status().canMoveTo(next)) {
            throw new IllegalStateException("Illegal recommendation transition " + current.status() + " -> " + next);
        }
        int updated = jdbc.sql("""
                update rec.recommendations set status = :status, version = version + 1, updated_at = :at
                where tenant_id = :t and id = :id and version = :v
                """).param("status", next.name()).param("at", utc(at)).param("t", tenantId)
                .param("id", current.id()).param("v", current.version()).update();
        if (updated != 1) {
            throw new IllegalStateException("Recommendation " + current.id() + " changed concurrently");
        }
        return current.version() + 1;
    }

    // ---- approvals ----------------------------------------------------------------------------------------------

    public Optional<ApprovalView> findApproval(UUID tenantId, UUID id, boolean forUpdate) {
        return jdbc.sql(APPROVAL_SELECT + " where tenant_id = :t and id = :id" + (forUpdate ? " for update" : ""))
                .param("t", tenantId).param("id", id).query((rs, n) -> approval(rs)).optional();
    }

    public List<ApprovalView> listApprovals(UUID tenantId, UUID siteId, ApprovalStatus status, KeysetCursor after,
            int limit) {
        StringBuilder sql = new StringBuilder(APPROVAL_SELECT).append(" where tenant_id = :t and site_id = :s");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("t", tenantId);
        params.put("s", siteId);
        if (status != null) {
            sql.append(" and status = :status");
            params.put("status", status.name());
        }
        page(sql, params, after, limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> approval(rs)).list();
    }

    void insert(NewApproval a) {
        jdbc.sql("""
                insert into rec.approvals (id, tenant_id, site_id, recommendation_id, status, approval_class,
                    separation_of_duties, policy_name, policy_version, approver_roles, requested_by, expires_at,
                    version, created_at, updated_at)
                values (:id, :t, :s, :rec, 'PENDING', :class, :sod, :policy, :pv, cast(:roles as jsonb), :by,
                    :expires, 1, :at, :at)
                """)
                .param("id", a.id()).param("t", a.tenantId()).param("s", a.siteId())
                .param("rec", a.recommendationId()).param("class", a.approvalClass().name())
                .param("sod", a.separationOfDuties()).param("policy", a.policyName())
                .param("pv", a.policyVersion()).param("roles", a.approverRoles()).param("by", a.requestedBy())
                .param("expires", utc(a.expiresAt())).param("at", utc(a.createdAt())).update();
    }

    /** Records the outcome of a PENDING approval (version + 1); returns the new version. */
    int decide(UUID tenantId, ApprovalView current, ApprovalStatus next, UUID decidedBy, String reason, Instant at) {
        if (!current.status().canMoveTo(next)) {
            throw new IllegalStateException("Illegal approval transition " + current.status() + " -> " + next);
        }
        int updated = jdbc.sql("""
                update rec.approvals set status = :status, decided_by = :by, decided_at = :decided,
                    reason = :reason, version = version + 1, updated_at = :at
                where tenant_id = :t and id = :id and version = :v
                """).param("status", next.name()).param("by", decidedBy)
                .param("decided", decidedBy == null ? null : utc(at)).param("reason", reason)
                .param("at", utc(at)).param("t", tenantId).param("id", current.id()).param("v", current.version())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Approval " + current.id() + " changed concurrently");
        }
        return current.version() + 1;
    }

    // ---- mapping ------------------------------------------------------------------------------------------------

    private static void page(StringBuilder sql, Map<String, Object> params, KeysetCursor after, int limit) {
        if (after != null) {
            sql.append(" and (created_at, id) < (:at, :aid)");
            params.put("at", after.atUtc());
            params.put("aid", after.id());
        }
        sql.append(" order by created_at desc, id desc limit :lim");
        params.put("lim", limit);
    }

    @SuppressWarnings("unchecked")
    private RecommendationView recommendation(ResultSet rs) throws SQLException {
        String execution = rs.getString("execution");
        return new RecommendationView(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getString("type"), RecommendationStatus.valueOf(rs.getString("status")),
                rs.getString("rationale"), mapper.readValue(rs.getString("evidence_refs"), List.class),
                map(rs, "feasibility"), map(rs, "objective_before"), map(rs, "objective_after"),
                mapper.readValue(rs.getString("constraints"), List.class),
                mapper.readValue(rs.getString("affected_entities"), List.class), map(rs, "baseline_entity_versions"),
                map(rs, "policy_versions"), map(rs, "execution_plan"), map(rs, "requires_approval"),
                instant(rs, "expires_at"), rs.getObject("approval_id", UUID.class),
                rs.getObject("simulation_run_id", UUID.class), rs.getObject("scenario_id", UUID.class),
                rs.getObject("proposed_by", UUID.class), rs.getObject("supersedes_id", UUID.class),
                execution == null ? null : mapper.readValue(execution, Map.class), rs.getString("data_mode"),
                rs.getInt("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    @SuppressWarnings("unchecked")
    private ApprovalView approval(ResultSet rs) throws SQLException {
        return new ApprovalView(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getObject("recommendation_id", UUID.class), ApprovalStatus.valueOf(rs.getString("status")),
                ApprovalClass.valueOf(rs.getString("approval_class")), rs.getBoolean("separation_of_duties"),
                rs.getString("policy_name"), rs.getInt("policy_version"),
                mapper.readValue(rs.getString("approver_roles"), List.class), rs.getObject("requested_by", UUID.class),
                rs.getObject("decided_by", UUID.class), instant(rs, "decided_at"), rs.getString("reason"),
                instant(rs, "expires_at"), rs.getInt("version"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(ResultSet rs, String column) throws SQLException {
        return mapper.readValue(rs.getString(column), Map.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static OffsetDateTime utc(Instant at) {
        return at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
