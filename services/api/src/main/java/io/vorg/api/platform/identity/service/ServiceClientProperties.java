package io.vorg.api.platform.identity.service;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Machine clients, {@code vo.service-clients.*} (ADR-66). Each client is bound to one tenant and a set of scopes; its
 * secret comes from the environment (never committed). A client with a blank secret is disabled, so a deployment that
 * does not configure one has no service access at all.
 */
@ConfigurationProperties(prefix = "vo.service-clients")
public record ServiceClientProperties(@DefaultValue List<Client> clients) {

    public record Client(String clientId, UUID tenantId, String secret, @DefaultValue List<String> scopes) {}
}
