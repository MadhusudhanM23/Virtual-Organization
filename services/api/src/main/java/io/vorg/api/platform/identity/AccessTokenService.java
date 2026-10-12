package io.vorg.api.platform.identity;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Issues RS256 access tokens carrying only pseudonymous ids: iss, sub=actor_ref, tid, iat, exp, jti (ADR-16). */
public class AccessTokenService {

    public static final Duration TTL = Duration.ofMinutes(15);

    private final JwtEncoder encoder;
    private final String issuer;
    private final String keyId;

    public AccessTokenService(JwtKeyProvider keys, String issuer) {
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.rsaKey())));
        this.issuer = issuer;
        this.keyId = keys.rsaKey().getKeyID();
    }

    public String issue(UUID actorRef, UUID tenantId) {
        return issue(actorRef, tenantId, Instant.now(), TTL);
    }

    /** Explicit clock/TTL overload (used by tests to mint expired tokens). */
    public String issue(UUID actorRef, UUID tenantId, Instant issuedAt, Duration ttl) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(actorRef.toString())
                .claim("tid", tenantId.toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
