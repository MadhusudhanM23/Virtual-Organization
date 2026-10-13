package io.vorg.api.scenarios;

import io.vorg.api.platform.Ids;
import io.vorg.api.platform.command.CommandResult;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.simulation.warehouse.Scenario;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scenario writes (REQ-070/071). Runs on the {@code vo_sim} connection only (see {@link SimConfig}) in its own short
 * transaction — never the primary datasource — so the command gateway's outer transaction (on {@code vo_app}) never
 * needs write access to the {@code sim} schema and a scenario write can never be part of the same atomic unit as a
 * live-state write. That is a deliberate consequence of the isolation design (ADR-60), not an oversight.
 */
@Service
public class ScenarioService {

    private final JdbcClient simJdbc;
    private final TransactionTemplate simTx;
    private final JsonMapper mapper;

    public ScenarioService(@Qualifier("simDataSource") DataSource simDataSource, JsonMapper mapper) {
        this.simJdbc = JdbcClient.create(simDataSource);
        this.simTx = new TransactionTemplate(new JdbcTransactionManager(simDataSource));
        this.mapper = mapper;
    }

    /** Builds, validates and stores a new scenario; returns the command result (echoes the created view). */
    public CommandResult create(UUID tenantId, UUID siteId, UUID createdBy, String name, String description,
            Map<String, Object> overlay, Map<String, Object> run) {
        UUID id = Ids.newId();
        Scenario scenario = ScenarioJson.build(id.toString(), name, description, overlay, run);
        Map<String, Object> scenarioDoc = ScenarioJson.toDocument(scenario);
        String scenarioJson = mapper.writeValueAsString(scenarioDoc);
        String overlayJson = mapper.writeValueAsString(scenario.overlay().toMap());
        String runJson = mapper.writeValueAsString(scenario.run().toMap());
        String affectedJson = mapper.writeValueAsString(scenario.overlay().affectedEntities());

        return simTx.execute(status -> {
            simJdbc.sql("""
                    insert into sim.scenarios (id, tenant_id, site_id, name, description, baseline_ref, overlay,
                        run_settings, scenario_json, affected_entities, created_by)
                    values (:id, :tenant, :site, :name, :desc, :baseline, cast(:overlay as jsonb),
                        cast(:run as jsonb), cast(:doc as jsonb), cast(:affected as jsonb), :by)
                    """)
                    .param("id", id).param("tenant", tenantId).param("site", siteId).param("name", scenario.name())
                    .param("desc", scenario.description()).param("baseline", scenario.baselineRef())
                    .param("overlay", overlayJson).param("run", runJson).param("doc", scenarioJson)
                    .param("affected", affectedJson).param("by", createdBy).update();
            ScenarioQueryService.ScenarioView view = ScenarioQueryService.row(simJdbc, mapper, tenantId, id)
                    .orElseThrow(() -> new IllegalStateException("scenario not visible after insert"));
            return CommandResult.of(ScenarioQueryService.toMap(view));
        });
    }

    /** Loads the stored scenario document for a run; 404 if it does not exist (RLS hides other tenants). */
    @SuppressWarnings("unchecked")
    Map<String, Object> loadScenarioDoc(UUID tenantId, UUID scenarioId) {
        return simJdbc.sql("select scenario_json from sim.scenarios where id = :id and tenant_id = :tenant")
                .param("id", scenarioId).param("tenant", tenantId)
                .query((rs, n) -> mapper.readValue(rs.getString(1), Map.class)).optional()
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "Not found",
                        "The requested scenario was not found."));
    }
}
