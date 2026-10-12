package io.vorg.api.platform.web;

import io.vorg.api.platform.identity.SubjectService;
import io.vorg.api.platform.identity.TenantSubjectFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless resource server (ADR-16): bearer JWT everywhere except health/info, the auth endpoints and JWKS.
 * CSRF is disabled for the bearer API; the cookie-based refresh/logout endpoints require the custom
 * {@code X-Requested-With: vorg-web} header and a SameSite=Strict cookie instead. {@code /actuator/prometheus} is public only when {@code vo.security.permit-prometheus=true} (local);
 * production deployments must protect it (network policy or auth).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JsonMapper jsonMapper, SubjectService subjects,
            @Value("${vo.security.permit-prometheus:false}") boolean permitPrometheus) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
                    auth.requestMatchers("/ws/live").permitAll(); // token is verified in the first message (ADR-37)
                    auth.requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                            "/.well-known/jwks.json").permitAll();
                    if (permitPrometheus) {
                        auth.requestMatchers("/actuator/prometheus").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint(jsonMapper))
                        .accessDeniedHandler((req, res, ex) -> Problems.write(jsonMapper, res,
                                HttpStatus.FORBIDDEN, "Forbidden", "Access is denied.")))
                .addFilterAfter(new TenantSubjectFilter(subjects, jsonMapper), BearerTokenAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint(jsonMapper))
                        .accessDeniedHandler((req, res, ex) -> Problems.write(jsonMapper, res,
                                HttpStatus.FORBIDDEN, "Forbidden", "Access is denied.")));
        return http.build();
    }

    /** Suppresses the generated default user; real authentication arrives with Plan 1.4. */
    @Bean
    InMemoryUserDetailsManager noUsers() {
        return new InMemoryUserDetailsManager();
    }

    private static AuthenticationEntryPoint entryPoint(JsonMapper jsonMapper) {
        return (req, res, ex) -> Problems.write(jsonMapper, res, HttpStatus.UNAUTHORIZED, "Unauthorized",
                "Authentication is required.");
    }
}
