package io.vorg.api.scenarios;

import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.simulation.warehouse.Baselines;
import io.vorg.simulation.warehouse.InvalidInputException;
import io.vorg.simulation.warehouse.Scenario;
import io.vorg.simulation.warehouse.SignatureScenarios;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Builds the engine's scenario document (schema {@code vorg.simulation.scenario.v1}) from an API request and turns
 * {@link InvalidInputException} ("path: message", see {@code services/simulation}'s {@code Fields}) into a 400
 * problem naming the bad field (REQ-070/071).
 */
public final class ScenarioJson {

    /** Plan 6.3 always overlays the one demo baseline (ADR-60); no live snapshot is taken. */
    static final String BASELINE_REF = Baselines.BENGALURU_WAREHOUSE_01;

    private ScenarioJson() {}

    /**
     * The overlay of a create request: the given {@code overlay}, or the expansion of a typed {@code signature}
     * ({@code {type, params}}, REQ-140, ADR-79). Both at once is a 400, as is any invalid signature parameter (the
     * field path is {@code signature.params.<name>}).
     */
    public static Map<String, Object> resolveOverlay(Map<String, Object> overlay, Map<String, Object> signature) {
        if (signature == null) {
            return overlay;
        }
        if (overlay != null && !overlay.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Invalid scenario",
                    "signature and overlay are mutually exclusive.",
                    List.of(new FieldError("signature", "cannot be combined with overlay")));
        }
        try {
            if (!signature.keySet().stream().allMatch(k -> k.equals("type") || k.equals("params"))) {
                throw new InvalidInputException("signature: only 'type' and 'params' are allowed");
            }
            Object type = signature.get("type");
            if (!(type instanceof String t) || t.isBlank()) {
                throw new InvalidInputException("signature.type: non-empty string required");
            }
            Object params = signature.get("params");
            if (params != null && !(params instanceof Map)) {
                throw new InvalidInputException("signature.params: object required");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> pm = (Map<String, Object>) params;
            return SignatureScenarios.expand(t, pm, Baselines.load(BASELINE_REF));
        } catch (InvalidInputException ex) {
            throw toProblem(ex);
        } catch (ClassCastException | NumberFormatException ex) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Invalid scenario",
                    "signature: fields have the wrong type.", List.of(new FieldError("signature", ex.getMessage())));
        }
    }

    /**
     * Builds and parses the scenario document. {@code run} may omit {@code master_seed}; one is then derived
     * deterministically from the scenario id (REQ-072 requires a seed, not that the caller supply it) so repeat runs
     * of the same stored scenario reproduce. Throws {@link ApiProblemException} (400) naming the bad field on any
     * other problem the engine's strict parser finds.
     */
    @SuppressWarnings("unchecked")
    static Scenario build(String scenarioId, String name, String description, Map<String, Object> overlay,
            Map<String, Object> run) {
        Map<String, Object> runMap = run == null ? new LinkedHashMap<>() : new LinkedHashMap<>(run);
        if (runMap.get("master_seed") == null) {
            runMap.put("master_seed", deriveSeed(scenarioId));
        }
        Map<String, Object> scenarioMap = new LinkedHashMap<>();
        scenarioMap.put("schema", Scenario.SCHEMA);
        scenarioMap.put("scenario_id", scenarioId);
        scenarioMap.put("name", name);
        scenarioMap.put("description", description == null ? "" : description);
        scenarioMap.put("baseline", BASELINE_REF);
        scenarioMap.put("run", runMap);
        if (overlay != null) {
            scenarioMap.put("overlay", overlay);
        }
        try {
            return Scenario.fromMap(scenarioMap);
        } catch (InvalidInputException ex) {
            throw toProblem(ex);
        } catch (ClassCastException | NumberFormatException ex) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Invalid scenario",
                    "overlay/run: fields have the wrong type.", List.of(new FieldError("overlay", ex.getMessage())));
        }
    }

    /**
     * The full, re-parseable scenario document ({@code schema}, {@code scenario_id}, {@code name}, {@code
     * description}, {@code baseline}, {@code run}, {@code overlay}) — what is stored as {@code scenario_json} and fed
     * back into {@link Scenario#parse(String)} on every run. {@link Scenario#toMap()} is deliberately not this: it is
     * a provenance summary (adds {@code baseline_hash}/{@code affected_entities}, drops {@code run}).
     */
    static Map<String, Object> toDocument(Scenario scenario) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", Scenario.SCHEMA);
        m.put("scenario_id", scenario.scenarioId());
        m.put("name", scenario.name());
        m.put("description", scenario.description());
        m.put("baseline", scenario.baselineRef());
        m.put("run", scenario.run().toMap());
        m.put("overlay", scenario.overlay().toMap());
        return m;
    }

    /** {@code InvalidInputException} messages are always "{@code path: message}" (engine {@code Fields.invalid}). */
    static ApiProblemException toProblem(InvalidInputException ex) {
        String message = ex.getMessage() == null ? "Invalid scenario." : ex.getMessage();
        int sep = message.indexOf(": ");
        String field = sep > 0 ? message.substring(0, sep) : "scenario";
        String detail = sep > 0 ? message.substring(sep + 2) : message;
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "Invalid scenario", message,
                List.of(new FieldError(field, detail)));
    }

    private static long deriveSeed(String scenarioId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scenarioId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long seed = 0;
            for (int i = 0; i < 8; i++) {
                seed = (seed << 8) | (digest[i] & 0xFF);
            }
            return seed & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
