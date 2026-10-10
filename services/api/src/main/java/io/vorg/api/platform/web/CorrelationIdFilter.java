package io.vorg.api.platform.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.vorg.api.platform.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the correlation id for every request. Runs just after the observation filter (HIGHEST_PRECEDENCE + 1,
 * so a trace id exists) and before Spring Security, so even 401/403 responses carry the header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    static final String TRACE_MDC_KEY = "trace_id";

    private final ObjectProvider<Tracer> tracer;

    public CorrelationIdFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = resolve(request.getHeader(CorrelationContext.HEADER));
        CorrelationContext.set(id);
        MDC.put(CorrelationContext.MDC_KEY, id);
        String traceId = currentTraceId();
        if (traceId != null) {
            MDC.put(TRACE_MDC_KEY, traceId);
        }
        response.setHeader(CorrelationContext.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationContext.MDC_KEY);
            MDC.remove(TRACE_MDC_KEY);
            CorrelationContext.clear();
        }
    }

    static String resolve(String header) {
        if (header != null && UUID_PATTERN.matcher(header.trim()).matches()) {
            return header.trim().toLowerCase(Locale.ROOT);
        }
        return Ids.newId().toString();
    }

    private String currentTraceId() {
        Tracer t = tracer.getIfAvailable();
        if (t == null) {
            return null;
        }
        Span span = t.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
