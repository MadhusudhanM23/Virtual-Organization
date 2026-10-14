package io.vorg.api.ai.web;

import io.vorg.api.ai.AiExchangeService;
import io.vorg.api.ai.AiExchangeService.Exchange;
import io.vorg.api.ai.AiExchangeService.ExchangeView;
import io.vorg.api.ai.SecretPatterns;
import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.Decision;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.command.CommandAck;
import io.vorg.api.platform.command.CommandGateway;
import io.vorg.api.platform.command.CommandSpec;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.platform.web.PageParams.Page;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exchange records of the AI assistant (Plan 9.1, REQ-106, ADR-65). {@code POST /api/ai/exchanges} is a command
 * ({@code Idempotency-Key}, the gateway uses the exchange id) authorized {@code VIEW_OPERATIONAL_STATE} on the site;
 * {@code GET /api/ai/exchanges} lists the caller's own records. This is an audit append, not an operational write:
 * the assistant has no other write path of its own (it proposes only through the existing commands).
 */
@RestController
public class AiExchangeController {

    private static final Set<String> PROVIDERS = Set.of("offline", "openai");
    private static final Set<String> RETENTIONS = Set.of("redacted", "none");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final int MAX_QUESTION = 8000;
    private static final int MAX_ANSWER = 16000;
    private static final int MAX_LIST = 50;

    public record ExchangeRequest(UUID exchangeId, UUID siteId, UUID conversationId, String provider, String model,
            Boolean external, String providerReason, String retention, String questionSha256, String questionText,
            String answerText, Integer promptTokens, Integer completionTokens, String tokenCountMethod,
            List<Object> toolCalls, List<Object> citations, Map<String, Object> redactions, List<Object> flags,
            Boolean grounded, Integer latencyMs) {}

    private final AiExchangeService exchanges;
    private final CommandGateway gateway;
    private final JsonMapper mapper;

    public AiExchangeController(AiExchangeService exchanges, CommandGateway gateway, JsonMapper mapper) {
        this.exchanges = exchanges;
        this.gateway = gateway;
        this.mapper = mapper;
    }

    @PostMapping("/api/ai/exchanges")
    public ResponseEntity<CommandAck> record(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) ExchangeRequest body, @AuthenticationPrincipal Subject subject) {
        Exchange exchange = validate(body);
        if (SecretPatterns.containsSecret(mapper.writeValueAsString(body))) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Secret detected",
                    "The exchange record still contains a credential-like value; it was not stored.",
                    "secret_detected", Map.of());
        }
        Decision check = exchanges.check(subject, exchange.siteId());
        if ("DENY_UNKNOWN_RESOURCE".equals(check.code())) {
            throw AiExchangeService.denied(check);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exchange_id", exchange.clientExchangeId().toString());
        payload.put("site_id", exchange.siteId().toString());
        payload.put("conversation_id", exchange.conversationId().toString());
        payload.put("provider", exchange.provider());
        payload.put("question_sha256", exchange.questionSha256());
        CommandSpec spec = new CommandSpec("AI_EXCHANGE_RECORD", key, subject, Action.VIEW_OPERATIONAL_STATE,
                ResourceRef.site(subject.tenantId(), exchange.siteId()), PolicyContext.none(), payload);
        return ResponseEntity.ok(gateway.execute(spec, () -> exchanges.record(subject, exchange)));
    }

    @GetMapping("/api/ai/exchanges")
    public Page<ExchangeView> list(@RequestParam("site_id") UUID siteId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal Subject subject) {
        return exchanges.listOwn(subject, siteId, limit, cursor);
    }

    private static Exchange validate(ExchangeRequest b) {
        List<FieldError> errors = new ArrayList<>();
        if (b == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed", "A body is required.",
                    List.of(new FieldError("body", "is required")));
        }
        require(errors, b.exchangeId(), "exchange_id");
        require(errors, b.siteId(), "site_id");
        require(errors, b.conversationId(), "conversation_id");
        if (b.provider() == null || !PROVIDERS.contains(b.provider())) {
            errors.add(new FieldError("provider", "must be one of " + PROVIDERS));
        }
        text(errors, b.model(), "model", 100, true);
        text(errors, b.providerReason(), "provider_reason", 60, true);
        text(errors, b.tokenCountMethod(), "token_count_method", 30, true);
        if (b.retention() == null || !RETENTIONS.contains(b.retention())) {
            errors.add(new FieldError("retention", "must be one of " + RETENTIONS));
        }
        if (b.questionSha256() == null || !SHA256.matcher(b.questionSha256()).matches()) {
            errors.add(new FieldError("question_sha256", "must be 64 lowercase hex characters"));
        }
        text(errors, b.questionText(), "question_text", MAX_QUESTION, false);
        text(errors, b.answerText(), "answer_text", MAX_ANSWER, false);
        if ("none".equals(b.retention()) && (b.questionText() != null || b.answerText() != null)) {
            errors.add(new FieldError("retention", "none means question_text and answer_text are omitted"));
        }
        nonNegative(errors, b.promptTokens(), "prompt_tokens");
        nonNegative(errors, b.completionTokens(), "completion_tokens");
        nonNegative(errors, b.latencyMs(), "latency_ms");
        require(errors, b.external(), "external");
        require(errors, b.grounded(), "grounded");
        list(errors, b.toolCalls(), "tool_calls");
        list(errors, b.citations(), "citations");
        list(errors, b.flags(), "flags");
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more fields are invalid.", errors);
        }
        return new Exchange(b.exchangeId(), b.siteId(), b.conversationId(), b.provider(), b.model(), b.external(),
                b.providerReason(), b.retention(), b.questionSha256(), b.questionText(), b.answerText(),
                b.promptTokens(), b.completionTokens(), b.tokenCountMethod(), b.toolCalls(), b.citations(),
                b.redactions() == null ? Map.of() : b.redactions(), b.flags(), b.grounded(), b.latencyMs());
    }

    private static void require(List<FieldError> errors, Object value, String field) {
        if (value == null) {
            errors.add(new FieldError(field, "is required"));
        }
    }

    private static void text(List<FieldError> errors, String value, String field, int max, boolean required) {
        if (value == null) {
            if (required) {
                errors.add(new FieldError(field, "is required"));
            }
            return;
        }
        if (value.length() > max || (required && value.isBlank())) {
            errors.add(new FieldError(field, (required ? "is required and " : "") + "at most " + max + " characters"));
        }
    }

    private static void nonNegative(List<FieldError> errors, Integer value, String field) {
        if (value == null || value < 0) {
            errors.add(new FieldError(field, "is required and must be >= 0"));
        }
    }

    private static void list(List<FieldError> errors, List<Object> value, String field) {
        if (value == null) {
            errors.add(new FieldError(field, "is required (may be empty)"));
        } else if (value.size() > MAX_LIST) {
            errors.add(new FieldError(field, "at most " + MAX_LIST + " items"));
        }
    }
}
