package io.vorg.api.kpi;

import java.util.List;

/**
 * Semantic definition of one KPI (blueprint KPI semantic layer): what it means, how it is computed, in which unit,
 * from which stored data and how its provenance label is derived. The registry is code, so a definition can never
 * drift from the computation that sits next to it.
 */
public record KpiDefinition(String id, String name, String category, String description, String formula, String unit,
        String grain, List<String> sourceTables, String dataMode, String freshness, boolean windowed,
        boolean seriesSupported, String minimumData) {}
