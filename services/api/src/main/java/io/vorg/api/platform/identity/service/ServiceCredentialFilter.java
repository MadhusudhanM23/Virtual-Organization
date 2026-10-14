package io.vorg.api.platform.identity.service;

import io.vorg.api.platform.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Service-credential authentication (ADR-66), registered in the security chain before the bearer filter. It acts only
 * on {@link #PATH_PREFIX}: there a request must carry a valid {@code Authorization: Service ...} header (a bearer
 * user token is refused with 401: no human role may assert device observations, ADR-32); everywhere else the filter
 * does nothing, so a service credential can never reach a human endpoint. Not a Spring bean on purpose.
 */
public class ServiceCredentialFilter extends OncePerRequestFilter {

    public static final String PATH_PREFIX = "/api/vision/";

    private final ServiceCredentials credentials;
    private final JsonMapper mapper;

    public ServiceCredentialFilter(ServiceCredentials credentials, JsonMapper mapper) {
        this.credentials = credentials;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<ServicePrincipal> principal = credentials.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (principal.isEmpty()) {
            SecurityContextHolder.clearContext();
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Service");
            Problems.write(mapper, response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                    "A valid service credential is required.");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new ServiceAuthenticationToken(principal.get()));
        try {
            chain.doFilter(new NoBearerRequest(request), response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Authentication whose principal is the {@link ServicePrincipal}; no authorities. */
    public static final class ServiceAuthenticationToken extends AbstractAuthenticationToken {

        private final transient ServicePrincipal principal;

        public ServiceAuthenticationToken(ServicePrincipal principal) {
            super(List.of());
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }
    }

    /** Hides the Authorization header from the bearer-token filter downstream (it is a Service credential). */
    private static final class NoBearerRequest extends jakarta.servlet.http.HttpServletRequestWrapper {

        NoBearerRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public java.util.Enumeration<String> getHeaders(String name) {
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? java.util.Collections.emptyEnumeration()
                    : super.getHeaders(name);
        }
    }
}
