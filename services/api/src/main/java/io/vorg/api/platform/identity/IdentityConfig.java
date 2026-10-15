package io.vorg.api.platform.identity;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class IdentityConfig {

    @Bean
    JwtKeyProvider jwtKeyProvider(@Value("${vo.jwt.key-path:}") String keyPath,
            @Value("${vo.jwt.generate-if-absent:false}") boolean generateIfAbsent) {
        return new JwtKeyProvider(keyPath, generateIfAbsent);
    }

    @Bean
    AccessTokenService accessTokenService(JwtKeyProvider keys, @Value("${vo.jwt.issuer}") String issuer) {
        return new AccessTokenService(keys, issuer);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * Validates tokens against our own signing key by default. Setting
     * {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} (our own JWKS or an external IdP) makes Spring
     * Boot build the decoder from that URI instead, so swapping the IdP is configuration only (ADR-16).
     */
    @Bean
    @ConditionalOnExpression("'${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}'.isEmpty()")
    JwtDecoder localJwtDecoder(JwtKeyProvider keys, @Value("${vo.jwt.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
        decoder.setJwtValidator(tokenValidator(issuer));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer) {
        OAuth2TokenValidator<Jwt> ids = jwt -> {
            try {
                UUID.fromString(jwt.getClaimAsString("tid"));
                UUID.fromString(jwt.getSubject());
                return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException ex) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "tid/sub invalid", null));
            }
        };
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), ids);
    }

    @Bean
    LoginThrottle loginThrottle() {
        return LoginThrottle.standard();
    }
}
