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
@ConditionalOnProperty(name = "adc.uc16.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/clinics/{clinicId}/supply-consumption")
final class SupplyConsumptionController {
    interface Access { SupplyConsumptionService open(HttpServletRequest request, String csrf); }
    record StaffAccess(JdbcTransactions tx, StaffBindingAdapter bindings,
                       SupplyConsumptionService.CostFacts facts, EventIdAllocator auditIds,
                       Clock clock) implements Access {
        @Override public SupplyConsumptionService open(HttpServletRequest request, String csrf) {
            var identity = new StaffSessionIdentityAdapter(() -> {
                var session = request.getSession(false);
                var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
            });
            identity.requireCsrf(csrf);
            return new SupplyConsumptionService(tx, new StaffAuthorization(identity, bindings::bindings),
                    facts, auditIds, clock);
        }
    }

    private final Access access;
    SupplyConsumptionController(Access access) { this.access = access; }

    @PostMapping
    ResponseEntity<JsonNode> save(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                  @RequestHeader("Idempotency-Key") String key, @PathVariable String clinicId,
                                  @RequestBody JsonNode input) {
        AuditDocuments.fields(input, "supplyId", "quantity", "occurredAt", "proofReference");
        var target = new SupplyConsumptionService.Target(id(clinicId), id(AuditDocuments.text(input, "supplyId")));
        String quantity = AuditDocuments.text(input, "quantity");
        if (!quantity.matches("[0-9]{1,15}(\\.[0-9]{1,3})?")) throw new IllegalArgumentException("INVALID_CONSUMPTION_QUANTITY");
        var model = new SupplyConsumptionService.Input(AuditDocuments.text(input, "proofReference"), new BigDecimal(quantity),
                cn.adc.shared.time.StrictInputPolicy.instant(AuditDocuments.text(input, "occurredAt")));
        var outcome = access.open(request, csrf).save(target, UUID.fromString(key), model);
        var saved = outcome.result();
        if ("SUCCEEDED".equals(outcome.businessOutcome()) && outcome.successEventId() != null && saved != null
                && clinicId.equals(saved.path("clinicId").asText()) && saved.path("consumptionId").asLong() > 0
                && saved.path("supplyId").asLong() == target.supply()) {
            var body = JSON.createObjectNode().put("requestReference", outcome.requestReference());
            body.set("consumption", saved);
            return response(200, body);
        }
        if ("REJECTED".equals(outcome.businessOutcome())) {
            String code = "CONSUMPTION_COST_REVIEW_REQUIRED".equals(outcome.reason()) ? outcome.reason() : "CONSUMPTION_REJECTED";
            return error(409, code, outcome.requestReference());
        }
        return error(503, "CONSUMPTION_UNCONFIRMED", outcome.requestReference());
    }

    private static long id(String text) {
        long value = Long.parseLong(text);
        if (value <= 0 || !Long.toString(value).equals(text)) throw new IllegalArgumentException("INVALID_CONSUMPTION_ID");
        return value;
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> denied(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "CONSUMPTION_ACCESS_DENIED", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_CONSUMPTION_REQUEST", null); }

    private static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code);
        if (reference != null) body.put("requestReference", reference);
        return response(status, body);
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
