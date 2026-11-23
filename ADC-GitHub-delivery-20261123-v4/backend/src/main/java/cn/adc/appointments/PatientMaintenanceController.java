package cn.adc.appointments;

import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.db.JdbcTransactions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

@RestController
@ConditionalOnProperty(name = "adc.uc01.enabled", havingValue = "true")
@RequestMapping("/api/v1/staff")
public final class PatientMaintenanceController {
    /** Deployment adapter resolves the active clinic and identity from the verified session. */
    public interface Access {
        JsonNode query(HttpServletRequest request, String csrf, JsonNode input);
        CommandRecovery.Receipt save(HttpServletRequest request, String csrf, UUID key, Long patient, JsonNode input);
    }

    private final Access access;

    public PatientMaintenanceController(Access access) {
        this.access = access;
    }

    @PostMapping("/patient-queries")
    ResponseEntity<JsonNode> query(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                   @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode input) {
        key(key);
        return respond(200, access.query(request, csrf, input));
    }

    @PostMapping("/patients")
    ResponseEntity<JsonNode> create(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                    @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode input) {
        return save(request, csrf, key, null, input);
    }

    @PostMapping("/patients/{patientId}/updates")
    ResponseEntity<JsonNode> update(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                    @RequestHeader("Idempotency-Key") String key, @PathVariable String patientId,
                                    @RequestBody JsonNode input) {
        long id = Long.parseLong(patientId);
        if (id <= 0 || !Long.toString(id).equals(patientId)) throw new IllegalArgumentException("INVALID_PATIENT_ID");
        return save(request, csrf, key, id, input);
    }

    private ResponseEntity<JsonNode> save(HttpServletRequest request, String csrf, String clientKey,
                                          Long patient, JsonNode input) {
        var receipt = access.save(request, csrf, key(clientKey), patient, input);
        var result = receipt.result();
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.successEventId() != null
                && result != null && result.path("patient").path("patientId").isString()) {
            if ("MINI_REQUEST".equals(result.path("sourceKind").asText())) {
                var envelope = (tools.jackson.databind.node.ObjectNode) result.deepCopy();
                envelope.putObject("originalUc01SuccessAudit").put("source", "ORIGINAL_UC")
                        .put("role", "COMMITTED_RESULT").put("ucCode", "UC01")
                        .put("eventId", receipt.successEventId()).put("commandReference", receipt.requestReference());
                return respond(patient == null ? 201 : 200, envelope);
            }
            return respond(patient == null ? 201 : 200, result);
        }
        if ("UNKNOWN".equals(receipt.businessOutcome()) || "SUCCEEDED".equals(receipt.businessOutcome())) {
            return error(503, "COMMIT_OUTCOME_UNKNOWN", receipt.requestReference());
        }
        if ("REJECTED".equals(receipt.businessOutcome())) {
            return error(switch (receipt.reason() == null ? "" : receipt.reason()) {
                case "EXECUTION_PERMISSION_DENIED", "RECOVERY_PERMISSION_DENIED" -> 403;
                case "PATIENT_NOT_FOUND" -> 404;
                default -> 409;
            }, receipt.reason() == null ? "REQUEST_CONFLICT" : receipt.reason(), receipt.requestReference());
        }
        return error(503, "OPERATION_FAILED", receipt.requestReference());
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> denied(SecurityException e) {
        boolean missing = "AUTHENTICATION_REQUIRED".equals(e.getMessage());
        return error(missing ? 401 : 403, missing ? "AUTHENTICATION_REQUIRED" : "FORBIDDEN", null);
    }

    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<JsonNode> malformed(Exception e) {
        if ("PATIENT_IDENTITY_AMBIGUOUS".equals(e.getMessage())) return error(409, "PATIENT_IDENTITY_AMBIGUOUS", null);
        return error(400, "INVALID_INPUT", null);
    }

    @ExceptionHandler(JdbcTransactions.Failure.class)
    ResponseEntity<JsonNode> unavailable() {
        return error(503, "DEPENDENCY_UNAVAILABLE", null);
    }

    private static UUID key(String key) {
        if (key.isBlank()) throw new IllegalArgumentException("INVALID_IDEMPOTENCY_KEY");
        return UUID.nameUUIDFromBytes(("UC01/v1/" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code).put("message", "患者档案操作未完成");
        if (reference != null && !reference.isBlank()) body.put("requestReference", reference);
        return respond(status, body);
    }

    private static ResponseEntity<JsonNode> respond(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
