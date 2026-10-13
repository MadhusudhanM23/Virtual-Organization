package io.vorg.api.recommendations;

import io.vorg.api.platform.identity.Role;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The approval policy for {@code DOCK_REASSIGNMENT} recommendations, {@code vo.recommendations.policies.dock-reassignment.*}
 * (ADR-61). Its {@code version} is recorded in every recommendation's {@code policy_versions} as
 * {@value #NAME}; <b>bump it whenever any other field changes</b>, because a version change is what makes an approved
 * plan stale (BLUEPRINT section 9 stale-baseline rule).
 *
 * <ul>
 *   <li>{@code approvalClass}: SENSITIVE requires separation of duties (approver must not be the proposer);</li>
 *   <li>{@code approverRoles}: the roles whose {@code ALLOW_SUBJECT_TO_POLICY} ("R*") matrix cell for
 *       {@code APPROVE_CONSEQUENTIAL_ACTION} this policy accepts. A plain {@code ALLOW} cell needs no policy;</li>
 *   <li>{@code expiryMinutes}: how long a proposal and its approval stay decidable.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "vo.recommendations.policies.dock-reassignment")
public record DockReassignmentPolicy(
        @DefaultValue("1") int version,
        @DefaultValue("SENSITIVE") ApprovalClass approvalClass,
        @DefaultValue({"WAREHOUSE_MANAGER", "REGIONAL_MANAGER"}) List<Role> approverRoles,
        @DefaultValue("30") int expiryMinutes) {

    public static final String NAME = "dock-reassignment";

    public DockReassignmentPolicy {
        Objects.requireNonNull(approvalClass, "approvalClass");
        approverRoles = List.copyOf(approverRoles);
        if (version < 1) {
            throw new IllegalArgumentException("dock-reassignment policy version must be >= 1");
        }
        if (expiryMinutes < 1 || expiryMinutes > 24 * 60) {
            throw new IllegalArgumentException("dock-reassignment expiry-minutes must be between 1 and 1440");
        }
        if (approverRoles.isEmpty()) {
            throw new IllegalArgumentException("dock-reassignment approver-roles must not be empty");
        }
        if (approverRoles.contains(Role.DRIVER_OPERATOR) || approverRoles.contains(Role.PLATFORM_ADMIN)) {
            throw new IllegalArgumentException("Drivers/operators and platform administrators cannot approve plans");
        }
    }

    public boolean separationOfDuties() {
        return approvalClass.separationOfDuties();
    }

    public Duration expiry() {
        return Duration.ofMinutes(expiryMinutes);
    }

    public Set<Role> approverRoleSet() {
        return Set.copyOf(approverRoles);
    }
}
