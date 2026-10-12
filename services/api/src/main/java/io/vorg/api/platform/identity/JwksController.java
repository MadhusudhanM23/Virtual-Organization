package io.vorg.api.platform.identity;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public signing key (never the private part) so resource-server validation is JWKS-driven (ADR-16). */
@RestController
public class JwksController {

    private final JwtKeyProvider keys;

    public JwksController(JwtKeyProvider keys) {
        this.keys = keys;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> jwks() {
        return ResponseEntity.ok().header("Cache-Control", "public, max-age=300").body(keys.publicJwksJson());
    }
}
