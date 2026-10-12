package io.vorg.api.platform.authz;

import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.platform.identity.Role;
import io.vorg.api.platform.identity.RoleAssignment;
import io.vorg.api.platform.identity.ScopeType;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.world.SiteLookup;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PolicyServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    private static final UUID REGION_1 = UUID.fromString("00000000-0000-7000-8000-000000000101");
    private static final UUID REGION_2 = UUID.fromString("00000000-0000-7000-8000-000000000102");
    private static final UUID SITE_1 = UUID.fromString("00000000-0000-7000-8000-000000000201");
    private static final UUID SITE_2 = UUID.fromString("00000000-0000-7000-8000-000000000202");

    private static final Map<UUID, UUID> REGION_OF_SITE = Map.of(SITE_1, REGION_1, SITE_2, REGION_2);
    private static final SiteLookup LOOKUP = (tenant, site) ->
            TENANT.equals(tenant) ? Optional.ofNullable(REGION_OF_SITE.get(site)) : Optional.empty();

    private final PolicyService policy = new PolicyService(PermissionMatrix.loadFromClasspath(), LOOKUP);

    private static Subject subject(Role role, ScopeType scopeType, UUID scopeId) {
        return new Subject(UUID.randomUUID(), TENANT, role.name(),
                List.of(new RoleAssignment(role, scopeType, scopeId)));
    }

    private static Subject exec() {
        return subject(Role.EXECUTIVE, ScopeType.ORG, null);
    }

    private static Subject regional() {
        return subject(Role.REGIONAL_MANAGER, ScopeType.REGION, REGION_1);
    }

    private static Subject warehouse() {
        return subject(Role.WAREHOUSE_MANAGER, ScopeType.SITE, SITE_1);
    }

    private static Subject driver() {
        return subject(Role.DRIVER_OPERATOR, ScopeType.ASSIGNED, null);
    }

    private static Subject analyst() {
        return subject(Role.ANALYST, ScopeType.ORG, null);
    }

    private static Subject safety() {
        return subject(Role.SAFETY_COMPLIANCE, ScopeType.SITE, SITE_1);
    }

    private static Subject admin() {
        return subject(Role.PLATFORM_ADMIN, ScopeType.ORG, null);
    }

    /**
     * Expected in-scope outcomes per action, columns Exec | Regional | Warehouse | Driver | Analyst | Safety | Admin.
     * Context for in-scope: no approved workflow (so Gate cells deny), driver assigned to self, admin on platform
     * configuration. Hard-coded independently of the matrix JSON.
     */
    private static final Map<Action, boolean[]> IN_SCOPE_EXPECTED = new java.util.LinkedHashMap<>();

    static {
        IN_SCOPE_EXPECTED.put(Action.VIEW_OPERATIONAL_STATE, new boolean[] {true, true, true, true, true, true, true});
        IN_SCOPE_EXPECTED.put(Action.ANALYZE_QUERY, new boolean[] {true, true, true, false, true, true, false});
        IN_SCOPE_EXPECTED.put(Action.CREATE_SIMULATION, new boolean[] {true, true, true, false, true, true, false});
        IN_SCOPE_EXPECTED.put(Action.PROPOSE_RECOMMENDATION,
                new boolean[] {true, true, true, false, true, true, false});
        IN_SCOPE_EXPECTED.put(Action.APPROVE_CONSEQUENTIAL_ACTION,
                new boolean[] {true, true, true, false, false, true, false});
        IN_SCOPE_EXPECTED.put(Action.EXECUTE_STANDARD_COMMAND,
                new boolean[] {false, false, true, true, false, false, false});
        IN_SCOPE_EXPECTED.put(Action.EMERGENCY_STOP_REQUEST,
                new boolean[] {false, false, true, false, false, true, false});
        IN_SCOPE_EXPECTED.put(Action.MANAGE_PLATFORM_CONFIGURATION,
                new boolean[] {false, false, false, false, false, false, true});
        IN_SCOPE_EXPECTED.put(Action.HANDLE_SAFETY_INCIDENT,
                new boolean[] {false, false, true, false, false, true, false});
    }

    private static final Role[] ROLES = {
        Role.EXECUTIVE, Role.REGIONAL_MANAGER, Role.WAREHOUSE_MANAGER, Role.DRIVER_OPERATOR, Role.ANALYST,
        Role.SAFETY_COMPLIANCE, Role.PLATFORM_ADMIN
    };

    private static Subject subjectFor(int i) {
        return switch (i) {
            case 0 -> exec();
            case 1 -> regional();
            case 2 -> warehouse();
            case 3 -> driver();
            case 4 -> analyst();
            case 5 -> safety();
            default -> admin();
        };
    }

    private static ResourceRef inScopeResource(int i) {
        return i == 6 ? ResourceRef.platformConfig(TENANT) : ResourceRef.site(TENANT, SITE_1);
    }

    private static ResourceRef outOfScopeResource(int i) {
        return switch (i) {
            case 0, 4 -> ResourceRef.site(OTHER_TENANT, SITE_1); // org-wide roles: only another tenant is out of scope
            case 6 -> ResourceRef.platformConfig(OTHER_TENANT);
            default -> ResourceRef.site(TENANT, SITE_2); // other region / other site
        };
    }

    static Stream<Arguments> matrixCases() {
        Stream.Builder<Arguments> b = Stream.builder();
        IN_SCOPE_EXPECTED.forEach((action, row) -> {
            for (int i = 0; i < ROLES.length; i++) {
                b.add(Arguments.of(action, i, row[i]));
            }
        });
        return b.build();
    }

    @ParameterizedTest(name = "{0} x role#{1}: in scope expected {2}")
    @MethodSource("matrixCases")
    void inScopeMatchesMatrix(Action action, int roleIndex, boolean expected) {
        Subject s = subjectFor(roleIndex);
        PolicyContext ctx = roleIndex == 3 ? PolicyContext.assignedTo(s.actorRef()) : PolicyContext.none();
        Decision d = policy.authorize(s, action, inScopeResource(roleIndex), ctx);
        assertThat(d.allowed()).as("%s %s in scope: %s", ROLES[roleIndex], action, d).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} x role#{1}: out of scope is always denied")
    @MethodSource("matrixCases")
    void outOfScopeIsAlwaysDenied(Action action, int roleIndex, boolean ignored) {
        Subject s = subjectFor(roleIndex);
        // Even a fully-privileged context (approved workflow) must not widen scope.
        PolicyContext ctx = roleIndex == 3 ? new PolicyContext(UUID.randomUUID(), true) : PolicyContext.approvedWorkflow();
        Decision d = policy.authorize(s, action, outOfScopeResource(roleIndex), ctx);
        assertThat(d.allowed()).as("%s %s out of scope: %s", ROLES[roleIndex], action, d).isFalse();
    }

    @Test
    void policyReviewedCellsReportRequiresPolicyCheck() {
        Decision approve = policy.authorize(exec(), Action.APPROVE_CONSEQUENTIAL_ACTION, ResourceRef.site(TENANT, SITE_1),
                PolicyContext.none());
        assertThat(approve.allowed()).isTrue();
        assertThat(approve.requiresPolicyCheck()).isTrue();
        Decision wmApprove = policy.authorize(warehouse(), Action.APPROVE_CONSEQUENTIAL_ACTION,
                ResourceRef.site(TENANT, SITE_1), PolicyContext.none());
        assertThat(wmApprove.allowed()).isTrue();
        assertThat(wmApprove.requiresPolicyCheck()).isFalse();
    }

    @Test
    void platformAdminHasNoBusinessAuthority() {
        Subject admin = admin();
        ResourceRef business = ResourceRef.site(TENANT, SITE_1);
        for (Action action : List.of(Action.APPROVE_CONSEQUENTIAL_ACTION, Action.EXECUTE_STANDARD_COMMAND,
                Action.PROPOSE_RECOMMENDATION, Action.ANALYZE_QUERY, Action.CREATE_SIMULATION,
                Action.EMERGENCY_STOP_REQUEST, Action.HANDLE_SAFETY_INCIDENT)) {
            assertThat(policy.authorize(admin, action, business, PolicyContext.approvedWorkflow()).allowed())
                    .as("admin %s on business data", action).isFalse();
            assertThat(policy.authorize(admin, action, ResourceRef.platformConfig(TENANT),
                    PolicyContext.approvedWorkflow()).allowed()).as("admin %s on platform config", action).isFalse();
        }
        // Admin-tech view: technical resources yes, business data never.
        assertThat(policy.authorize(admin, Action.VIEW_OPERATIONAL_STATE, business, PolicyContext.none()).allowed())
                .isFalse();
        assertThat(policy.authorize(admin, Action.VIEW_OPERATIONAL_STATE,
                new ResourceRef(ResourceKind.TECHNICAL_STATE, TENANT, null, null), PolicyContext.none()).allowed())
                .isTrue();
        assertThat(policy.authorize(admin, Action.MANAGE_PLATFORM_CONFIGURATION, business, PolicyContext.none())
                .allowed()).isFalse();
    }

    @Test
    void platformConfigurationIsAdminOnly() {
        for (Subject s : List.of(exec(), regional(), warehouse(), driver(), analyst(), safety())) {
            assertThat(policy.authorize(s, Action.MANAGE_PLATFORM_CONFIGURATION, ResourceRef.platformConfig(TENANT),
                    PolicyContext.none()).allowed()).isFalse();
        }
    }

    @Test
    void regionalManagerIsDeniedForSiteInAnotherRegionAndAllowedInOwnRegion() {
        Subject rm = regional();
        assertThat(policy.authorize(rm, Action.VIEW_OPERATIONAL_STATE, ResourceRef.site(TENANT, SITE_2),
                PolicyContext.none())).satisfies(d -> {
                    assertThat(d.allowed()).isFalse();
                    assertThat(d.code()).isEqualTo("DENY_OUT_OF_SCOPE");
                });
        assertThat(policy.authorize(rm, Action.VIEW_OPERATIONAL_STATE, ResourceRef.site(TENANT, SITE_1),
                PolicyContext.none()).allowed()).isTrue();
        assertThat(policy.authorize(rm, Action.VIEW_OPERATIONAL_STATE, ResourceRef.region(TENANT, REGION_1),
                PolicyContext.none()).allowed()).isTrue();
        assertThat(policy.authorize(rm, Action.VIEW_OPERATIONAL_STATE, ResourceRef.region(TENANT, REGION_2),
                PolicyContext.none()).allowed()).isFalse();
        // Organization-level (above region) resources are not covered by a region scope.
        assertThat(policy.authorize(rm, Action.VIEW_OPERATIONAL_STATE, ResourceRef.organization(TENANT),
                PolicyContext.none()).allowed()).isFalse();
    }

    @Test
    void unknownSiteIsDeniedRatherThanGuessed() {
        assertThat(policy.authorize(exec(), Action.VIEW_OPERATIONAL_STATE,
                ResourceRef.site(TENANT, UUID.randomUUID()), PolicyContext.none()).allowed()).isFalse();
    }

    @Test
    void driverIsAllowedOnlyForOwnAssignment() {
        Subject d = driver();
        ResourceRef site = ResourceRef.site(TENANT, SITE_1);
        assertThat(policy.authorize(d, Action.EXECUTE_STANDARD_COMMAND, site, PolicyContext.assignedTo(d.actorRef()))
                .allowed()).isTrue();
        assertThat(policy.authorize(d, Action.EXECUTE_STANDARD_COMMAND, site,
                PolicyContext.assignedTo(UUID.randomUUID())).allowed()).isFalse();
        assertThat(policy.authorize(d, Action.EXECUTE_STANDARD_COMMAND, site, PolicyContext.none()).allowed())
                .isFalse();
        // Even viewing needs the assignment ("no broad data access").
        assertThat(policy.authorize(d, Action.VIEW_OPERATIONAL_STATE, site, PolicyContext.none()).allowed()).isFalse();
        assertThat(policy.authorize(d, Action.APPROVE_CONSEQUENTIAL_ACTION, site,
                PolicyContext.assignedTo(d.actorRef())).allowed()).isFalse();
    }

    @Test
    void gateRequiresApprovedWorkflow() {
        ResourceRef site = ResourceRef.site(TENANT, SITE_1);
        for (Subject s : List.of(exec(), regional(), safety())) {
            assertThat(policy.authorize(s, Action.EXECUTE_STANDARD_COMMAND, site, PolicyContext.none()).allowed())
                    .isFalse();
        }
        assertThat(policy.authorize(exec(), Action.EXECUTE_STANDARD_COMMAND, site, PolicyContext.approvedWorkflow())
                .allowed()).isTrue();
        assertThat(policy.authorize(regional(), Action.EXECUTE_STANDARD_COMMAND, site,
                PolicyContext.approvedWorkflow()).allowed()).isTrue();
        assertThat(policy.authorize(safety(), Action.EXECUTE_STANDARD_COMMAND, site, PolicyContext.approvedWorkflow())
                .allowed()).isTrue();
        // Gate never bypasses scope or role: an analyst is still denied.
        assertThat(policy.authorize(analyst(), Action.EXECUTE_STANDARD_COMMAND, site, PolicyContext.approvedWorkflow())
                .allowed()).isFalse();
    }

    @Test
    void limitedEmergencyStopIsOwnSiteOnly() {
        Subject wm = warehouse();
        assertThat(policy.authorize(wm, Action.EMERGENCY_STOP_REQUEST, ResourceRef.site(TENANT, SITE_1),
                PolicyContext.none()).allowed()).isTrue();
        assertThat(policy.authorize(wm, Action.EMERGENCY_STOP_REQUEST, ResourceRef.site(TENANT, SITE_2),
                PolicyContext.none()).allowed()).isFalse();
        assertThat(policy.authorize(wm, Action.EMERGENCY_STOP_REQUEST, ResourceRef.organization(TENANT),
                PolicyContext.none()).allowed()).isFalse();
        // Executive has no emergency-stop authority by default.
        assertThat(policy.authorize(exec(), Action.EMERGENCY_STOP_REQUEST, ResourceRef.site(TENANT, SITE_1),
                PolicyContext.approvedWorkflow()).allowed()).isFalse();
    }

    @Test
    void safetyComplianceWithOrgScopeCoversEverySiteButSiteScopeDoesNot() {
        Subject orgSafety = subject(Role.SAFETY_COMPLIANCE, ScopeType.ORG, null);
        assertThat(policy.authorize(orgSafety, Action.EMERGENCY_STOP_REQUEST, ResourceRef.site(TENANT, SITE_2),
                PolicyContext.none()).allowed()).isTrue();
        assertThat(policy.authorize(safety(), Action.EMERGENCY_STOP_REQUEST, ResourceRef.site(TENANT, SITE_2),
                PolicyContext.none()).allowed()).isFalse();
    }

    @Test
    void incidentHandlingFollowsAdr20() {
        ResourceRef site = ResourceRef.site(TENANT, SITE_1);
        assertThat(policy.authorize(safety(), Action.HANDLE_SAFETY_INCIDENT, site, PolicyContext.none()).allowed())
                .isTrue();
        assertThat(policy.authorize(warehouse(), Action.HANDLE_SAFETY_INCIDENT, site, PolicyContext.none()).allowed())
                .isTrue();
        for (Subject s : List.of(exec(), regional(), driver(), analyst(), admin())) {
            assertThat(policy.authorize(s, Action.HANDLE_SAFETY_INCIDENT, site, PolicyContext.assignedTo(s.actorRef()))
                    .allowed()).isFalse();
        }
    }

    @Test
    void invalidAssignmentScopeForRoleIsIgnored() {
        // An Executive "assigned" to a single site is not a valid combination: deny by default.
        Subject odd = subject(Role.EXECUTIVE, ScopeType.SITE, SITE_1);
        assertThat(policy.authorize(odd, Action.VIEW_OPERATIONAL_STATE, ResourceRef.site(TENANT, SITE_1),
                PolicyContext.none()).allowed()).isFalse();
    }

    @Test
    void subjectWithoutRolesAndCrossTenantAreDenied() {
        Subject nobody = new Subject(UUID.randomUUID(), TENANT, "nobody", List.of());
        assertThat(policy.authorize(nobody, Action.VIEW_OPERATIONAL_STATE, ResourceRef.site(TENANT, SITE_1),
                PolicyContext.none()).allowed()).isFalse();
        assertThat(policy.authorize(exec(), Action.VIEW_OPERATIONAL_STATE, ResourceRef.site(OTHER_TENANT, SITE_1),
                PolicyContext.none()).code()).isEqualTo("DENY_TENANT");
    }

    @Test
    void nullInputsAreDenied() {
        assertThat(policy.authorize(null, Action.VIEW_OPERATIONAL_STATE, ResourceRef.organization(TENANT), null)
                .allowed()).isFalse();
        assertThat(policy.authorize(exec(), null, ResourceRef.organization(TENANT), null).allowed()).isFalse();
        assertThat(policy.authorize(exec(), Action.VIEW_OPERATIONAL_STATE, null, null).allowed()).isFalse();
    }
}
