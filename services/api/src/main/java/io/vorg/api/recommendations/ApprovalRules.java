package io.vorg.api.recommendations;

import io.vorg.api.platform.authz.Decision;
import io.vorg.api.platform.identity.Role;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The pure decision rules of an approval (ADR-61), separate from persistence so they are unit-testable.
 *
 * <p><b>Eligibility.</b> The command gateway authorizes {@code APPROVE_CONSEQUENTIAL_ACTION} on the site but does not
 * interpret {@link Decision#requiresPolicyCheck()}. Here it gets its meaning: each of the caller's role assignments is
 * evaluated on its own; an assignment whose cell is {@code ALLOW} qualifies as is, one whose cell is
 * {@code ALLOW_SUBJECT_TO_POLICY} qualifies only if the approval policy lists that role. Evaluating per assignment
 * (rather than the subject's overall decision) stops an R* grant in scope from borrowing a policy-listed role that the
 * caller holds somewhere else.
 */
final class ApprovalRules {

    /** The policy decision for one of the caller's role assignments on the approval's site. */
    record Grant(Role role, Decision decision) {}

    private ApprovalRules() {}

    static boolean eligible(List<Grant> grants, Set<Role> approverRoles) {
        for (Grant g : grants) {
            if (g.decision() == null || !g.decision().allowed()) {
                continue;
            }
            if (!g.decision().requiresPolicyCheck() || approverRoles.contains(g.role())) {
                return true;
            }
        }
        return false;
    }

    /** True when separation of duties applies and the decider is the proposer. */
    static boolean violatesSeparationOfDuties(boolean separationOfDuties, UUID proposedBy, UUID decidedBy) {
        return separationOfDuties && Objects.equals(proposedBy, decidedBy);
    }

    /** An approval is decidable strictly before {@code expiresAt}; at or after it, it has expired. */
    static boolean expired(Instant now, Instant expiresAt) {
        return !now.isBefore(expiresAt);
    }
}
