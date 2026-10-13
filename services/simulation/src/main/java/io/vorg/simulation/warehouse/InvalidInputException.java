package io.vorg.simulation.warehouse;

/** A model input or scenario that violates the schema or refers to unknown entities. The message names the path. */
public final class InvalidInputException extends IllegalArgumentException {

    public InvalidInputException(String message) {
        super(message);
    }
}
