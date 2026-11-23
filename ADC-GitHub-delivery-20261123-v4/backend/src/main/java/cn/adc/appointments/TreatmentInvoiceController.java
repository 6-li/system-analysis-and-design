package cn.adc.appointments;

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
@ConditionalOnProperty(name = "adc.uc10.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/visits/{visitId}/invoice")
final class TreatmentInvoiceController {
    record Scope(long clinic, TreatmentInvoiceService service) {}
    interface Access { Scope open(HttpServletRequest request, String csrf); }

    record StaffAccess(JdbcTransactions tx, StaffBindingAdapter bindings, ToLongFunction<HttpServletRequest> clinic,
                       TreatmentInvoiceService.Prices prices, FormalBookingService.AppointmentIds invoiceIds,
                       FormalBookingService.AppointmentIds itemIds, EventIdAllocator auditIds,
                       Clock clock) implements Access {
        @Override
        public Scope open(HttpServletRequest request, String csrf) {
            var identity = new StaffSessionIdentityAdapter(() -> {
                var session = request.getSession(false);
                var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
            });
            identity.requireCsrf(csrf);
            return new Scope(clinic.applyAsLong(request), new TreatmentInvoiceService(tx,
                    new StaffAuthorization(identity, bindings::bindings), prices, invoiceIds, itemIds, auditIds, clock));
        }
    }

    private final Access access;
    TreatmentInvoiceController(Access access) { this.access = access; }

    @PostMapping
    ResponseEntity<JsonNode> generate(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                      @RequestHeader("Idempotency-Key") String key, @PathVariable String visitId) {
        var scope = access.open(request, csrf);
        long id = Long.parseLong(visitId);
        if (id <= 0 || !Long.toString(id).equals(visitId)) throw new IllegalArgumentException("INVALID_VISIT_ID");
        var receipt = scope.service().generate(new TreatmentInvoiceService.Target(scope.clinic(), id), UUID.fromString(key));
        var body = receipt.result();
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null
                && body != null && body.path("visitId").asText().equals(visitId) && body.path("invoiceId").asLong() > 0
                && body.path("invoiceVersionNo").asInt() > 0 && body.path("lines").isArray() && !body.path("lines").isEmpty()) {
            var result = JSON.createObjectNode().put("requestReference", receipt.requestReference());
            result.set("invoice", body);
            return response(200, result);
        }
        if ("REJECTED".equals(receipt.businessOutcome())) return error(409, "INVOICE_REVIEW_REQUIRED", receipt.requestReference());
        return error(503, "INVOICE_RESULT_UNCONFIRMED", receipt.requestReference());
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> forbidden(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "INVOICE_ACCESS_DENIED", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_INVOICE_REQUEST", null); }

    private static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code);
        if (reference != null) body.put("requestReference", reference);
        return response(status, body);
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
