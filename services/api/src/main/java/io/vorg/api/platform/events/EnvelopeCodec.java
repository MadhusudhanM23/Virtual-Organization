package io.vorg.api.platform.events;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** The one serializer for envelopes: snake_case, ISO-8601 UTC instants, independent of the web mapper settings. */
public final class EnvelopeCodec {

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public JsonNode toTree(Object value) {
        return mapper.valueToTree(value);
    }

    public JsonNode toNode(EventEnvelope envelope) {
        return mapper.valueToTree(envelope);
    }

    public String toJson(EventEnvelope envelope) {
        return mapper.writeValueAsString(envelope);
    }

    public String toPrettyJson(EventEnvelope envelope) {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
    }

    public JsonNode readTree(String json) {
        return mapper.readTree(json);
    }

    public EventLocation readLocation(String json) {
        return json == null ? null : mapper.readValue(json, EventLocation.class);
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }
}
