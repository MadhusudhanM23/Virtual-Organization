package io.vorg.api.ai;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Defense in depth for exchange records (ADR-65): {@code services/ai} redacts secrets before it records an exchange,
 * and the API refuses a record that still contains one, so a raw credential can never be persisted by this path.
 * The same patterns are used by the gateway's redactor ({@code services/ai/src/vorg_ai/redaction.py}).
 */
public final class SecretPatterns {

    private static final List<Pattern> PATTERNS = List.of(
            // JSON Web Token (three base64url segments, header starts with {" => eyJ).
            Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,}"),
            // OpenAI-style and similar API keys.
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("(?i)\\b(password|passwd|api[_-]?key|secret)\\s*[=:]\\s*[^\\s\"',]{4,}"));

    private SecretPatterns() {}

    /** True when the text contains something that looks like a credential. */
    public static boolean containsSecret(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (Pattern p : PATTERNS) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
