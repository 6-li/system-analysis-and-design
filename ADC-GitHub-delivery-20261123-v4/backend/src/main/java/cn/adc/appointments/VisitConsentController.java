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
@ConditionalOnProperty(name = "adc.uc08.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/visits/{visitId}/consent")
final class VisitConsentController {
    record Scope(long clinic, VisitConsentService service) {}
    interface Access { Scope open(HttpServletRequest request, String csrf); }

    private final Access access;

    VisitConsentController(Access access) { this.access = access; }

    @GetMapping
    ResponseEntity<JsonNode> read(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                  @PathVariable String visitId, @RequestParam String patientId) {
        var scope = access.open(request, csrf);
        var body = JSON.createObjectNode().put("visitId", visitId).put("patientId", patientId);
        var entries = body.putArray("entries");
        for (var row : scope.service().read(context(scope, visitId, patientId))) {
            var object=entries.addObject().put("consentResultId", Long.toString(row.id())).put("communicationParty", row.party())
                    .put("guardianParticipation", row.guardianParticipation()).put("proposedTreatment", row.treatment())
                    .put("consentResult", row.outcome()).put("comments", row.comments()).put("recordedBy", row.actor())
                    .put("recordedAt", row.recordedAt().toInstant().toString()).put("dentistId", Long.toString(row.dentist()));
            if(!row.projectScopes().isEmpty())object.set("projectScopes",ConsentProjectScopes.document(row.projectScopes()));
        }
        return response(200, body);
    }

    @PostMapping
    ResponseEntity<JsonNode> save(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                  @PathVariable String visitId, @RequestHeader("Idempotency-Key") String key,
                                  @RequestBody JsonNode input) {
        var controlled=input.deepCopy();if(controlled instanceof tools.jackson.databind.node.ObjectNode object){object.remove("projectKeys");object.remove("catalogVersion");}
        AuditDocuments.fields(controlled,"patientId","communicationParty","guardianRequired","guardianParticipation","proposedTreatment","consentResult","comments");
        var scope = access.open(request, csrf);
        var context = context(scope, visitId, AuditDocuments.text(input, "patientId"));
        if (!input.path("guardianRequired").isBoolean()) throw new IllegalArgumentException("GUARDIAN_REQUIRED_FLAG");
        var receipt = scope.service().save(context, UUID.fromString(key), new VisitConsentService.Input(
                AuditDocuments.text(input, "communicationParty"), input.path("guardianRequired").asBoolean(),
                nullableText(input, "guardianParticipation"), AuditDocuments.text(input, "proposedTreatment"),
                AuditDocuments.text(input, "consentResult"), nullableText(input, "comments"),projectKeys(input),input.has("catalogVersion")?AuditDocuments.text(input,"catalogVersion"):null));
        var result = receipt.result();
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null && result != null
                && result.path("visitId").asLong() == context.visit() && result.path("consentResultId").asLong() > 0) {
            return response(200, JSON.createObjectNode().put("consentResultId", result.path("consentResultId").asText())
                    .put("visitId", visitId).put("requestReference", receipt.requestReference()));
        }
        if ("REJECTED".equals(receipt.businessOutcome())) return error(409, "CONSENT_REJECTED", receipt.requestReference());
        return error(503, "CONSENT_RESULT_UNCONFIRMED", receipt.requestReference());
    }

    private static VisitConsentService.Context context(Scope scope, String visit, String patient) {
        return new VisitConsentService.Context(scope.clinic(), id(patient), id(visit));
    }

    private static long id(String text) {
        long id = Long.parseLong(text);
        if (id <= 0 || !Long.toString(id).equals(text)) throw new IllegalArgumentException("INVALID_CONSENT_ID");
        return id;
    }

    private static java.util.List<String> projectKeys(JsonNode input){
        if(!input.has("projectKeys"))return null;
        if(!input.path("projectKeys").isArray())throw new IllegalArgumentException("INVALID_PROJECT_KEYS");
        var keys=new java.util.ArrayList<String>();for(var item:input.path("projectKeys")){if(!item.isString())throw new IllegalArgumentException("INVALID_PROJECT_KEY");keys.add(item.asText());}return keys;
    }
    private static String nullableText(JsonNode input, String key) {
        return input.path(key).isNull() ? null : AuditDocuments.text(input, key);
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> forbidden(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "CONSENT_ACCESS_DENIED", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_CONSENT_INPUT", null); }

    @ExceptionHandler(JdbcTransactions.Failure.class)
    ResponseEntity<JsonNode> unavailable() { return error(503, "CONSENT_DEPENDENCY_UNAVAILABLE", null); }

    private static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code);
        if (reference != null) body.put("requestReference", reference);
        return response(status, body);
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
