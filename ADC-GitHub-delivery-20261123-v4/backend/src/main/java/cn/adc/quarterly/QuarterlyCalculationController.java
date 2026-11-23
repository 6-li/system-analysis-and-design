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
@ConditionalOnProperty(name = "adc.uc19.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/quarterly/calculations")
final class QuarterlyCalculationController {
    interface Access { QuarterlyCalculationService open(HttpServletRequest request, String csrf); }
    record StaffAccess(JdbcTransactions tx, StaffBindingAdapter bindings, QuarterlyCalculationService.Policies policies,
                       EventIdAllocator calculationIds, EventIdAllocator auditIds, Clock clock) implements Access {
        @Override public QuarterlyCalculationService open(HttpServletRequest req, String csrf) {
            var identity = new StaffSessionIdentityAdapter(() -> {
                var session = req.getSession(false);
                var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
            });
            identity.requireCsrf(csrf);
            return new QuarterlyCalculationService(tx, new StaffAuthorization(identity, bindings::bindings), policies,
                    calculationIds, auditIds, clock);
        }
    }

    private final Access access;
    QuarterlyCalculationController(Access access) { this.access = access; }

    @PostMapping("/{calculationId}/reviewed-report")
    ResponseEntity<JsonNode> report(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                   @RequestHeader("Idempotency-Key") String key, @PathVariable String calculationId, @RequestParam int version,
                                   @RequestParam String clinics) {
        if (version < 1) throw new IllegalArgumentException("INVALID_CALCULATION_VERSION");
        var receipt = access.open(request, csrf).export(id(calculationId), version, scope(clinics), UUID.fromString(key));
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null && receipt.result() != null) {
            var body = JSON.createObjectNode().put("requestReference", receipt.requestReference());
            body.set("report", receipt.result());
            return response(200, body);
        }
        return "REJECTED".equals(receipt.businessOutcome())
                ? error(409, "QUARTER_CALCULATION_REJECTED", receipt.requestReference())
                : error(503, "QUARTER_CALCULATION_UNCONFIRMED", receipt.requestReference());
    }

    @PostMapping
    ResponseEntity<JsonNode> calculate(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                      @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode input) {
        AuditDocuments.fields(input, "statisticId", "clinics", "calculationId", "expectedVersion", "pendingNotes");
        long statistic = id(AuditDocuments.text(input, "statisticId"));
        var calculation = nullable(input, "calculationId");
        var version = input.path("expectedVersion");
        if (!version.isNull() && (!version.isIntegralNumber() || !version.canConvertToInt() || version.asInt() < 1)) {
            throw new IllegalArgumentException("INVALID_CALCULATION_VERSION");
        }
        var target = new QuarterlyCalculationService.Target(statistic, calculation == null ? null : id(calculation),
                version.isNull() ? null : version.asInt(), scope(AuditDocuments.text(input, "clinics")));
        var receipt = access.open(request, csrf).calculate(target, UUID.fromString(key), nullable(input, "pendingNotes"));
        var result = receipt.result();
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null && result != null
                && statistic == result.path("statisticId").asLong() && result.path("calculationId").asLong() > 0
                && result.path("version").asInt() > 0) {
            var body = JSON.createObjectNode().put("requestReference", receipt.requestReference());
            body.set("calculation", result);
            return response(200, body);
        }
        if ("REJECTED".equals(receipt.businessOutcome())) return error(409, "QUARTER_CALCULATION_REJECTED", receipt.requestReference());
        return error(503, "QUARTER_CALCULATION_UNCONFIRMED", receipt.requestReference());
    }

    static java.util.Set<Long> scope(String clinics) {
        var ids = new HashSet<Long>();
        for (var text : clinics.split(",", -1)) {
            if (!ids.add(id(text))) throw new IllegalArgumentException("DUPLICATE_CLINIC");
        }
        return ids;
    }

    static long id(String text) {
        long value = Long.parseLong(text);
        if (value <= 0 || !Long.toString(value).equals(text)) throw new IllegalArgumentException("INVALID_CALCULATION_ID");
        return value;
    }

    private static String nullable(JsonNode input, String field) {
        return input.path(field).isNull() ? null : AuditDocuments.text(input, field);
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> denied(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "QUARTER_CALCULATION_ACCESS_DENIED", null);
    }

    @ExceptionHandler(cn.adc.shared.audit.CommandRecovery.Rejection.class)
    ResponseEntity<JsonNode> issueRejected() { return error(409, "QUARTER_CALCULATION_REJECTED", null); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_QUARTER_CALCULATION", null); }

    @ExceptionHandler(JdbcTransactions.Failure.class)
    ResponseEntity<JsonNode> unavailable() { return error(503, "QUARTER_CALCULATION_UNAVAILABLE", null); }

    static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code);
        if (reference != null) body.put("requestReference", reference);
        return response(status, body);
    }

    static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
