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
@ConditionalOnProperty(name = "adc.uc11.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/visits/{visitId}/invoice-question")
final class InvoiceQuestionController {
    record Scope(long clinic, InvoiceQuestionService service) {}
    interface Access { Scope open(HttpServletRequest request, String csrf); }

    record StaffAccess(JdbcTransactions tx, StaffBindingAdapter bindings, ToLongFunction<HttpServletRequest> clinic,
                       VisitHealthService.DentistIdentity dentists, TreatmentInvoiceService.Prices prices,
                       FormalBookingService.AppointmentIds itemIds, EventIdAllocator auditIds, Clock clock) implements Access {
        @Override public Scope open(HttpServletRequest request, String csrf) {
            var identity = new StaffSessionIdentityAdapter(() -> {
                var session = request.getSession(false);
                var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
            });
            identity.requireCsrf(csrf);
            return new Scope(clinic.applyAsLong(request), new InvoiceQuestionService(tx,
                    new StaffAuthorization(identity, bindings::bindings), dentists, prices, itemIds, auditIds, clock));
        }
    }

    private final Access access;
    InvoiceQuestionController(Access access) { this.access = access; }

    @GetMapping
    ResponseEntity<JsonNode> read(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                  @PathVariable String visitId, @RequestParam String patientId, @RequestParam String invoiceId,
                                  @RequestParam int invoiceVersionNo, @RequestParam boolean clinical) {
        var scope = access.open(request, csrf);
        var details = scope.service().read(target(scope, visitId, patientId, invoiceId, invoiceVersionNo), clinical);
        var body = JSON.createObjectNode().put("question", details.question());
        body.set("handling", details.handling());
        body.set("invoice", details.invoice());
        return response(200, body);
    }

    @PostMapping
    ResponseEntity<JsonNode> write(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                   @PathVariable String visitId, @RequestHeader("Idempotency-Key") String key,
                                   @RequestBody JsonNode input) {
        var operation = InvoiceQuestionService.Operation.valueOf(AuditDocuments.text(input, "operation"));
        String[] basic = {"patientId", "invoiceId", "invoiceVersionNo", "operation", "note", "clinicalQuestion", "questionReference"};
        var fields = new java.util.ArrayList<>(java.util.List.of(basic));
        if (operation == InvoiceQuestionService.Operation.INVOICE_CLINICAL_CORRECT) {
            fields.add(input.has("correction") ? "correction" : "corrections");
        }
        if (operation == InvoiceQuestionService.Operation.INVOICE_APPLICANT_CONFIRM) fields.add("applicant");
        AuditDocuments.fields(input, fields.toArray(String[]::new));
        var scope = access.open(request, csrf);
        if (!input.path("invoiceVersionNo").isIntegralNumber() || !input.path("invoiceVersionNo").canConvertToInt()
                || !input.path("clinicalQuestion").isBoolean()) throw new IllegalArgumentException("INVALID_INVOICE_REVIEW_INPUT");
        var target = target(scope, visitId, AuditDocuments.text(input, "patientId"), AuditDocuments.text(input, "invoiceId"),
                input.path("invoiceVersionNo").asInt());
        var receipt = scope.service().execute(target, UUID.fromString(key), operation, AuditDocuments.text(input, "note"),
                input.path("clinicalQuestion").asBoolean(), input.path("questionReference").isNull()
                        ? null : AuditDocuments.text(input, "questionReference"), corrections(input), applicant(input.get("applicant")));
        var result = receipt.result();
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null && result != null
                && result.path("invoice").path("invoiceId").asLong() == target.invoice()
                && result.path("invoice").path("visitId").asLong() == target.visit()) {
            var body = JSON.createObjectNode().put("requestReference", receipt.requestReference());
            body.set("review", result);
            return response(200, body);
        }
        if ("REJECTED".equals(receipt.businessOutcome())) return error(409, "INVOICE_REVIEW_REJECTED", receipt.requestReference());
        return error(503, "INVOICE_REVIEW_UNCONFIRMED", receipt.requestReference());
    }

    private static java.util.List<InvoiceQuestionService.Correction> corrections(JsonNode input) {
        if (input.has("correction")) return java.util.List.of(correction(input.get("correction")));
        if (!input.has("corrections")) return java.util.List.of();
        var batch = input.get("corrections");
        if (!batch.isArray() || batch.isEmpty()) throw new IllegalArgumentException("INVALID_CORRECTION_BATCH");
        var result = new java.util.ArrayList<InvoiceQuestionService.Correction>();
        for (var item : batch) result.add(correction(item));
        return java.util.List.copyOf(result);
    }

    private static InvoiceQuestionService.Correction correction(JsonNode node) {
        if (node == null) return null;
        var controlled=node.deepCopy();if(controlled instanceof tools.jackson.databind.node.ObjectNode object)object.remove("projectKey");
        AuditDocuments.fields(controlled, "record", "consent", "project", "unit", "quantity");
        String quantity = AuditDocuments.text(node, "quantity");
        if (!quantity.matches("[0-9]{1,15}(\\.[0-9]{1,3})?")) throw new IllegalArgumentException("INVALID_QUANTITY");
        return new InvoiceQuestionService.Correction(id(AuditDocuments.text(node, "record")),
                id(AuditDocuments.text(node, "consent")), AuditDocuments.text(node, "project"),
                AuditDocuments.text(node, "unit"), new java.math.BigDecimal(quantity),node.has("projectKey")?AuditDocuments.text(node,"projectKey"):null);
    }

    private static InvoiceQuestionService.Applicant applicant(JsonNode node) {
        if (node == null) return null;
        AuditDocuments.fields(node, "identity", "acceptedAmount");
        return new InvoiceQuestionService.Applicant(AuditDocuments.text(node, "identity"), AuditDocuments.text(node, "acceptedAmount"));
    }

    private static InvoiceQuestionService.Target target(Scope scope, String visit, String patient, String invoice, int version) {
        if (version < 1) throw new IllegalArgumentException("INVALID_INVOICE_VERSION");
        return new InvoiceQuestionService.Target(scope.clinic(), id(patient), id(visit), id(invoice), version);
    }

    private static long id(String text) {
        long id = Long.parseLong(text);
        if (id <= 0 || !Long.toString(id).equals(text)) throw new IllegalArgumentException("INVALID_INVOICE_ID");
        return id;
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> denied(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "INVOICE_REVIEW_ACCESS_DENIED", null);
    }

    @ExceptionHandler(cn.adc.shared.audit.CommandRecovery.Rejection.class)
    ResponseEntity<JsonNode> stale() { return error(409, "INVOICE_REVIEW_REJECTED", null); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_INVOICE_REVIEW_INPUT", null); }

    @ExceptionHandler(JdbcTransactions.Failure.class)
    ResponseEntity<JsonNode> unavailable() { return error(503, "INVOICE_REVIEW_UNAVAILABLE", null); }

    private static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code);
        if (reference != null) body.put("requestReference", reference);
        return response(status, body);
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
