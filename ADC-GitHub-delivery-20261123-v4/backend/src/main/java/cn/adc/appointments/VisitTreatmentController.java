package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

@RestController
@ConditionalOnProperty(name = "adc.uc09.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/visits/{visitId}/treatment")
final class VisitTreatmentController {
    record Scope(long clinic, VisitTreatmentService service) {}
    interface Access { Scope open(HttpServletRequest request, String csrf); }

    private final Access access;

    VisitTreatmentController(Access access) { this.access = access; }

    @GetMapping
    ResponseEntity<JsonNode> read(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                  @PathVariable String visitId, @RequestParam String patientId) {
        var scope = access.open(request, csrf);
        var body = JSON.createObjectNode().put("visitId", visitId).put("patientId", patientId);
        var consents = body.putArray("consents");
        for (var row : scope.service().read(context(scope, visitId, patientId))) {
            var object=consents.addObject().put("consentResultId", Long.toString(row.id()))
                    .put("proposedTreatment", row.scope()).put("consentResult", row.outcome());
            if(!row.projectScopes().isEmpty())object.set("projectScopes",ConsentProjectScopes.document(row.projectScopes()));
        }
        var records=scope.service().records(context(scope,visitId,patientId));if(records!=null)body.set("records",records);
        return response(200, body);
    }

    @PostMapping
    ResponseEntity<JsonNode> save(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                  @PathVariable String visitId, @RequestHeader("Idempotency-Key") String key,
                                  @RequestBody JsonNode input) {
        var controlled=input.deepCopy();
        if(controlled instanceof tools.jackson.databind.node.ObjectNode object){object.remove("patientFindings");object.remove("aftercareInstructions");object.remove("projectKey");}
        AuditDocuments.fields(controlled,"patientId","consentResultId","performed","project","unit","quantity","specialRequirements","nonPerformanceReason");
        var scope = access.open(request, csrf);
        var context = context(scope, visitId, AuditDocuments.text(input, "patientId"));
        if (!input.path("performed").isBoolean()) throw new IllegalArgumentException("PERFORMED_FLAG_REQUIRED");
        String quantity = nullableText(input, "quantity");
        if (quantity != null && !quantity.matches("[0-9]{1,15}(\\.[0-9]{1,3})?")) {
            throw new IllegalArgumentException("INVALID_TREATMENT_QUANTITY");
        }
        var receipt = scope.service().save(context, UUID.fromString(key), new VisitTreatmentService.Input(
                id(AuditDocuments.text(input, "consentResultId")), input.path("performed").asBoolean(),
                nullableText(input, "project"), nullableText(input, "unit"),
                quantity == null ? null : new java.math.BigDecimal(quantity),
                nullableText(input, "specialRequirements"), nullableText(input, "nonPerformanceReason"),optionalText(input,"patientFindings"),optionalText(input,"aftercareInstructions"),optionalText(input,"projectKey")));
        var result = receipt.result();
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null && result != null
                && result.path("visitId").asLong() == context.visit() && result.path("clinicalRecordId").asLong() > 0
                && result.path("recordVersionNo").asInt() == 1) {
            return response(200, JSON.createObjectNode().put("clinicalRecordId", result.path("clinicalRecordId").asText())
                    .put("recordVersionNo", 1).put("visitId", visitId).put("requestReference", receipt.requestReference()));
        }
        if ("REJECTED".equals(receipt.businessOutcome())) return error(409, "TREATMENT_REJECTED", receipt.requestReference());
        return error(503, "TREATMENT_RESULT_UNCONFIRMED", receipt.requestReference());
    }

    private static VisitTreatmentService.Context context(Scope scope, String visit, String patient) {
        return new VisitTreatmentService.Context(scope.clinic(), id(patient), id(visit));
    }

    private static long id(String text) {
        long id = Long.parseLong(text);
        if (id <= 0 || !Long.toString(id).equals(text)) throw new IllegalArgumentException("INVALID_TREATMENT_ID");
        return id;
    }

    private static String optionalText(JsonNode input,String key){return !input.has(key)||input.path(key).isNull()?null:AuditDocuments.text(input,key);}

    private static String nullableText(JsonNode input, String key) {
        return input.path(key).isNull() ? null : AuditDocuments.text(input, key);
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> forbidden(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "TREATMENT_ACCESS_DENIED", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_TREATMENT_INPUT", null); }

    @ExceptionHandler(JdbcTransactions.Failure.class)
    ResponseEntity<JsonNode> unavailable() { return error(503, "TREATMENT_DEPENDENCY_UNAVAILABLE", null); }

    private static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code);
        if (reference != null) body.put("requestReference", reference);
        return response(status, body);
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}

