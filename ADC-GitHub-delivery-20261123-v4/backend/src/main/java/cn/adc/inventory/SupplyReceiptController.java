package cn.adc.inventory;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore.EventIdAllocator;
import cn.adc.shared.audit.CommandRecovery;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

@RestController
@ConditionalOnProperty(name = "adc.uc15.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/clinics/{clinicId}/purchase-requirements/{requirementId}/receipt")
final class SupplyReceiptController {
    interface Access { SupplyReceiptService open(HttpServletRequest request, String csrf); }
    record StaffAccess(JdbcTransactions tx, StaffBindingAdapter bindings,
                       SupplyReceiptService.Deliveries deliveries, EventIdAllocator auditIds,
                       Clock clock) implements Access {
        @Override public SupplyReceiptService open(HttpServletRequest request, String csrf) {
            var identity = new StaffSessionIdentityAdapter(() -> {
                var session = request.getSession(false);
                var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
            });
            identity.requireCsrf(csrf);
            return new SupplyReceiptService(tx, new StaffAuthorization(identity, bindings::bindings),
                    deliveries, auditIds, clock);
        }
    }

    private final Access access;
    SupplyReceiptController(Access access) { this.access = access; }

    @PostMapping
    ResponseEntity<JsonNode> save(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                  @RequestHeader("Idempotency-Key") String key, @PathVariable String clinicId,
                                  @PathVariable String requirementId, @RequestBody JsonNode input) {
        AuditDocuments.fields(input, "deliveryLookup", "accepted", "result", "discrepancy", "received", "cost", "expiry");
        if (!input.path("accepted").isBoolean()) throw new IllegalArgumentException("INVALID_ACCEPTANCE");
        String day = optional(input, "expiry");
        java.time.LocalDate expiry = null;
        if (day != null) {
            try { expiry = java.time.LocalDate.parse(day); }
            catch (java.time.DateTimeException e) { throw new IllegalArgumentException("INVALID_RECEIPT_EXPIRY"); }
            if (!day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || expiry.getYear() < 1) {
                throw new IllegalArgumentException("INVALID_RECEIPT_EXPIRY");
            }
        }
        var model = new SupplyReceiptService.Input(AuditDocuments.text(input, "deliveryLookup"),
                input.path("accepted").asBoolean(), AuditDocuments.text(input, "result"), optional(input, "discrepancy"),
                decimal(input, "received", 15, 3), decimal(input, "cost", 16, 2), expiry);
        var outcome = access.open(request, csrf).save(new SupplyReceiptService.Target(id(clinicId), id(requirementId)),
                UUID.fromString(key), model);
        var saved = outcome.result();
        if ("SUCCEEDED".equals(outcome.businessOutcome()) && outcome.successEventId() != null && saved != null
                && clinicId.equals(saved.path("clinicId").asText()) && saved.path("receiptId").asLong() > 0
                && requirementId.equals(saved.path("requirementId").asText())) {
            var body = JSON.createObjectNode().put("requestReference", outcome.requestReference());
            body.set("receipt", saved);
            return response(200, body);
        }
        if ("REJECTED".equals(outcome.businessOutcome())) return error(409, "SUPPLY_RECEIPT_REJECTED", outcome.requestReference());
        return error(503, "SUPPLY_RECEIPT_UNCONFIRMED", outcome.requestReference());
    }

    private static String optional(JsonNode input, String name) {
        return input.path(name).isNull() ? null : AuditDocuments.text(input, name);
    }

    private static BigDecimal decimal(JsonNode input, String name, int whole, int scale) {
        String text = optional(input, name);
        if (text == null) return null;
        if (!text.matches("[0-9]{1," + whole + "}(\\.[0-9]{1," + scale + "})?")) {
            throw new IllegalArgumentException("INVALID_RECEIPT_DECIMAL");
        }
        return new BigDecimal(text);
    }

    private static long id(String text) {
        long value = Long.parseLong(text);
        if (value <= 0 || !Long.toString(value).equals(text)) throw new IllegalArgumentException("INVALID_RECEIPT_ID");
        return value;
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> denied(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "SUPPLY_RECEIPT_ACCESS_DENIED", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_SUPPLY_RECEIPT", null); }

    private static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code);
        if (reference != null) body.put("requestReference", reference);
        return response(status, body);
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
