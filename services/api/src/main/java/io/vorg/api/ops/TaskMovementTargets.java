package io.vorg.api.ops;

import io.vorg.api.integration.simulated.MovementTargetProvider;
import io.vorg.api.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Sends a forklift to the work assigned to it: the oldest open task of each assigned entity, to its origin while the
 * task is ASSIGNED or ACCEPTED and to its destination once STARTED. A paused task holds the forklift where it is (no
 * target). This links the simulated movement to the task lifecycle without the movement source knowing about tasks.
 */
@Component
public class TaskMovementTargets implements MovementTargetProvider {

    private final JdbcClient jdbc;

    public TaskMovementTargets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Target> activeTargets(UUID tenantId, UUID siteId) {
        return TenantContext.callWith(tenantId, () -> jdbc.sql("""
                select distinct on (t.assigned_entity_id) t.assigned_entity_id, t.id, t.status,
                       case when t.status = 'STARTED' then t.to_x else t.from_x end as x,
                       case when t.status = 'STARTED' then t.to_y else t.from_y end as y
                from ops.tasks t join world.entities e on e.id = t.assigned_entity_id
                where t.site_id = :site and t.status in ('ASSIGNED', 'ACCEPTED', 'STARTED')
                  and e.entity_type = 'FORKLIFT'
                order by t.assigned_entity_id, t.created_at, t.id
                """).param("site", siteId)
                .query((rs, n) -> new Target(rs.getObject("assigned_entity_id", UUID.class),
                        rs.getObject("id", UUID.class) + ":" + ("STARTED".equals(rs.getString("status")) ? "to"
                                : "from"), rs.getDouble("x"), rs.getDouble("y"))).list());
    }
}
