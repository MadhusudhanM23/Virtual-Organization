package io.vorg.simulation.warehouse;

import io.vorg.simulation.json.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A simulation scenario, schema {@value #SCHEMA} (ADR-49): a baseline model input (a built-in baseline by name, or an
 * inline {@code model}), run settings and an overlay. {@link #effectiveModel()} is the baseline with the overlay's
 * parameter overrides applied (copy-on-write; the baseline object is never modified).
 */
public final class Scenario {

    public static final String SCHEMA = "vorg.simulation.scenario.v1";
    private static final Set<String> FIELDS = Set.of("schema", "scenario_id", "name", "description", "baseline", "model",
            "run", "overlay");

    private final String scenarioId;
    private final String name;
    private final String description;
    private final String baselineRef;
    private final ModelInput baseline;
    private final RunSettings run;
    private final Overlay overlay;
    private final ModelInput effective;

    public Scenario(String scenarioId, String name, String description, String baselineRef, ModelInput baseline,
            RunSettings run, Overlay overlay) {
        this.scenarioId = scenarioId;
        this.name = name;
        this.description = description == null ? "" : description;
        this.baselineRef = baselineRef;
        this.baseline = baseline;
        this.run = run;
        this.overlay = overlay;
        overlay.validate(baseline, run);
        this.effective = baseline.withOverrides(overlay.parameterOverrides());
    }

    public static Scenario parse(String json) {
        return fromMap(Json.parseObject(json));
    }

    public static Scenario fromMap(Map<String, Object> m) {
        Fields.allowOnly(m, "scenario", FIELDS);
        if (!SCHEMA.equals(m.get("schema"))) {
            throw Fields.invalid("scenario.schema", "must be \"" + SCHEMA + "\"");
        }
        String ref = Fields.optStr(m, "scenario", "baseline", null);
        Map<String, Object> inline = Fields.optMap(m, "scenario", "model");
        if ((ref == null) == (inline == null)) {
            throw Fields.invalid("scenario", "exactly one of 'baseline' (built-in name) or 'model' (inline) required");
        }
        ModelInput baseline = ref != null ? Baselines.load(ref) : ModelInput.fromMap(inline);
        RunSettings run = RunSettings.fromMap(Fields.map(m, "scenario", "run"));
        Overlay overlay = Overlay.fromMap(Fields.optMap(m, "scenario", "overlay"));
        return new Scenario(Fields.str(m, "scenario", "scenario_id"), Fields.str(m, "scenario", "name"),
                Fields.optStr(m, "scenario", "description", ""), ref == null ? "inline" : ref, baseline, run, overlay);
    }

    /** The same scenario without its overlay: the baseline run for a comparison (same seeds). */
    public Scenario baselineCounterpart() {
        return new Scenario(scenarioId + "--baseline", "Baseline for " + name, "Overlay removed; same run settings",
                baselineRef, baseline, run, Overlay.empty());
    }

    public Scenario withRun(RunSettings newRun) {
        return new Scenario(scenarioId, name, description, baselineRef, baseline, newRun, overlay);
    }

    public String scenarioId() {
        return scenarioId;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String baselineRef() {
        return baselineRef;
    }

    public ModelInput baseline() {
        return baseline;
    }

    public ModelInput effectiveModel() {
        return effective;
    }

    public RunSettings run() {
        return run;
    }

    public Overlay overlay() {
        return overlay;
    }

    /** Canonical description of the scenario inputs (the baseline by reference + hash, not inlined). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenario_id", scenarioId);
        m.put("name", name);
        m.put("description", description);
        m.put("baseline", baselineRef);
        m.put("baseline_hash", baseline.hash());
        m.put("overlay", overlay.toMap());
        m.put("affected_entities", overlay.affectedEntities());
        return m;
    }
}
