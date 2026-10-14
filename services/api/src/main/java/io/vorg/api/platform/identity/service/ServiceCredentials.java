package io.vorg.api.platform.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Verifies {@code Authorization: Service <client_id>:<secret>} (ADR-66). Only SHA-256 digests of the configured
 * secrets are kept in memory; the comparison is constant-time. Secrets shorter than 24 characters are refused at
 * startup (the client stays disabled), so a trivial dev value cannot slip into a deployment unnoticed.
 */
@Component
@EnableConfigurationProperties(ServiceClientProperties.class)
public class ServiceCredentials {

    public static final String SCHEME = "Service ";
    static final int MIN_SECRET_LENGTH = 24;
    private static final Pattern CLIENT_ID = Pattern.compile("^[A-Za-z0-9._-]{1,60}$");
    private static final Logger LOG = LoggerFactory.getLogger(ServiceCredentials.class);

    private record Entry(ServicePrincipal principal, byte[] digest) {}

    private final Map<String, Entry> clients = new LinkedHashMap<>();

    public ServiceCredentials(ServiceClientProperties props) {
        for (ServiceClientProperties.Client c : props.clients()) {
            if (c.clientId() == null || !CLIENT_ID.matcher(c.clientId()).matches() || c.tenantId() == null) {
                LOG.warn("Ignoring a service client with an invalid client id or no tenant");
                continue;
            }
            if (c.secret() == null || c.secret().isBlank()) {
                LOG.info("Service client {} is disabled (no secret configured)", c.clientId());
                continue;
            }
            if (c.secret().length() < MIN_SECRET_LENGTH) {
                LOG.warn("Service client {} is disabled: its secret is shorter than {} characters", c.clientId(),
                        MIN_SECRET_LENGTH);
                continue;
            }
            ServicePrincipal principal = new ServicePrincipal(c.clientId(), c.tenantId(),
                    ServicePrincipal.actorRefOf(c.clientId()), new HashSet<>(c.scopes()));
            clients.put(c.clientId(), new Entry(principal, sha256(c.secret())));
        }
    }

    /** The principal for a raw {@code Authorization} header value, or empty when it is absent, malformed or wrong. */
    public Optional<ServicePrincipal> authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return Optional.empty();
        }
        String credential = authorization.substring(SCHEME.length()).trim();
        int sep = credential.indexOf(':');
        if (sep <= 0 || sep == credential.length() - 1) {
            return Optional.empty();
        }
        Entry entry = clients.get(credential.substring(0, sep));
        byte[] given = sha256(credential.substring(sep + 1));
        if (entry == null) {
            MessageDigest.isEqual(given, given); // keep timing similar for unknown clients
            return Optional.empty();
        }
        return MessageDigest.isEqual(entry.digest(), given) ? Optional.of(entry.principal()) : Optional.empty();
    }

    public boolean anyEnabled() {
        return !clients.isEmpty();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
