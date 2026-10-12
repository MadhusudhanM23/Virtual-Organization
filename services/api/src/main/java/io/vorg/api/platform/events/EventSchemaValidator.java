package io.vorg.api.platform.events;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.dialect.Dialects;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Validates envelopes against the shared {@code event-envelope.v1} JSON Schema (draft 2020-12, format assertions on,
 * so uuid and date-time are enforced). The schema is copied from packages/contracts onto the classpath at build time
 * and is the single definition of the shape.
 */
@Component
public class EventSchemaValidator {

    static final String ENVELOPE_RESOURCE = "/contracts/schemas/event-envelope.v1.schema.json";

    private final Schema schema;

    public EventSchemaValidator() {
        SchemaRegistry registry = SchemaRegistry.withDialect(Dialects.getDraft202012(),
                builder -> builder.schemaRegistryConfig(
                        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
        try (InputStream in = EventSchemaValidator.class.getResourceAsStream(ENVELOPE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + ENVELOPE_RESOURCE);
            }
            this.schema = registry.getSchema(in, InputFormat.JSON);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + ENVELOPE_RESOURCE, ex);
        }
    }

    public List<String> validate(JsonNode envelope) {
        return schema.validate(envelope).stream().map(e -> e.getInstanceLocation() + ": " + e.getMessage()).toList();
    }

    public void requireValid(JsonNode envelope) {
        List<String> violations = validate(envelope);
        if (!violations.isEmpty()) {
            throw new EventSchemaViolationException(violations);
        }
    }
}
