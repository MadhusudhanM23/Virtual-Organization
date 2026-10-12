package io.vorg.api.platform.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * Loads the RS256 signing key from {@code vo.jwt.key-path} (env {@code VO_JWT_KEY_PATH}); when absent it is generated
 * only if {@code vo.jwt.generate-if-absent=true} (local/test profiles). The key path must be gitignored (*.pem). ADR-16.
 */
public class JwtKeyProvider {

    private static final String BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String END = "-----END PRIVATE KEY-----";

    private final RSAKey rsaKey;

    public JwtKeyProvider(String keyPath, boolean generateIfAbsent) {
        if (keyPath == null || keyPath.isBlank()) {
            throw new IllegalStateException("VO_JWT_KEY_PATH (vo.jwt.key-path) is not configured");
        }
        try {
            Path path = Path.of(keyPath).toAbsolutePath();
            if (!Files.exists(path)) {
                if (!generateIfAbsent) {
                    throw new IllegalStateException("JWT signing key not found at " + path
                            + " and key generation is disabled (vo.jwt.generate-if-absent=false)");
                }
                generate(path);
            }
            this.rsaKey = load(path);
        } catch (IOException | GeneralSecurityException | JOSEException ex) {
            throw new IllegalStateException("Cannot load JWT signing key", ex);
        }
    }

    private static void generate(Path path) throws IOException, GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded());
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, BEGIN + "\n" + body + "\n" + END + "\n", StandardCharsets.US_ASCII);
    }

    private static RSAKey load(Path path) throws IOException, GeneralSecurityException, JOSEException {
        String pem = Files.readString(path, StandardCharsets.US_ASCII)
                .replace(BEGIN, "").replace(END, "").replaceAll("\\s", "");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
        RSAKey unnamed = new RSAKey.Builder(pub).privateKey(priv).build();
        return new RSAKey.Builder(pub).privateKey(priv).keyID(unnamed.computeThumbprint().toString()).build();
    }

    public RSAKey rsaKey() {
        return rsaKey;
    }

    public RSAPublicKey publicKey() {
        try {
            return rsaKey.toRSAPublicKey();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Public-only JWK set as JSON, for {@code /.well-known/jwks.json}. */
    public String publicJwksJson() {
        return new JWKSet(rsaKey.toPublicJWK()).toString();
    }
}
