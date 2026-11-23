package cn.adc.quarterly;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore.EventIdAllocator;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.HashSet;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

@RestController
@ConditionalOnProperty(name = "adc.uc18.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/quarterly/previews/{statisticId}/issues/{issueId}")
final class QuarterlyIssueController {
    interface Access { QuarterlyIssueService open(HttpServletRequest request, String csrf); }
    record StaffAccess(JdbcTransactions tx, StaffBindingAdapter bindings, QuarterlyIssueService.Sources sources,
                       EventIdAllocator historyIds, EventIdAllocator auditIds, Clock clock) implements Access {
        @Override public QuarterlyIssueService open(HttpServletRequest req, String csrf) {
            var identity = new StaffSessionIdentityAdapter(() -> {
                var session = req.getSession(false);
                var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
            });
            identity.requireCsrf(csrf);
            return new QuarterlyIssueService(tx, new StaffAuthorization(identity, bindings::bindings), sources,
                    historyIds, auditIds, clock);
        }
    }

    private final Access access;
    QuarterlyIssueController(Access access) { this.access = access; }

    @GetMapping
    ResponseEntity<JsonNode> read(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                 @PathVariable String statisticId, @PathVariable String issueId,
                                 @RequestParam String clinics, @RequestParam boolean correction) {
        return response(200, access.open(request, csrf).read(target(statisticId, issueId, clinics), correction));
    }

    @PostMapping
    ResponseEntity<JsonNode> save(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                 @RequestHeader("Idempotency-Key") String key, @PathVariable String statisticId,
                                 @PathVariable String issueId, @RequestBody JsonNode input) {
        AuditDocuments.fields(input, "clinics", "correction", "historyId", "evidence", "value");
        if (!input.path("correction").isBoolean()) throw new IllegalArgumentException("INVALID_ISSUE_OPERATION");
        var history = nullable(input, "historyId");
        boolean correction = input.path("correction").asBoolean();
        var evidence = nullable(input, "evidence");
        var value = nullable(input, "value");
        if (!correction && (value != null || evidence != null)) throw new IllegalArgumentException("REVIEW_INPUT_MISMATCH");
        var receipt = access.open(request, csrf).execute(target(statisticId, issueId, AuditDocuments.text(input, "clinics")),
                UUID.fromString(key), correction, history == null ? null : id(history), evidence, value);
        var result = receipt.result();
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null && result != null
                && statisticId.equals(result.path("statisticId").asText()) && issueId.equals(result.path("issueId").asText())) {
            var body = JSON.createObjectNode().put("requestReference", receipt.requestReference());
            body.set("issue", result);
            return response(200, body);
        }
        if ("REJECTED".equals(receipt.businessOutcome())) return error(409, "QUARTER_ISSUE_REJECTED", receipt.requestReference());
        return error(503, "QUARTER_ISSUE_UNCONFIRMED", receipt.requestReference());
    }

    private static QuarterlyIssueService.Target target(String statistic, String issue, String clinics) {
        var ids = new HashSet<Long>();
        for (var text : clinics.split(",", -1)) {
            if (!ids.add(id(text))) throw new IllegalArgumentException("DUPLICATE_CLINIC");
        }
        return new QuarterlyIssueService.Target(id(statistic), id(issue), ids);
    }

    private static long id(String text) {
        long value = Long.parseLong(text);
        if (value <= 0 || !Long.toString(value).equals(text)) throw new IllegalArgumentException("INVALID_ISSUE_ID");
        return value;
    }

    private static String nullable(JsonNode input, String field) {
        return input.path(field).isNull() ? null : AuditDocuments.text(input, field);
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> denied(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "QUARTER_ISSUE_ACCESS_DENIED", null);
    }

    @ExceptionHandler(cn.adc.shared.audit.CommandRecovery.Rejection.class)
    ResponseEntity<JsonNode> issueRejected() { return error(409, "QUARTER_ISSUE_REJECTED", null); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_QUARTER_ISSUE", null); }

    @ExceptionHandler(JdbcTransactions.Failure.class)
    ResponseEntity<JsonNode> unavailable() { return error(503, "QUARTER_ISSUE_UNAVAILABLE", null); }

    private static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code);
        if (reference != null) body.put("requestReference", reference);
        return response(status, body);
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
