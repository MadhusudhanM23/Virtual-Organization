package io.vorg.api.platform.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    public record TenantInfo(UUID id, String slug, String name, boolean demoMode) {}

    public record RoleInfo(Role role, ScopeType scopeType, UUID scopeId) {}

    public record MeResponse(UUID actorRef, String displayName, TenantInfo tenant, List<RoleInfo> roles) {}

    private final JdbcClient jdbc;

    public MeController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal Subject subject) {
        TenantInfo tenant = jdbc.sql("select id, slug, name, demo_mode from iam.tenants where id = :id")
                .param("id", subject.tenantId())
                .query((rs, n) -> new TenantInfo(rs.getObject("id", UUID.class), rs.getString("slug"),
                        rs.getString("name"), rs.getBoolean("demo_mode")))
                .single();
        List<RoleInfo> roles = subject.roles().stream()
                .map(r -> new RoleInfo(r.role(), r.scopeType(), r.scopeId())).toList();
        return new MeResponse(subject.actorRef(), subject.displayName(), tenant, roles);
    }
}
