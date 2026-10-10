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
}
