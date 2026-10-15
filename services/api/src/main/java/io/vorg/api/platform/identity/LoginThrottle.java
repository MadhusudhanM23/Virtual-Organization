package io.vorg.api.platform.identity;

import java.time.Duration;
import java.util.Locale;

/**
 * Brute-force protection for {@code POST /api/auth/login} (Plan 11.3). Three independent sliding windows of FAILED
 * attempts, so no single dimension can be walked around:
 * <ul>
 *   <li><b>address + account</b>: 5 per minute, the original limit, stops hammering one account from one address;</li>
 *   <li><b>address</b>: 30 per 10 minutes over ALL accounts, stops password spraying (one password against many
 *       accounts) from one address, which the per-account limit never sees;</li>
 *   <li><b>account</b>: 20 per 10 minutes from ALL addresses, stops a distributed attack on one account.</li>
 * </ul>
 * A success clears only the address+account window; it never resets the other two, so an attacker cannot interleave a
 * valid login of their own to refill their budget. Single-node and in memory (a Redis-backed limiter is the multi-node
 * follow-up, ADR-71 area).
 */
public class LoginThrottle {

    private final LoginRateLimiter pair;
    private final LoginRateLimiter address;
    private final LoginRateLimiter account;

    public LoginThrottle(LoginRateLimiter pair, LoginRateLimiter address, LoginRateLimiter account) {
        this.pair = pair;
        this.address = address;
        this.account = account;
    }

    public static LoginThrottle standard() {
        return new LoginThrottle(new LoginRateLimiter(5, Duration.ofMinutes(1)),
                new LoginRateLimiter(30, Duration.ofMinutes(10)), new LoginRateLimiter(20, Duration.ofMinutes(10)));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isBlocked(String ip, String tenant, String email) {
        return pair.isBlocked(ip + "|" + norm(email)) || address.isBlocked(ip)
                || account.isBlocked(norm(tenant) + "|" + norm(email));
    }

    public void recordFailure(String ip, String tenant, String email) {
        pair.recordFailure(ip + "|" + norm(email));
        address.recordFailure(ip);
        account.recordFailure(norm(tenant) + "|" + norm(email));
    }

    public void recordSuccess(String ip, String email) {
        pair.recordSuccess(ip + "|" + norm(email));
    }
}
