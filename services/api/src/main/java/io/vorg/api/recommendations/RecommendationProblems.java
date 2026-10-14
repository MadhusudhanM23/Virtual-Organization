package io.vorg.api.recommendations;

import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** The typed problems of this module; each carries a stable machine-readable {@code code} (docs/api/recommendations.md). */
public final class RecommendationProblems {

    public static final String GENERATOR_UNAVAILABLE = "generator_unavailable";
    public static final String CURRENT_STATE_UNAVAILABLE = "current_state_unavailable";
    public static final String RUN_NOT_SUCCEEDED = "run_not_succeeded";
    public static final String NOT_ACTIONABLE = "not_actionable";
    public static final String PROPOSAL_PENDING = "proposal_pending";
    public static final String DRY_RUN_UNSUPPORTED = "dry_run_unsupported";
    public static final String INVALID_STATE = "invalid_state";
    public static final String SEPARATION_OF_DUTIES = "separation_of_duties";
    public static final String APPROVER_NOT_ELIGIBLE = "approver_not_eligible";
    public static final String APPROVAL_EXPIRED = "approval_expired";

    private RecommendationProblems() {}

    public static ApiProblemException of(HttpStatus status, String title, String detail, String code) {
        return new ApiProblemException(status, title, detail, code, Map.of());
    }

    public static ApiProblemException of(HttpStatus status, String title, String detail, String code,
            Map<String, Object> properties) {
        return new ApiProblemException(status, title, detail, code, properties);
    }

    public static ApiProblemException notFound(String what) {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "Not found", "The requested " + what + " was not found.");
    }

    public static ApiProblemException invalid(String field, String message) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid.",
                List.of(new FieldError(field, message)));
    }
}
