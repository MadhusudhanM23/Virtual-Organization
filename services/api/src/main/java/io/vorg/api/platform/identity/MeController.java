package io.vorg.api.platform.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    /**
     * {@code llm_external_allowed} is the tenant's external-LLM policy flag (REQ-101, ADR-12/17): the AI gateway reads
     * it here with the caller's own token and only then may use an external provider.
     */
    public record TenantInfo(UUID id, String slug, String name, boolean demoMode, boolean llmExternalAllowed) {}

    public record RoleInfo(Role role, ScopeType scopeType, UUID scopeId) {}

    public record MeResponse(UUID actorRef, String displayName, TenantInfo tenant, List<RoleInfo> roles) {}

    private final JdbcClient jdbc;

    public MeController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal Subject subject) {
        TenantInfo tenant = jdbc.sql("select id, slug, name, demo_mode, llm_external_allowed from iam.tenants where id = :id")
                .param("id", subject.tenantId())
                .query((rs, n) -> new TenantInfo(rs.getObject("id", UUID.class), rs.getString("slug"),
                        rs.getString("name"), rs.getBoolean("demo_mode"),
                        rs.getBoolean("llm_external_allowed")))
                .single();
        List<RoleInfo> roles = subject.roles().stream()
                .map(r -> new RoleInfo(r.role(), r.scopeType(), r.scopeId())).toList();
        return new MeResponse(subject.actorRef(), subject.displayName(), tenant, roles);
    }
}
