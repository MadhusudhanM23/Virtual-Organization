package io.vorg.api.platform.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "vo_refresh";
    static final String CSRF_HEADER = "X-Requested-With";
    static final String CSRF_VALUE = "vorg-web";

    public record LoginRequest(String tenant, String email, String password) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

    private final AuthService auth;
    private final LoginThrottle limiter;
    private final boolean cookieSecure;

    public AuthController(AuthService auth, LoginThrottle limiter,
            @Value("${vo.auth.cookie-secure:true}") boolean cookieSecure) {
        this.auth = auth;
        this.limiter = limiter;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (limiter.isBlocked(ip, body.tenant(), body.email())) {
            return problem(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later.")
                    .header(HttpHeaders.RETRY_AFTER, "60").body(detail(HttpStatus.TOO_MANY_REQUESTS,
                            "Too many attempts. Try again later."));
        }
        Optional<AuthService.Tokens> tokens = auth.login(body.tenant(), body.email(), body.password());
        if (tokens.isEmpty()) {
            limiter.recordFailure(ip, body.tenant(), body.email());
            return invalid();
        }
        limiter.recordSuccess(ip, body.email());
        return ok(tokens.get());
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestHeader(value = CSRF_HEADER, required = false) String requestedWith,
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookie) {
        if (!CSRF_VALUE.equals(requestedWith)) {
            return problem(HttpStatus.FORBIDDEN, "Missing required header.")
                    .body(detail(HttpStatus.FORBIDDEN, "Missing required header."));
        }
        Optional<AuthService.Tokens> tokens = auth.refresh(cookie);
        if (tokens.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, clearCookie().toString())
                    .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication failed."));
        }
        return ok(tokens.get());
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = CSRF_HEADER, required = false) String requestedWith,
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookie) {
        if (!CSRF_VALUE.equals(requestedWith)) {
            return problem(HttpStatus.FORBIDDEN, "Missing required header.")
                    .body(detail(HttpStatus.FORBIDDEN, "Missing required header."));
        }
        auth.logout(cookie);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    private ResponseEntity<?> ok(AuthService.Tokens tokens) {
        ResponseCookie cookie = baseCookie(tokens.refreshCookieValue()).maxAge(tokens.refreshTtl()).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new TokenResponse(tokens.accessToken(), "Bearer", AccessTokenService.TTL.toSeconds()));
    }

    private ResponseEntity<?> invalid() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication failed."));
    }

    private static ResponseEntity.BodyBuilder problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON);
    }

    private static ProblemDetail detail(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }

    private ResponseCookie clearCookie() {
        return baseCookie("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true).secure(cookieSecure).sameSite("Strict").path("/api/auth");
    }
}
