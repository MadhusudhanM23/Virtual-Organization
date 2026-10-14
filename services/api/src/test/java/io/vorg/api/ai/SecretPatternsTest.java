package io.vorg.api.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecretPatternsTest {

    @Test
    void detectsCredentialLikeValues() {
        assertTrue(SecretPatterns.containsSecret("token eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXZhbHVl"));
        assertTrue(SecretPatterns.containsSecret("Authorization: Bearer abcdefghijklmnop1234"));
        assertTrue(SecretPatterns.containsSecret("key sk-proj-abcdefghijklmnopqrstuvwx"));
        assertTrue(SecretPatterns.containsSecret("password=hunter2hunter2"));
    }

    @Test
    void acceptsRedactedOperationalText() {
        assertFalse(SecretPatterns.containsSecret(null));
        assertFalse(SecretPatterns.containsSecret("Why is dock IN-02 congested? [SECRET_REDACTED] [EMAIL_1]"));
        assertFalse(SecretPatterns.containsSecret("Inbound truck dock wait rose from 3.70 to 10.18 min [c1]"));
    }
}
