package cn.adc.quarterly;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore.EventIdAllocator;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.UUID;

import static cn.adc.quarterly.QuarterlyCalculationController.*;

@RestController
@ConditionalOnProperty(name = "adc.uc20.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/quarterly/financial-reviews")
final class QuarterlyFinancialReviewController {
    interface Access { QuarterlyFinancialReviewService open(HttpServletRequest request, String csrf); }

    record StaffAccess(JdbcTransactions tx, StaffBindingAdapter bindings, EventIdAllocator reviewIds,
                       EventIdAllocator auditIds, Clock clock) implements Access {
        @Override public QuarterlyFinancialReviewService open(HttpServletRequest req, String csrf) {
            var identity = new StaffSessionIdentityAdapter(() -> {
                var session = req.getSession(false);
                var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
            });
            identity.requireCsrf(csrf);
            return new QuarterlyFinancialReviewService(tx, new StaffAuthorization(identity, bindings::bindings), reviewIds, auditIds, clock);
        }
    }

    private final Access access;
    QuarterlyFinancialReviewController(Access access) { this.access = access; }

    @GetMapping("/{calculationId}")
    ResponseEntity<JsonNode> read(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                  @PathVariable String calculationId, @RequestParam int version, @RequestParam String clinics) {
        if (version < 1) throw new IllegalArgumentException("INVALID_CALCULATION_VERSION");
        return response(200, access.open(request, csrf).read(id(calculationId), version, scope(clinics)));
    }

    @PostMapping("/{calculationId}")
    ResponseEntity<JsonNode> approve(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                     @RequestHeader("Idempotency-Key") String key, @PathVariable String calculationId,
                                     @RequestParam int version, @RequestParam String clinics, @RequestBody JsonNode input) {
        if (version < 1) throw new IllegalArgumentException("INVALID_CALCULATION_VERSION");
        AuditDocuments.fields(input, "basisHash", "notes");
        var receipt = access.open(request, csrf).approve(id(calculationId), version, scope(clinics),
                AuditDocuments.text(input, "basisHash"), AuditDocuments.text(input, "notes"), UUID.fromString(key));
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null && receipt.result() != null) {
            var body = AuditDocuments.JSON.createObjectNode().put("requestReference", receipt.requestReference());
            body.set("review", receipt.result());
            return response(200, body);
        }
        return "REJECTED".equals(receipt.businessOutcome()) ? error(409, "FINANCIAL_REVIEW_REJECTED", receipt.requestReference())
                : error(503, "FINANCIAL_REVIEW_UNCONFIRMED", receipt.requestReference());
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> denied(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "FINANCIAL_REVIEW_ACCESS_DENIED", null);
    }
    @ExceptionHandler(CommandRecovery.Rejection.class)
    ResponseEntity<JsonNode> rejected() { return error(409, "FINANCIAL_REVIEW_REJECTED", null); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_FINANCIAL_REVIEW", null); }
    @ExceptionHandler(JdbcTransactions.Failure.class)
    ResponseEntity<JsonNode> unavailable() { return error(503, "FINANCIAL_REVIEW_UNAVAILABLE", null); }
}
