package io.vorg.api.platform.identity;

import java.util.UUID;

public record RoleAssignment(Role role, ScopeType scopeType, UUID scopeId) {}
