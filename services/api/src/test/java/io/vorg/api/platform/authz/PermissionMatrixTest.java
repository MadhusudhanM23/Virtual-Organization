package io.vorg.api.platform.authz;

import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.platform.identity.Role;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Independent transcription of the BLUEPRINT section 4 "Permission matrix baseline" table (legend: R, R*, Gate,
 * Assigned only, No, Limited, Admin-tech). If the contract JSON is edited, this test fails the build.
 */
class PermissionMatrixTest {

    /** Column order of the blueprint table: Exec | Regional | Warehouse | Driver | Analyst | Safety | Platform Admin. */
    private static final Role[] COLUMNS = {
        Role.EXECUTIVE, Role.REGIONAL_MANAGER, Role.WAREHOUSE_MANAGER, Role.DRIVER_OPERATOR, Role.ANALYST,
        Role.SAFETY_COMPLIANCE, Role.PLATFORM_ADMIN
    };

    private static final Map<Action, String[]> BLUEPRINT_SECTION_4 = new LinkedHashMap<>();

    static {
        BLUEPRINT_SECTION_4.put(Action.VIEW_OPERATIONAL_STATE,
                new String[] {"R", "R", "R", "R*", "R", "R", "Admin-tech"});
        BLUEPRINT_SECTION_4.put(Action.ANALYZE_QUERY, new String[] {"R", "R", "R", "No", "R", "R", "No"});
        BLUEPRINT_SECTION_4.put(Action.CREATE_SIMULATION, new String[] {"R", "R", "R", "No", "R", "R", "No"});
        BLUEPRINT_SECTION_4.put(Action.PROPOSE_RECOMMENDATION, new String[] {"R", "R", "R", "No", "R", "R", "No"});
        BLUEPRINT_SECTION_4.put(Action.APPROVE_CONSEQUENTIAL_ACTION,
                new String[] {"R*", "R*", "R", "No", "No", "R*", "No"});
        BLUEPRINT_SECTION_4.put(Action.EXECUTE_STANDARD_COMMAND,
                new String[] {"Gate", "Gate", "R", "Assigned only", "No", "Gate", "No"});
        BLUEPRINT_SECTION_4.put(Action.EMERGENCY_STOP_REQUEST,
                new String[] {"No", "No", "Limited", "No", "No", "R", "No"});
        BLUEPRINT_SECTION_4.put(Action.MANAGE_PLATFORM_CONFIGURATION,
                new String[] {"No", "No", "No", "No", "No", "No", "R"});
        // ADR-20 (not in the section 4 table): confirm/reject/resolve/close for Safety/Compliance and Warehouse Manager.
        BLUEPRINT_SECTION_4.put(Action.HANDLE_SAFETY_INCIDENT,
                new String[] {"No", "No", "R", "No", "No", "R", "No"});
    }

    private static DecisionCode decode(String legend, Action action) {
        return switch (legend) {
            case "R" -> DecisionCode.ALLOW;
            case "R*" -> DecisionCode.ALLOW_SUBJECT_TO_POLICY;
            case "Gate" -> DecisionCode.GATED;
            case "Assigned only" -> DecisionCode.ASSIGNED_ONLY;
            case "Limited" -> DecisionCode.LIMITED;
            case "No" -> DecisionCode.DENY;
            case "Admin-tech" -> DecisionCode.ADMIN_TECH;
            default -> throw new IllegalArgumentException(legend);
        };
    }

    @Test
    void everyActionIsCoveredByTheIndependentTranscription() {
        assertThat(BLUEPRINT_SECTION_4.keySet()).containsExactlyInAnyOrder(Action.values());
    }

    @Test
    void matrixJsonEqualsBlueprintSection4() {
        PermissionMatrix matrix = PermissionMatrix.loadFromClasspath();
        BLUEPRINT_SECTION_4.forEach((action, row) -> {
            for (int i = 0; i < COLUMNS.length; i++) {
                assertThat(matrix.decision(COLUMNS[i], action))
                        .as("%s x %s", action, COLUMNS[i])
                        .isEqualTo(decode(row[i], action));
            }
        });
    }

    @Test
    void sourcesAreSection4ExceptAdr20IncidentHandling() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(PermissionMatrix.RESOURCE)) {
            JsonNode root = JsonMapper.builder().build().readTree(in);
            assertThat(root.get("version").asInt()).isEqualTo(1);
            assertThat(root.get("actions")).hasSize(Action.values().length);
            for (JsonNode a : root.get("actions")) {
                String expected = "HANDLE_SAFETY_INCIDENT".equals(a.get("action").asString()) ? "ADR-20" : "§4";
                assertThat(a.get("source").asString()).as(a.get("action").asString()).isEqualTo(expected);
            }
            assertThat(root.get("roles")).hasSize(Role.values().length);
        }
    }

    @Test
    void versionIsExposedForPolicyVersions() {
        assertThat(PermissionMatrix.loadFromClasspath().version()).isEqualTo(1);
    }

    @Test
    void roleScopeTypesFollowSection4ScopeColumn() {
        PermissionMatrix matrix = PermissionMatrix.loadFromClasspath();
        assertThat(matrix.allowedScopeTypes(Role.EXECUTIVE)).containsExactly(io.vorg.api.platform.identity.ScopeType.ORG);
        assertThat(matrix.allowedScopeTypes(Role.REGIONAL_MANAGER))
                .containsExactly(io.vorg.api.platform.identity.ScopeType.REGION);
        assertThat(matrix.allowedScopeTypes(Role.WAREHOUSE_MANAGER))
                .containsExactly(io.vorg.api.platform.identity.ScopeType.SITE);
        assertThat(matrix.allowedScopeTypes(Role.DRIVER_OPERATOR))
                .containsExactly(io.vorg.api.platform.identity.ScopeType.ASSIGNED);
        assertThat(matrix.allowedScopeTypes(Role.ANALYST)).containsExactly(io.vorg.api.platform.identity.ScopeType.ORG);
        assertThat(matrix.allowedScopeTypes(Role.SAFETY_COMPLIANCE)).containsExactlyInAnyOrder(
                io.vorg.api.platform.identity.ScopeType.SITE, io.vorg.api.platform.identity.ScopeType.ORG);
    }
}
