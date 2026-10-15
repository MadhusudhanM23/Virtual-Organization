package io.vorg.api.security;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;

import io.vorg.api.platform.authz.Action;
import java.util.List;
import java.util.UUID;

/**
 * The endpoints exercised by the security suite (Plan 11.3): every site-scoped read and every command endpoint that is
 * reachable without first creating a domain object, with the matrix action each one authorizes. The role-by-endpoint
 * expectations are derived from {@code permission-matrix.v1.json}, not written out by hand.
 */
final class EndpointCatalog {

    private EndpointCatalog() {}

    /** A single call. {@code body} is null for GET. {@code strictDeny} means a DENY decision must yield exactly 403. */
    record Endpoint(String method, String path, Action action, String body, boolean viaPlatformConfig) {
        boolean isCommand() {
            return !"GET".equals(method);
        }

        @Override
        public String toString() {
            return method + " " + path.replaceAll("\\?.*", "");
        }
    }

    static final String SITE = "site_id=" + SITE_BLR;

    static Endpoint get(String path, Action action) {
        return new Endpoint("GET", path, action, null, false);
    }

    static Endpoint post(String path, Action action, String body) {
        return new Endpoint("POST", path, action, body, false);
    }

    /**
     * A command that a caller without operational-state authority may still use as MANAGE_PLATFORM_CONFIGURATION: the
     * Platform Administrator administers demos and replay snapshots (ADR-43, ADR-44) but never reads the results.
     */
    static Endpoint postViaPlatformConfig(String path, Action action, String body) {
        return new Endpoint("POST", path, action, body, true);
    }

    /** Placeholder replaced by a fresh UUID on every call (one per occurrence). */
    static String fill(String body) {
        String out = body;
        while (out != null && out.contains("{uuid}")) {
            out = out.replaceFirst("[{]uuid[}]", UUID.randomUUID().toString());
        }
        return out;
    }

    static List<Endpoint> all() {
        UUID none = UUID.fromString("00000000-0000-7000-8000-0000000000ff");
        return List.of(
                // twin
                get("/api/twin/world?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/twin/world/version?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/twin/floors?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/twin/zones?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/twin/docks?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/twin/racks?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/twin/entities?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                // live operations
                get("/api/events?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/tasks?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/kpis?" + SITE + "&window=24h", Action.VIEW_OPERATIONAL_STATE),
                get("/api/analytics/heatmap?" + SITE + "&window=24h", Action.ANALYZE_QUERY),
                get("/api/analytics/order-flow?" + SITE + "&window=24h", Action.ANALYZE_QUERY),
                get("/api/integration/records?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/integration/findings?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/simulation/movement?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                // incidents, cameras, replay
                get("/api/incidents?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/cameras?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/replay/range?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/replay/events?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                // simulation, recommendations
                get("/api/scenarios?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/simulation-runs?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/recommendations?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                get("/api/approvals?" + SITE, Action.VIEW_OPERATIONAL_STATE),
                // commands
                post("/api/scenarios", Action.CREATE_SIMULATION, """
                        {"site_id":"%s","name":"authz probe","description":"security suite probe",
                         "overlay":{"dock_closures":[{"dock_id":"IN-02","start_min":60,"end_min":240}]}}
                        """.formatted(SITE_BLR)),
                post("/api/recommendations", Action.PROPOSE_RECOMMENDATION,
                        "{\"site_id\":\"%s\",\"simulation_run_id\":\"%s\"}".formatted(SITE_BLR, none)),
                postViaPlatformConfig("/api/replay/snapshots", Action.VIEW_OPERATIONAL_STATE,
                        "{\"site_id\":\"" + SITE_BLR + "\"}"),
                post("/api/sites/" + SITE_BLR + "/settings", Action.MANAGE_PLATFORM_CONFIGURATION,
                        "{\"locale\":\"en-IN\"}"),
                post("/api/simulation/movement/control", Action.MANAGE_PLATFORM_CONFIGURATION,
                        "{\"site_id\":\"%s\",\"action\":\"CONFIGURE\"}".formatted(SITE_BLR)),
                post("/api/ai/exchanges", Action.VIEW_OPERATIONAL_STATE, """
                        {"exchange_id":"{uuid}","site_id":"%s","conversation_id":"{uuid}","provider":"offline",
                         "model":"offline","external":false,"provider_reason":"authz probe","retention":"none",
                         "question_sha256":"%s","prompt_tokens":0,"completion_tokens":0,"token_count_method":"estimate",
                         "tool_calls":[],"citations":[],"flags":[],"grounded":true,"latency_ms":1}
                        """.formatted(SITE_BLR, "a".repeat(64))));
    }

    /**
     * Demo-control commands. They mutate the shared demo world for every role that may use them, so the all-roles matrix
     * leaves them out; the tenant-isolation suite uses them only against the OTHER tenant's site (nothing is written).
     */
    static List<Endpoint> demoCommands() {
        return List.of(
                postViaPlatformConfig("/api/demo/incidents/proximity", Action.VIEW_OPERATIONAL_STATE,
                        "{\"site_id\":\"" + SITE_BLR + "\"}"),
                postViaPlatformConfig("/api/demo/integration/tms-change", Action.VIEW_OPERATIONAL_STATE,
                        "{\"site_id\":\"%s\",\"trip_code\":\"TRP-001\",\"to_dock\":\"IN-02\"}"
                                .formatted(SITE_BLR)));
    }

    /** Endpoints that need only a token (no site): checked for 401 without a token. */
    static List<String> tokenOnly() {
        return List.of("/api/me", "/api/organizations", "/api/kpis/definitions");
    }
}
