package io.vorg.api.platform.web;

import java.util.Optional;

/** Holder for the current request's correlation id (thread-bound for the request's duration). */
public final class CorrelationContext {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlation_id";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationContext() {}

    static void set(String id) {
        CURRENT.set(id);
    }

    static void clear() {
        CURRENT.remove();
    }

    public static Optional<String> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Runs {@code action} with {@code id} as the correlation id (and MDC value) for non-HTTP work such as outbox
     * handlers, restoring the previous value afterwards.
     */
    public static <T> T callWith(String id, java.util.function.Supplier<T> action) {
        String previous = CURRENT.get();
        CURRENT.set(id);
        org.slf4j.MDC.put(MDC_KEY, id);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
                org.slf4j.MDC.remove(MDC_KEY);
            } else {
                CURRENT.set(previous);
                org.slf4j.MDC.put(MDC_KEY, previous);
            }
        }
    }
}
