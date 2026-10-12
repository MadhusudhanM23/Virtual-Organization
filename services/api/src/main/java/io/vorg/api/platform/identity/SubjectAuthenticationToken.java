package io.vorg.api.platform.identity;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/** Authentication whose principal is the loaded {@link Subject}; roles are never authorities (the policy engine decides). */
public class SubjectAuthenticationToken extends AbstractAuthenticationToken {

    private final transient Subject subject;
    private final transient Jwt jwt;

    public SubjectAuthenticationToken(Subject subject, Jwt jwt) {
        super(List.of());
        this.subject = subject;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public Object getPrincipal() {
        return subject;
    }
}
