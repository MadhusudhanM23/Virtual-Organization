package io.vorg.api.scenarios;

import io.vorg.api.platform.Ids;
import io.vorg.api.platform.command.CommandResult;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.simulation.Engine;
import io.vorg.simulation.warehouse.Comparison;
import io.vorg.simulation.warehouse.InvalidInputException;
import io.vorg.simulation.warehouse.Scenario;
import io.vorg.simulation.warehouse.SimulationRunner;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Simulation runs (REQ-072..074). A run never touches live state: creation inserts a {@code QUEUED} row on the
 * {@code vo_sim} connection, then an engine computation — pure, in-process, bounded executor (ADR-22, NFR-06) — reads
 * only its own stored scenario document, calls {@link SimulationRunner#runWithBaseline(Scenario)} and writes its own
 * result row, all still on {@code vo_sim}. No {@code DomainEvents}, no live repository, is ever reached from here.
 */
@Service
public class SimulationRunService {

    private static final Logger LOG = LoggerFactory.getLogger(SimulationRunService.class);

    private final JdbcClient simJdbc;
    private final TransactionTemplate simTx;
    private final JsonMapper mapper;
    private final ScenarioService scenarios;
    private final ExecutorService executor;

    public SimulationRunService(@Qualifier("simDataSource") DataSource simDataSource, JsonMapper mapper,
            ScenarioService scenarios, @Qualifier("simulationExecutor") ExecutorService executor) {
        this.simJdbc = JdbcClient.create(simDataSource);
        this.simTx = new TransactionTemplate(new JdbcTransactionManager(simDataSource));
        this.mapper = mapper;
        this.scenarios = scenarios;
        this.executor = executor;
    }

    /** Inserts the QUEUED run and, once that commits, submits it to the executor. Runs inside the command gateway. */
    public CommandResult createRun(UUID tenantId, UUID siteId, UUID scenarioId, UUID requestedBy) {
        UUID id = Ids.newId();
        simTx.executeWithoutResult(status -> simJdbc.sql("""
                insert into sim.simulation_runs (id, tenant_id, site_id, scenario_id, status, requested_by)
                values (:id, :tenant, :site, :scenario, 'QUEUED', :by)
                """)
                .param("id", id).param("tenant", tenantId).param("site", siteId).param("scenario", scenarioId)
                .param("by", requestedBy).update());
        executor.submit(() -> execute(tenantId, id));
        Map<String, Object> result = Map.of("run_id", id.toString(), "scenario_id", scenarioId.toString(),
                "status", "QUEUED");
        return CommandResult.of(result);
    }

    /** Runs on the executor thread: claims the row, runs the engine, records the outcome. Never throws. */
    void execute(UUID tenantId, UUID runId) {
        TenantContext.callWith(tenantId, () -> {
            doExecute(tenantId, runId);
            return null;
        });
    }

    private void doExecute(UUID tenantId, UUID runId) {
        UUID scenarioId = claim(tenantId, runId);
        if (scenarioId == null) {
            return; // already claimed/finished (e.g. a defensive re-submit); nothing to do.
        }
        try {
            Map<String, Object> doc = scenarios.loadScenarioDoc(tenantId, scenarioId);
            String scenarioJson = mapper.writeValueAsString(doc);
            Scenario scenario = Scenario.parse(scenarioJson);
            Comparison comparison = SimulationRunner.runWithBaseline(scenario);
            Map<String, Object> resultDoc = comparison.toMap();
            String resultsJson = mapper.writeValueAsString(resultDoc);
            String resultsHash = String.valueOf(resultDoc.get("comparison_hash"));
            succeed(tenantId, runId, resultsJson, resultsHash);
        } catch (InvalidInputException ex) {
            fail(tenantId, runId, "Invalid scenario: " + ex.getMessage());
        } catch (RuntimeException ex) {
            LOG.warn("Simulation run {} failed: {}", runId, ex.toString());
            fail(tenantId, runId, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private UUID claim(UUID tenantId, UUID runId) {
        return simTx.execute(status -> simJdbc.sql("""
                update sim.simulation_runs set status = 'RUNNING', started_at = now(), version = version + 1
                where id = :id and tenant_id = :tenant and status = 'QUEUED'
                returning scenario_id
                """)
                .param("id", runId).param("tenant", tenantId)
                .query((rs, n) -> rs.getObject("scenario_id", UUID.class)).optional().orElse(null));
    }

    private void succeed(UUID tenantId, UUID runId, String resultsJson, String resultsHash) {
        simTx.executeWithoutResult(status -> simJdbc.sql("""
                update sim.simulation_runs set status = 'SUCCEEDED', finished_at = now(), engine_name = :name,
                    engine_version = :version, results_json = cast(:results as jsonb), results_hash = :hash,
                    version = version + 1
                where id = :id and tenant_id = :tenant
                """)
                .param("name", Engine.NAME).param("version", Engine.VERSION).param("results", resultsJson)
                .param("hash", resultsHash).param("id", runId).param("tenant", tenantId).update());
    }

    private void fail(UUID tenantId, UUID runId, String errorMessage) {
        String message = errorMessage.length() > 2000 ? errorMessage.substring(0, 2000) : errorMessage;
        simTx.executeWithoutResult(status -> simJdbc.sql("""
                update sim.simulation_runs set status = 'FAILED', finished_at = now(), error_message = :err,
                    version = version + 1
                where id = :id and tenant_id = :tenant
                """)
                .param("err", message).param("id", runId).param("tenant", tenantId).update());
    }

    static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "Not found", "The requested simulation run was not found.");
    }
}
