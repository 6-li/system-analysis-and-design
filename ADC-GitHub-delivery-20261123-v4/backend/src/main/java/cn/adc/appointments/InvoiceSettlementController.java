package cn.adc.appointments;

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
import java.util.UUID;
import java.util.function.ToLongFunction;

import static cn.adc.shared.audit.AuditDocuments.JSON;

@RestController
@ConditionalOnProperty(name = "adc.uc12.demo.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/visits/{visitId}/demo-settlement")
final class InvoiceSettlementController {
    record Scope(long clinic, InvoiceSettlementService service) {}
    interface Access { Scope open(HttpServletRequest request, String csrf); }

    record StaffAccess(JdbcTransactions tx, StaffBindingAdapter bindings, ToLongFunction<HttpServletRequest> clinic,
                       FormalBookingService.AppointmentIds ids, EventIdAllocator auditIds, Clock clock) implements Access {
        @Override public Scope open(HttpServletRequest request, String csrf) {
            var identity = new StaffSessionIdentityAdapter(() -> {
                var session = request.getSession(false);
                var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
            });
            identity.requireCsrf(csrf);
            var actor = identity.current().orElseThrow(() -> new SecurityException("AUTHENTICATION_REQUIRED"));
            if (!actor.startsWith("SYNTHETIC")) throw new SecurityException("DEMO_IDENTITY_REQUIRED");
            return new Scope(clinic.applyAsLong(request), new InvoiceSettlementService(tx,
                    new StaffAuthorization(identity, bindings::bindings), ids, auditIds, clock));
        }
    }

    private final Access access;
    InvoiceSettlementController(Access access) { this.access = access; }

    @PostMapping
    ResponseEntity<JsonNode> settle(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                    @RequestHeader("Idempotency-Key") String key, @PathVariable String visitId,
                                    @RequestBody JsonNode input) {
        AuditDocuments.fields(input, "invoiceId", "invoiceVersionNo", "operation", "paymentId", "confirmation");
        var scope = access.open(request, csrf);
        long invoice = id(AuditDocuments.text(input, "invoiceId"));
        var version = input.path("invoiceVersionNo");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.asInt() < 1) {
            throw new IllegalArgumentException("INVALID_INVOICE_VERSION");
        }
        var target = new InvoiceSettlementService.Target(scope.clinic(), id(visitId), invoice, version.asInt());
        String operation = AuditDocuments.text(input, "operation");
        var payment = input.path("paymentId").isNull() ? null : id(AuditDocuments.text(input, "paymentId"));
        InvoiceSettlementService.Confirmation confirmation = null;
        if (!input.path("confirmation").isNull()) {
            var fields = input.get("confirmation");
            AuditDocuments.fields(fields, "applicant", "amount", "basis");
            confirmation = new InvoiceSettlementService.Confirmation(AuditDocuments.text(fields, "applicant"),
                    AuditDocuments.text(fields, "amount"), AuditDocuments.text(fields, "basis"));
        }
        var receipt = scope.service().execute(target, operation, UUID.fromString(key), payment, confirmation);
        var result = receipt.result();
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null && result != null
                && result.path("invoiceId").asLong() == invoice && result.path("visitId").asLong() == target.visit()
                && result.path("invoiceVersionNo").asInt() == target.version()
                && result.path("synthetic").asBoolean() && result.path("paymentId").asLong() > 0) {
            var body = JSON.createObjectNode().put("requestReference", receipt.requestReference());
            body.set("payment", result);
            return response(200, body);
        }
        if ("REJECTED".equals(receipt.businessOutcome())) return error(409, "SETTLEMENT_REVIEW_REQUIRED", receipt.requestReference());
        return error(503, "SETTLEMENT_RESULT_UNCONFIRMED", receipt.requestReference());
    }

    private static long id(String text) {
        long number = Long.parseLong(text);
        if (number < 1 || !Long.toString(number).equals(text)) throw new IllegalArgumentException("INVALID_SETTLEMENT_ID");
        return number;
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> forbidden(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "SETTLEMENT_ACCESS_DENIED", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_SETTLEMENT_REQUEST", null); }

    private static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code);
        if (reference != null) body.put("requestReference", reference);
        return response(status, body);
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
