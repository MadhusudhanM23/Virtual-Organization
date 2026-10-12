package io.vorg.api.platform.command;

import java.util.Map;

/** The command-specific outcome returned in {@code command-ack.v1.result}; keys are snake_case, no personal data. */
public record CommandResult(Map<String, Object> data) {

    public CommandResult {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static CommandResult of(Map<String, Object> data) {
        return new CommandResult(data);
    }
}
