package io.vorg.api.scenarios;

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
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Scenario reads (REQ-070). Visibility: {@code CREATE_SIMULATION} on the site ({@link ScenarioAccess}); tenant
 * boundary is RLS on the {@code vo_sim} connection, same as every other tenant table. */
@Service
public class ScenarioQueryService {

    public record ScenarioView(UUID id, UUID siteId, String name, String description, String baselineRef,
            Map<String, Object> overlay, Map<String, Object> runSettings, List<String> affectedEntities,
            UUID createdBy, Instant createdAt, Instant updatedAt, int version) {}

    private static final String SELECT = """
            select id, site_id, name, description, baseline_ref, overlay::text as overlay,
                   run_settings::text as run_settings, affected_entities::text as affected_entities, created_by,
                   created_at, updated_at, version
            from sim.scenarios
            """;

    private final JdbcClient simJdbc;
    private final JsonMapper mapper;
    private final ScenarioAccess access;

    public ScenarioQueryService(@Qualifier("simDataSource") DataSource simDataSource, JsonMapper mapper,
            ScenarioAccess access) {
        this.simJdbc = JdbcClient.create(simDataSource);
        this.mapper = mapper;
        this.access = access;
    }

    public Page<ScenarioView> list(Subject subject, UUID siteId, Integer limit, String cursor) {
        access.require(subject, siteId);
        int lim = PageParams.limit(limit);
        KeysetCursor after = KeysetCursor.decode(cursor);
        StringBuilder sql = new StringBuilder(SELECT).append(" where site_id = :site");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("site", siteId);
        if (after != null) {
            sql.append(" and (created_at, id) < (:at, :aid)");
            params.put("at", after.atUtc());
            params.put("aid", after.id());
        }
        sql.append(" order by created_at desc, id desc limit :lim");
        params.put("lim", lim + 1);
        List<ScenarioView> rows = simJdbc.sql(sql.toString()).params(params).query((rs, n) -> view(rs, mapper))
                .list();
        if (rows.size() <= lim) {
            return new Page<>(rows, null);
        }
        List<ScenarioView> items = new ArrayList<>(rows.subList(0, lim));
        ScenarioView last = items.get(items.size() - 1);
        return new Page<>(items, KeysetCursor.encode(last.createdAt(), last.id()));
    }

    public ScenarioView get(Subject subject, UUID id) {
        ScenarioView scenario = row(simJdbc, mapper, subject.tenantId(), id)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "Not found",
                        "The requested scenario was not found."));
        access.require(subject, scenario.siteId());
        return scenario;
    }

    static Optional<ScenarioView> row(JdbcClient simJdbc, JsonMapper mapper, UUID tenantId, UUID id) {
        return simJdbc.sql(SELECT + " where id = :id and tenant_id = :tenant").param("id", id)
                .param("tenant", tenantId).query((rs, n) -> view(rs, mapper)).optional();
    }

    @SuppressWarnings("unchecked")
    private static ScenarioView view(ResultSet rs, JsonMapper mapper) throws SQLException {
        return new ScenarioView(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getString("name"), rs.getString("description"), rs.getString("baseline_ref"),
                mapper.readValue(rs.getString("overlay"), Map.class),
                mapper.readValue(rs.getString("run_settings"), Map.class),
                mapper.readValue(rs.getString("affected_entities"), List.class),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getInt("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static Map<String, Object> toMap(ScenarioView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id().toString());
        m.put("site_id", v.siteId().toString());
        m.put("name", v.name());
        m.put("description", v.description());
        m.put("baseline_ref", v.baselineRef());
        m.put("overlay", v.overlay());
        m.put("run_settings", v.runSettings());
        m.put("affected_entities", v.affectedEntities());
        m.put("created_by", v.createdBy().toString());
        m.put("created_at", v.createdAt().toString());
        m.put("updated_at", v.updatedAt() == null ? null : v.updatedAt().toString());
        m.put("version", v.version());
        return m;
    }
}
