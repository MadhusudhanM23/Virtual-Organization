package io.vorg.api.recommendations;

import io.vorg.api.platform.tenancy.TenantContext;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The {@link CurrentStateReader} over the dock and forklift assignment projections and the twin's docks (Plan 8.3,
 * ADR-63): what the dry-run diff and the execution stale check compare with the recorded baseline.
 */
@Component
public class AssignmentStateReader implements CurrentStateReader {

    private final AssignmentStore assignments;

    public AssignmentStateReader(AssignmentStore assignments) {
        this.assignments = assignments;
    }

    @Override
    public Map<String, EntityState> read(UUID tenantId, UUID siteId, Collection<String> entities) {
        return TenantContext.callWith(tenantId, () -> assignments.states(tenantId, siteId, entities));
    }
}
