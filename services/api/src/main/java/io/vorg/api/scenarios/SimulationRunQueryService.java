package io.vorg.api.scenarios;

import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
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
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Simulation-run reads (REQ-073). Visibility follows the scenario's site ({@code CREATE_SIMULATION}, RLS). */
@Service
public class SimulationRunQueryService {

    public record RunView(UUID id, UUID siteId, UUID scenarioId, String status, UUID requestedBy,
            String engineName, String engineVersion, Map<String, Object> results, String resultsHash,
            String errorMessage, Instant createdAt, Instant startedAt, Instant finishedAt, int version) {}

    private static final String SELECT = """
            select id, site_id, scenario_id, status, requested_by, engine_name, engine_version,
                   results_json::text as results_json, results_hash, error_message, created_at, started_at,
                   finished_at, version
            from sim.simulation_runs
            """;

    private final JdbcClient simJdbc;
    private final JsonMapper mapper;
    private final ScenarioAccess access;
    private final ScenarioQueryService scenarios;

    public SimulationRunQueryService(@Qualifier("simDataSource") DataSource simDataSource, JsonMapper mapper,
            ScenarioAccess access, ScenarioQueryService scenarios) {
        this.simJdbc = JdbcClient.create(simDataSource);
        this.mapper = mapper;
        this.access = access;
        this.scenarios = scenarios;
    }

    public RunView get(Subject subject, UUID id) {
        RunView run = simJdbc.sql(SELECT + " where id = :id and tenant_id = :tenant").param("id", id)
                .param("tenant", subject.tenantId()).query((rs, n) -> view(rs)).optional()
                .orElseThrow(SimulationRunService::notFound);
        access.require(subject, run.siteId());
        return run;
    }

    public Page<RunView> list(Subject subject, UUID scenarioId, UUID siteId, Integer limit, String cursor) {
        UUID effectiveSite = siteId;
        if (scenarioId != null) {
            effectiveSite = scenarios.get(subject, scenarioId).siteId(); // 404/403 via the scenario
        } else if (siteId == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more parameters are invalid.",
                    List.of(new FieldError("site_id", "is required when scenario_id is not given")));
        } else {
            access.require(subject, siteId);
        }
        int lim = PageParams.limit(limit);
        KeysetCursor after = KeysetCursor.decode(cursor);
        StringBuilder sql = new StringBuilder(SELECT).append(" where tenant_id = :tenant and site_id = :site");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenant", subject.tenantId());
        params.put("site", effectiveSite);
        if (scenarioId != null) {
            sql.append(" and scenario_id = :scenario");
            params.put("scenario", scenarioId);
        }
        if (after != null) {
            sql.append(" and (created_at, id) < (:at, :aid)");
            params.put("at", after.atUtc());
            params.put("aid", after.id());
        }
        sql.append(" order by created_at desc, id desc limit :lim");
        params.put("lim", lim + 1);
        List<RunView> rows = simJdbc.sql(sql.toString()).params(params).query((rs, n) -> view(rs)).list();
        if (rows.size() <= lim) {
            return new Page<>(rows, null);
        }
        List<RunView> items = new ArrayList<>(rows.subList(0, lim));
        RunView last = items.get(items.size() - 1);
        return new Page<>(items, KeysetCursor.encode(last.createdAt(), last.id()));
    }

    @SuppressWarnings("unchecked")
    private RunView view(ResultSet rs) throws SQLException {
        String resultsJson = rs.getString("results_json");
        return new RunView(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getObject("scenario_id", UUID.class), rs.getString("status"),
                rs.getObject("requested_by", UUID.class), rs.getString("engine_name"),
                rs.getString("engine_version"), resultsJson == null ? null : mapper.readValue(resultsJson, Map.class),
                rs.getString("results_hash"), rs.getString("error_message"), instant(rs, "created_at"),
                instant(rs, "started_at"), instant(rs, "finished_at"), rs.getInt("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    public static Map<String, Object> toMap(RunView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", v.id().toString());
        m.put("scenario_id", v.scenarioId().toString());
        m.put("site_id", v.siteId().toString());
        m.put("status", v.status());
        m.put("requested_by", v.requestedBy().toString());
        if (v.engineName() != null) {
            m.put("engine", Map.of("name", v.engineName(), "version", v.engineVersion()));
        }
        if (v.results() != null) {
            m.put("results", v.results());
            m.put("results_hash", v.resultsHash());
        }
        if (v.errorMessage() != null) {
            m.put("error_message", v.errorMessage());
        }
        m.put("created_at", v.createdAt().toString());
        if (v.startedAt() != null) {
            m.put("started_at", v.startedAt().toString());
        }
        if (v.finishedAt() != null) {
            m.put("finished_at", v.finishedAt().toString());
        }
        m.put("version", v.version());
        return m;
    }
}
