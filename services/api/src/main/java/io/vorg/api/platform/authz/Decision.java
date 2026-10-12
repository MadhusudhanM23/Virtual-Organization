package io.vorg.api.platform.authz;

/** Outcome of an authorization check. {@code requiresPolicyCheck} marks R* cells (approval-class policy, Phase 8). */
public record Decision(boolean allowed, String code, String reason, boolean requiresPolicyCheck) {

    public static Decision allow(String code, String reason, boolean requiresPolicyCheck) {
        return new Decision(true, code, reason, requiresPolicyCheck);
    }

    public static Decision deny(String code, String reason) {
        return new Decision(false, code, reason, false);
    }
}
