package io.vorg.api.recommendations;

import io.vorg.api.platform.authz.PolicyService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The policies a recommendation is evaluated against and their current versions (BLUEPRINT section 9
 * {@code policy_versions}, ADR-61): {@code "permission-matrix"} (the {@code version} of
 * {@code permission-matrix.v1.json}) and {@code "dock-reassignment"} ({@link DockReassignmentPolicy}). A proposal
 * records {@link #current()}; the stale check at execution time (Plan 8.3) compares that record with {@link #current()}
 * through {@link #changed(Map)}.
 */
@Component
public class PolicyVersions {

    public static final String PERMISSION_MATRIX = "permission-matrix";

    /** One policy whose version differs from the recorded baseline ({@code current} is null if it no longer exists). */
    public record Change(String policy, Integer baselineVersion, Integer currentVersion) {}

    private final PolicyService policy;
    private final DockReassignmentPolicy dockReassignment;

    public PolicyVersions(PolicyService policy, DockReassignmentPolicy dockReassignment) {
        this.policy = policy;
        this.dockReassignment = dockReassignment;
    }

    /** Current versions, in a stable order. */
    public Map<String, Integer> current() {
        Map<String, Integer> versions = new LinkedHashMap<>();
        versions.put(PERMISSION_MATRIX, policy.matrixVersion());
        versions.put(DockReassignmentPolicy.NAME, dockReassignment.version());
        return versions;
    }

    /** Policies of {@code baseline} whose version is no longer the current one. */
    public List<Change> changed(Map<String, Integer> baseline) {
        return changed(baseline, current());
    }

    static List<Change> changed(Map<String, Integer> baseline, Map<String, Integer> current) {
        List<Change> changes = new ArrayList<>();
        baseline.forEach((name, version) -> {
            Integer now = current.get(name);
            if (!Objects.equals(version, now)) {
                changes.add(new Change(name, version, now));
            }
        });
        return changes;
    }
}
