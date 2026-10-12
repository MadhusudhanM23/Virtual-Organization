package io.vorg.api.platform.identity;

import io.vorg.api.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import io.vorg.api.platform.web.Problems;

/**
 * After bearer-token validation: binds {@link TenantContext} from the {@code tid} claim, loads the {@link Subject}
 * (rejecting disabled/erased/unknown actors with 401) and always clears the tenant context in {@code finally}.
 * Deliberately not a Spring bean so it is only registered inside the security filter chain.
 */
public class TenantSubjectFilter extends OncePerRequestFilter {

    private final SubjectService subjects;
    private final JsonMapper mapper;

    public TenantSubjectFilter(SubjectService subjects, JsonMapper mapper) {
        this.subjects = subjects;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth)) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = auth.getToken();
        UUID tenantId;
        UUID actorRef;
        try {
            tenantId = UUID.fromString(jwt.getClaimAsString("tid"));
            actorRef = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException ex) {
            reject(response);
            return;
        }
        TenantContext.set(tenantId);
        try {
            Optional<Subject> subject = subjects.load(tenantId, actorRef);
            if (subject.isEmpty()) {
                reject(response);
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(new SubjectAuthenticationToken(subject.get(), jwt));
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        Problems.write(mapper, response, HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required.");
    }
}
