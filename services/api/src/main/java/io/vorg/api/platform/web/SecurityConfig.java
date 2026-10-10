package io.vorg.api.platform.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Temporary security baseline until Plan 1.4 adds JWT: stateless, health/info public, everything else denied.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
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
                .cors(Customizer.withDefaults());
        return http.build();
    }

    /** Suppresses the generated default user; real authentication arrives with Plan 1.4. */
    @Bean
    InMemoryUserDetailsManager noUsers() {
        return new InMemoryUserDetailsManager();
    }
}
