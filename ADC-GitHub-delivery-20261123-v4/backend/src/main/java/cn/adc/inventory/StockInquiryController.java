package cn.adc.inventory;

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

import static cn.adc.shared.audit.AuditDocuments.JSON;

@RestController
@ConditionalOnProperty(name = "adc.uc13.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/clinics/{clinicId}/stock-inquiry")
final class StockInquiryController {
    interface Access { StockInquiryService open(HttpServletRequest request, String csrf); }
    record StaffAccess(JdbcTransactions tx, StaffBindingAdapter bindings,
                       EventIdAllocator auditIds, Clock clock) implements Access {
        @Override public StockInquiryService open(HttpServletRequest request, String csrf) {
            var identity = new StaffSessionIdentityAdapter(() -> {
                var session = request.getSession(false);
                var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
            });
            identity.requireCsrf(csrf);
            return new StockInquiryService(tx, new StaffAuthorization(identity, bindings::bindings), auditIds, clock);
        }
    }

    private final Access access;
    StockInquiryController(Access access) { this.access = access; }

    @PostMapping
    ResponseEntity<JsonNode> query(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                   @RequestHeader("Idempotency-Key") String key, @PathVariable String clinicId,
                                   @RequestBody JsonNode input) {
        AuditDocuments.fields(input, "supplyId");
        long clinic = id(clinicId);
        Long supply = input.path("supplyId").isNull() ? null : id(AuditDocuments.text(input, "supplyId"));
        var receipt = access.open(request, csrf).query(clinic, supply, UUID.fromString(key));
        var result = receipt.result();
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null && result != null
                && clinicId.equals(result.path("clinicId").asText()) && result.path("items").isArray()) {
            var body = JSON.createObjectNode().put("requestReference", receipt.requestReference());
            body.set("stock", result);
            return response(200, body);
        }
        if ("REJECTED".equals(receipt.businessOutcome())) return error(403, "STOCK_INQUIRY_DENIED");
        return error(503, "STOCK_INQUIRY_UNAVAILABLE");
    }

    private static long id(String text) {
        long number = Long.parseLong(text);
        if (number < 1 || !Long.toString(number).equals(text)) throw new IllegalArgumentException("INVALID_STOCK_ID");
        return number;
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> denied(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "STOCK_INQUIRY_DENIED");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_STOCK_INQUIRY"); }

    private static ResponseEntity<JsonNode> error(int status, String code) {
        return response(status, JSON.createObjectNode().put("code", code));
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
