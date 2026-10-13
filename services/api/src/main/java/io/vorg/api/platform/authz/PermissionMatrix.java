package io.vorg.api.platform.authz;

import io.vorg.api.platform.identity.Role;
import io.vorg.api.platform.identity.ScopeType;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The section 4 matrix loaded from the classpath copy of {@code packages/contracts/authz/permission-matrix.v1.json}.
 * Loading fails fast if any role x action cell is missing or unknown, so a malformed contract cannot start the app.
 */
public final class PermissionMatrix {

    public static final String RESOURCE = "authz/permission-matrix.v1.json";

    private final Map<Action, Map<Role, DecisionCode>> cells = new EnumMap<>(Action.class);
    private final Map<Role, Set<ScopeType>> scopeTypes = new EnumMap<>(Role.class);
    private int version;

    private PermissionMatrix() {}

    public static PermissionMatrix loadFromClasspath() {
        try (InputStream in = PermissionMatrix.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Permission matrix contract missing from classpath: " + RESOURCE);
            }
            return parse(JsonMapper.builder().build().readTree(in));
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read permission matrix", ex);
        }
    }

    static PermissionMatrix parse(JsonNode root) {
        PermissionMatrix m = new PermissionMatrix();
        JsonNode version = root.get("version");
        if (version == null || !version.isInt() || version.asInt() < 1) {
            throw new IllegalStateException("Matrix version missing or not a positive integer");
        }
        m.version = version.asInt();
        for (JsonNode r : root.get("roles")) {
            Role role = Role.valueOf(r.get("role").asString());
            Set<ScopeType> types = EnumSet.noneOf(ScopeType.class);
            r.get("allowed_scope_types").forEach(t -> types.add(ScopeType.valueOf(t.asString())));
            m.scopeTypes.put(role, types);
        }
        for (JsonNode a : root.get("actions")) {
            Action action = Action.valueOf(a.get("action").asString());
            Map<Role, DecisionCode> row = new EnumMap<>(Role.class);
            JsonNode decisions = a.get("decisions");
            for (Role role : Role.values()) {
                JsonNode cell = decisions.get(role.name());
                if (cell == null) {
                    throw new IllegalStateException("Matrix cell missing: " + action + " x " + role);
                }
                row.put(role, DecisionCode.valueOf(cell.asString()));
            }
            m.cells.put(action, row);
        }
        for (Action action : Action.values()) {
            if (!m.cells.containsKey(action)) {
                throw new IllegalStateException("Matrix row missing for action " + action);
            }
        }
        if (m.scopeTypes.size() != Role.values().length) {
            throw new IllegalStateException("Matrix roles incomplete");
        }
        return m;
    }

    /**
     * The contract's {@code "version"}: a policy version in the sense of BLUEPRINT section 9 ({@code policy_versions}
     * of a recommendation records it as {@code "permission-matrix"}; a change makes an approved plan stale, ADR-61).
     */
    public int version() {
        return version;
    }

    /** The matrix cell; anything unknown is DENY. */
    public DecisionCode decision(Role role, Action action) {
        Map<Role, DecisionCode> row = cells.get(action);
        DecisionCode code = row == null ? null : row.get(role);
        return code == null ? DecisionCode.DENY : code;
    }

    public Set<ScopeType> allowedScopeTypes(Role role) {
        return scopeTypes.getOrDefault(role, Set.of());
    }
}
