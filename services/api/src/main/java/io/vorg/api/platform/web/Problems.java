package io.vorg.api.platform.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/** Writes RFC 9457 problem+json from servlet-level code (filters, entry points) that bypasses MVC advice. */
public final class Problems {

    private Problems() {}

    public static void write(JsonMapper mapper, HttpServletResponse res, HttpStatus status, String title,
            String detail) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        CorrelationContext.current().ifPresent(id -> body.put("correlation_id", id));
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write(mapper.writeValueAsString(body));
    }
}
