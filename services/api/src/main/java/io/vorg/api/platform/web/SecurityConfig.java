package io.vorg.api.platform.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Temporary security baseline until Plan 1.4 adds JWT: stateless, health/info public, everything else denied
 * with a problem+json 401. CSRF is disabled for the stateless API (Plan 1.4 re-evaluates for the refresh-cookie
 * path). {@code /actuator/prometheus} is public only when {@code vo.security.permit-prometheus=true} (local);
 * production deployments must protect it (network policy or auth).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JsonMapper jsonMapper,
            @Value("${vo.security.permit-prometheus:false}") boolean permitPrometheus) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
                    if (permitPrometheus) {
                        auth.requestMatchers("/actuator/prometheus").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeProblem(jsonMapper, res,
                                HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required."))
                        .accessDeniedHandler((req, res, ex) -> writeProblem(jsonMapper, res,
                                HttpStatus.FORBIDDEN, "Forbidden", "Access is denied.")));
        return http.build();
    }

    /** Suppresses the generated default user; real authentication arrives with Plan 1.4. */
    @Bean
    InMemoryUserDetailsManager noUsers() {
        return new InMemoryUserDetailsManager();
    }

    private static void writeProblem(JsonMapper mapper, HttpServletResponse res, HttpStatus status, String title,
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
