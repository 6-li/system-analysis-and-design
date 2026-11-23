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
@ConditionalOnProperty(name = "adc.uc17.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/quarterly/previews")
final class QuarterlyPreviewController {
    interface Access { QuarterlyPreviewService open(HttpServletRequest request, String csrf); }
    record StaffAccess(JdbcTransactions tx, StaffBindingAdapter bindings, QuarterlyPreviewService.Sources sources,
                       EventIdAllocator snapshots, EventIdAllocator issues, EventIdAllocator auditIds, Clock clock) implements Access {
        @Override public QuarterlyPreviewService open(HttpServletRequest req, String csrf) {
            var identity = new StaffSessionIdentityAdapter(() -> {
                var session = req.getSession(false);
                var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
            });
            identity.requireCsrf(csrf);
            return new QuarterlyPreviewService(tx, new StaffAuthorization(identity, bindings::bindings), sources,
                    snapshots, issues, auditIds, clock);
        }
    }

    private final Access access;
    QuarterlyPreviewController(Access access) { this.access = access; }

    @PostMapping
    ResponseEntity<JsonNode> generate(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                      @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode input) {
        AuditDocuments.fields(input, "quarter", "clinics");
        String quarter = AuditDocuments.text(input, "quarter");
        if (quarter.length() > 16 || !quarter.equals(quarter.strip())) throw new IllegalArgumentException("INVALID_QUARTER");
        var requested = input.path("clinics");
        if (!requested.isArray() || requested.isEmpty()) throw new IllegalArgumentException("CLINIC_SCOPE_REQUIRED");
        var clinics = new HashSet<Long>();
        for (var item : requested) {
            if (!item.isString()) throw new IllegalArgumentException("INVALID_CLINIC_ID");
            long value = Long.parseLong(item.asText());
            if (value <= 0 || !Long.toString(value).equals(item.asText()) || !clinics.add(value)) {
                throw new IllegalArgumentException("INVALID_CLINIC_ID");
            }
        }
        var receipt = access.open(request, csrf).generate(new QuarterlyPreviewService.Target(quarter, clinics,"true".equals(request.getParameter("expenseReview"))), UUID.fromString(key));
        var result = receipt.result();
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null && result != null
                && quarter.equals(result.path("quarter").asText()) && result.path("statisticId").asLong() > 0) {
            var body = JSON.createObjectNode().put("requestReference", receipt.requestReference());
            body.set("preview", result);
            return response(200, body);
        }
        if ("REJECTED".equals(receipt.businessOutcome())) return error(409, "QUARTER_PREVIEW_REJECTED", receipt.requestReference());
        return error(503, "QUARTER_PREVIEW_UNCONFIRMED", receipt.requestReference());
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> denied(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "QUARTER_PREVIEW_ACCESS_DENIED", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_QUARTER_PREVIEW", null); }

    private static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code);
        if (reference != null) body.put("requestReference", reference);
        return response(status, body);
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
