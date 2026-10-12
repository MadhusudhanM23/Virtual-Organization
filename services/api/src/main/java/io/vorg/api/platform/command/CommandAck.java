package io.vorg.api.platform.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Wire shape of {@code command-ack.v1.schema.json}; serialized snake_case by the application mapper. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandAck(UUID commandId, String status, UUID correlationId, JsonNode result) {}
