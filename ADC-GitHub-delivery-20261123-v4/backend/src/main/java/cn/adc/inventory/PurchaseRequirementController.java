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
@ConditionalOnProperty(name = "adc.uc14.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/clinics/{clinicId}/purchase-requirements")
final class PurchaseRequirementController {
    interface Access { PurchaseRequirementService open(HttpServletRequest request, String csrf); }
    record StaffAccess(JdbcTransactions tx, StaffBindingAdapter bindings, EventIdAllocator ids,
                       PurchaseRequirementService.Confirmations confirmations, EventIdAllocator auditIds,
                       Clock clock) implements Access {
        @Override public PurchaseRequirementService open(HttpServletRequest request, String csrf) {
            var identity = new StaffSessionIdentityAdapter(() -> {
                var session = request.getSession(false);
                var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
            });
            identity.requireCsrf(csrf);
            return new PurchaseRequirementService(tx, new StaffAuthorization(identity, bindings::bindings),
                    ids, confirmations, auditIds, clock);
        }
    }

    private final Access access;
    PurchaseRequirementController(Access access) { this.access = access; }

    @PostMapping
    ResponseEntity<JsonNode> register(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                      @RequestHeader("Idempotency-Key") String key, @PathVariable String clinicId,
                                      @RequestBody JsonNode input) {
        AuditDocuments.fields(input, "supplyId", "quantity", "confirmationReference");
        String amount = AuditDocuments.text(input, "quantity");
        if (!amount.matches("[0-9]{1,15}(\\.[0-9]{1,3})?")) throw new IllegalArgumentException("INVALID_PURCHASE_QUANTITY");
        var model = new PurchaseRequirementService.Input(id(AuditDocuments.text(input, "supplyId")), new BigDecimal(amount),
                AuditDocuments.text(input, "confirmationReference"));
        var receipt = access.open(request, csrf).register(id(clinicId), UUID.fromString(key), model);
        return outcome(receipt, clinicId, null);
    }

    @PostMapping("/{requirementId}/unavailable")
    ResponseEntity<JsonNode> unavailable(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                         @RequestHeader("Idempotency-Key") String key, @PathVariable String clinicId,
                                         @PathVariable String requirementId, @RequestBody JsonNode input) {
        AuditDocuments.fields(input, "explanation");
        var receipt = access.open(request, csrf).unavailable(id(clinicId), UUID.fromString(key), id(requirementId),
                AuditDocuments.text(input, "explanation"));
        return outcome(receipt, clinicId, requirementId);
    }

    private static ResponseEntity<JsonNode> outcome(CommandRecovery.Receipt receipt, String clinic, String id) {
        var saved = receipt.result();
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null && saved != null
                && clinic.equals(saved.path("clinicId").asText()) && saved.path("requirementId").asLong() > 0
                && (id == null || id.equals(saved.path("requirementId").asText()))) {
            var body = JSON.createObjectNode().put("requestReference", receipt.requestReference());
            body.set("requirement", saved);
            return response(200, body);
        }
        if ("REJECTED".equals(receipt.businessOutcome())) return error(409, "PURCHASE_REQUIREMENT_REJECTED", receipt.requestReference());
        return error(503, "PURCHASE_RESULT_UNCONFIRMED", receipt.requestReference());
    }

    private static long id(String text) {
        long value = Long.parseLong(text);
        if (value <= 0 || !Long.toString(value).equals(text)) throw new IllegalArgumentException("INVALID_PURCHASE_ID");
        return value;
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> denied(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "PURCHASE_ACCESS_DENIED", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_PURCHASE_REQUEST", null); }

    private static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code);
        if (reference != null) body.put("requestReference", reference);
        return response(status, body);
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
