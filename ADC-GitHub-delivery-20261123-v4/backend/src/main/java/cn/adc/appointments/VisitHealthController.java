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

/** UC07 staff-only route. Its session and clinic wiring is supplied by the environment. */
@RestController
@ConditionalOnProperty(name = "adc.uc07.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff/visits/{visitId}/health")
final class VisitHealthController {
    record Scope(long clinic, VisitHealthService service) {}
    interface Access { Scope open(HttpServletRequest request, String csrf); }

    private final Access access;

    VisitHealthController(Access access) { this.access = access; }

    @GetMapping
    ResponseEntity<JsonNode> read(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                  @PathVariable String visitId, @RequestParam String patientId) {
        var scope = access.open(request, csrf);
        return response(200, details(scope.service().read(context(scope, visitId, patientId))));
    }

    @PostMapping
    ResponseEntity<JsonNode> save(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                  @PathVariable String visitId, @RequestHeader("Idempotency-Key") String key,
                                  @RequestBody JsonNode input) {
        AuditDocuments.fields(input, "patientId", "expectedSnapshotId", "medicalHistory", "allergyInformation", "changeContent");
        var scope = access.open(request, csrf);
        var target = context(scope, visitId, AuditDocuments.text(input, "patientId"));
        var update = new VisitHealthService.Update(id(AuditDocuments.text(input, "expectedSnapshotId"), true),
                nullableText(input, "medicalHistory"), nullableText(input, "allergyInformation"),
                AuditDocuments.text(input, "changeContent"));
        var receipt = scope.service().save(target, UUID.fromString(key), update);
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null) {
            var saved = receipt.result();
            if (saved != null && saved.path("visitId").asLong() == target.visit()
                    && saved.path("healthChangeId").asLong() > 0) {
                return response(200, JSON.createObjectNode().put("healthChangeId", saved.path("healthChangeId").asText())
                        .put("visitId", visitId).put("requestReference", receipt.requestReference()));
            }
        }
        if ("REJECTED".equals(receipt.businessOutcome())) {
            return error(409, "HEALTH_CHANGE_REJECTED", receipt.requestReference());
        }
        return error(503, "HEALTH_CHANGE_UNCONFIRMED", receipt.requestReference());
    }

    private static VisitHealthService.Context context(Scope scope, String visitId, String patientId) {
        return new VisitHealthService.Context(scope.clinic(), id(patientId, false), id(visitId, false));
    }

    private static long id(String text, boolean zeroAllowed) {
        long number = Long.parseLong(text);
        if (number < (zeroAllowed ? 0 : 1) || !Long.toString(number).equals(text)) {
            throw new IllegalArgumentException("INVALID_HEALTH_ID");
        }
        return number;
    }

    private static String nullableText(JsonNode input, String key) {
        return input.path(key).isNull() ? null : AuditDocuments.text(input, key);
    }

    private static JsonNode details(VisitHealthService.Details details) {
        var result = JSON.createObjectNode();
        var snapshot = details.snapshot();
        if (snapshot == null) result.putNull("snapshot");
        else result.putObject("snapshot").put("id", Long.toString(snapshot.id()))
                .put("medicalHistory", snapshot.medicalHistory()).put("allergyInformation", snapshot.allergyInformation());
        var treatments = result.putArray("priorTreatments");
        for (var row : details.priorTreatments()) {
            treatments.addObject().put("visitId", Long.toString(row.visitId())).put("recordId", Long.toString(row.recordId()))
                    .put("version", row.version()).put("treatment", row.treatment()).put("result", row.result());
        }
        return result;
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> forbidden(SecurityException e) {
        return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "HEALTH_ACCESS_DENIED", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_HEALTH_INPUT", null); }

    @ExceptionHandler(JdbcTransactions.Failure.class)
    ResponseEntity<JsonNode> unavailable() { return error(503, "HEALTH_DEPENDENCY_UNAVAILABLE", null); }

    private static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code);
        if (reference != null) body.put("requestReference", reference);
        return response(status, body);
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
