package io.vorg.api.ops;

import io.vorg.api.integration.core.MirrorRecordListener;
import io.vorg.api.integration.core.RecordKind;
import io.vorg.api.ops.TaskService.NewTask;
import io.vorg.api.ops.TaskService.Place;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.live.LiveProperties;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Turns a newly mirrored source TASK record (SIM-WMS) into a warehouse task in status ASSIGNED, with a TASK_ASSIGNED
 * event labeled with the source's provenance (REQ-040/043). The task is assigned to the forklift named by the source
 * ({@code assignee_entity_code}) and, when {@code vo.live.operator-actor-ref} is configured, to that demo operator so a
 * Driver/Operator can work it through the API. A re-delivered record creates nothing (unique source key).
 */
@Component
public class TaskMirrorListener implements MirrorRecordListener {

    private final TaskService tasks;
    private final JdbcClient jdbc;
    private final LiveProperties live;

    public TaskMirrorListener(TaskService tasks, JdbcClient jdbc, LiveProperties live) {
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.live = live;
    }

    @Override
    public void onApplied(Applied applied) {
        if (applied.kind() != RecordKind.TASK || !applied.created()) {
            return;
        }
        JsonNode p = applied.payload();
        Optional<UUID> entity = entityId(applied.siteId(), text(p, "assignee_entity_code"));
        Optional<UUID> pallet = entityId(applied.siteId(), text(p, "pallet_code"));
        tasks.create(new NewTask(applied.tenantId(), applied.siteId(), p.get("task_type").asString(),
                p.hasNonNull("priority") ? p.get("priority").asInt() : 2, text(p, "instruction") == null
                        ? p.get("task_type").asString() + " task" : text(p, "instruction"),
                applied.sourceSystem(), applied.sourceRecordId(), live.operatorActorRef(), entity.orElse(null),
                pallet.orElse(null), place(p.get("from")), place(p.get("to")), applied.dataMode(),
                EventSource.SIMULATION, "svc:adapter:" + applied.sourceSystem()));
    }

    private Optional<UUID> entityId(UUID siteId, String code) {
        if (code == null) {
            return Optional.empty();
        }
        return jdbc.sql("select id from world.entities where site_id = :s and code = :c")
                .param("s", siteId).param("c", code).query(UUID.class).optional();
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private static Place place(JsonNode node) {
        return new Place(node.get("label").asString(), node.get("x").asDouble(), node.get("y").asDouble());
    }
}
