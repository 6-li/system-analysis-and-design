package cn.adc.scheduling;

import cn.adc.shared.audit.CommandRecovery;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.NoSuchElementException;

import static cn.adc.shared.audit.AuditDocuments.JSON;

/** The deployment must explicitly supply a session-scoped history reader. */
@RestController
@ConditionalOnProperty(name = "adc.uc22.resolution-enabled", havingValue = "true")
@RequestMapping("/api/v1/staff")
public final class ResolutionController {
    public interface HistoryAccess {
        JsonNode read(HttpServletRequest request, long schedule, int version);

        default JsonNode impact(HttpServletRequest request, long schedule, int version) {
            throw new cn.adc.shared.db.JdbcTransactions.Failure(false, "IMPACT_UNAVAILABLE");
        }

        default void requireCsrf(HttpServletRequest request, String token) {
            throw new SecurityException("ACTION_OR_SCOPE_DENIED");
        }

        default CommandRecovery.Receipt save(HttpServletRequest request, long schedule, int version,
                                             String key, JsonNode body) {
            throw new cn.adc.shared.db.JdbcTransactions.Failure(false, "RESOLUTION_SAVE_UNAVAILABLE");
        }

        default CommandRecovery.Receipt activate(HttpServletRequest request, long schedule, int version,
                                                 String key, JsonNode body) {
            throw new cn.adc.shared.db.JdbcTransactions.Failure(false, "ACTIVATION_UNAVAILABLE");
        }

        default CommandRecovery.Receipt activationResult(HttpServletRequest request, String reference) {
            throw new cn.adc.shared.db.JdbcTransactions.Failure(false, "ACTIVATION_LOOKUP_UNAVAILABLE");
        }
    }

    private final HistoryAccess access;

    public ResolutionController(HistoryAccess access) {
        this.access = access;
    }

    @GetMapping("/schedules/{scheduleId}/versions/{versionNo}/resolution-histories")
    ResponseEntity<JsonNode> histories(HttpServletRequest request, @PathVariable String scheduleId,
                                       @PathVariable String versionNo) {
        return read(request, scheduleId, versionNo, false);
    }

    @GetMapping("/schedules/{scheduleId}/versions/{versionNo}/impacts")
    ResponseEntity<JsonNode> impacts(HttpServletRequest request, @PathVariable String scheduleId,
                                     @PathVariable String versionNo) {
        return read(request, scheduleId, versionNo, true);
    }

    @PostMapping("/schedules/{scheduleId}/versions/{versionNo}/impact-checks")
    ResponseEntity<JsonNode> impactCheck(HttpServletRequest request, @PathVariable String scheduleId,
                                         @PathVariable String versionNo,
                                         @RequestHeader(value = "Idempotency-Key", defaultValue = "") String key,
                                         @RequestHeader(value = "X-CSRF-Token", defaultValue = "") String csrf) {
        if (key.isBlank() || csrf.isBlank()) return error(400, "INVALID_INPUT");
        try {
            access.requireCsrf(request, csrf);
        } catch (SecurityException denied) {
            return denied(denied);
        }
        return read(request, scheduleId, versionNo, true);
    }

    private ResponseEntity<JsonNode> read(HttpServletRequest request, String scheduleId, String versionNo,
                                          boolean impact) {
        long schedule;
        int version;
        try {
            schedule = Long.parseLong(scheduleId);
            version = Integer.parseInt(versionNo);
            if (schedule <= 0 || version <= 0) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            return error(400, "INVALID_INPUT");
        }
        try {
            return response(200, impact ? access.impact(request, schedule, version) : access.read(request, schedule, version));
        } catch (SecurityException denied) {
            return denied(denied);
        } catch (NoSuchElementException missing) {
            return error(404, "NOT_FOUND");
        } catch (IllegalArgumentException inconsistent) {
            return error(409, impact ? "IMPACT_UNVERIFIABLE" : "RESOLUTION_HISTORY_UNVERIFIABLE");
        } catch (cn.adc.shared.db.JdbcTransactions.Failure unavailable) {
            return error(503, "DEPENDENCY_UNAVAILABLE");
        }
    }

    @PostMapping("/schedules/{scheduleId}/versions/{versionNo}/resolutions")
    ResponseEntity<JsonNode> save(HttpServletRequest request, @PathVariable long scheduleId,
                                  @PathVariable int versionNo,
                                  @RequestHeader(value = "Idempotency-Key", defaultValue = "") String key,
                                  @RequestHeader(value = "X-CSRF-Token", defaultValue = "") String csrf,
                                  @RequestBody JsonNode body) {
        if (scheduleId <= 0 || versionNo <= 0 || key.isBlank() || csrf.isBlank() || !body.isObject()) {
            return error(400, "INVALID_INPUT");
        }
        try {
            access.requireCsrf(request, csrf);
            return commandReceipt(access.save(request, scheduleId, versionNo, key, body), false);
        } catch (SecurityException denied) {
            return denied(denied);
        } catch (NoSuchElementException missing) {
            return error(404, "NOT_FOUND");
        } catch (IllegalArgumentException invalid) {
            return error(400, "INVALID_INPUT");
        } catch (cn.adc.shared.db.JdbcTransactions.Failure unavailable) {
            return error(503, "DEPENDENCY_UNAVAILABLE");
        }
    }

    @PostMapping("/schedules/{scheduleId}/versions/{versionNo}/activations")
    ResponseEntity<JsonNode> activate(HttpServletRequest request, @PathVariable long scheduleId,
                                      @PathVariable int versionNo,
                                      @RequestHeader(value = "Idempotency-Key", defaultValue = "") String key,
                                      @RequestHeader(value = "X-CSRF-Token", defaultValue = "") String csrf,
                                      @RequestBody JsonNode body) {
        if (scheduleId <= 0 || versionNo <= 0 || key.isBlank() || csrf.isBlank() || !body.isObject()) {
            return error(400, "INVALID_INPUT");
        }
        try {
            access.requireCsrf(request, csrf);
            return commandReceipt(access.activate(request, scheduleId, versionNo, key, body), true);
        } catch (SecurityException denied) {
            return denied(denied);
        } catch (NoSuchElementException missing) {
            return error(404, "NOT_FOUND");
        } catch (IllegalArgumentException invalid) {
            return error(400, "INVALID_INPUT");
        } catch (cn.adc.shared.db.JdbcTransactions.Failure | cn.adc.shared.config.ConfigurationUnavailable unavailable) {
            return error(503, "DEPENDENCY_UNAVAILABLE");
        }
    }

    @GetMapping("/schedule-commands/{*requestReference}")
    ResponseEntity<JsonNode> activationResult(HttpServletRequest request, @PathVariable String requestReference) {
        String reference = requestReference.startsWith("/") ? requestReference.substring(1) : requestReference;
        try {
            return commandReceipt(access.activationResult(request, reference), true);
        } catch (SecurityException denied) {
            return denied(denied);
        } catch (NoSuchElementException missing) {
            return error(404, "NOT_FOUND");
        } catch (IllegalArgumentException invalid) {
            return error(400, "INVALID_INPUT");
        } catch (cn.adc.shared.db.JdbcTransactions.Failure unavailable) {
            return error(503, "DEPENDENCY_UNAVAILABLE");
        }
    }

    private static ResponseEntity<JsonNode> commandReceipt(CommandRecovery.Receipt receipt, boolean activation) {
        String reference = receipt.requestReference();
        if (reference == null || reference.isBlank()) return error(503, "DEPENDENCY_UNAVAILABLE");
        var body = JSON.createObjectNode().put("requestReference", reference).put("ucCode", "UC22")
                .put("operationKind", activation ? "ACTIVATE" : "RESOLUTION_SAVE").put("existingResult", receipt.existingResult())
                .put("auditPersistence", receipt.auditPersistence().name());
        if ("SUCCEEDED".equals(receipt.businessOutcome()) && receipt.result() != null
                && receipt.auditPersistence() == CommandRecovery.Persistence.CONFIRMED
                && receipt.successEventId() != null && !receipt.successEventId().isBlank()
                && (activation ? receipt.result().path("scheduleId").asText().matches("[1-9][0-9]*")
                    && receipt.result().path("versionNo").isIntegralNumber()
                    && receipt.result().path("versionNo").intValue() > 0
                    : receipt.result().path("resolution_id").asText().matches("[1-9][0-9]*"))) {
            body.put("status", "COMPLETED").put("businessOutcome", "SUCCEEDED")
                    .put("message", activation ? "已生效排班版本" : "已保存处理记录");
            var links = body.putObject("links");
            if (activation) links.putObject("scheduleRef").put("scheduleId", receipt.result().path("scheduleId").asText())
                    .put("versionNo", receipt.result().path("versionNo").intValue());
            else links.put("resolutionId", receipt.result().path("resolution_id").asText());
            return response(200, body);
        }
        if ("UNKNOWN".equals(receipt.businessOutcome()) || "SUCCEEDED".equals(receipt.businessOutcome())) {
            body.put("status", "UNKNOWN").put("businessOutcome", "UNKNOWN").put("message", "结果待核对，请勿自动重试");
            return response(202, body);
        }
        var reason = receipt.reason();
        if ("EXECUTION_PERMISSION_DENIED".equals(reason) || "RECOVERY_PERMISSION_DENIED".equals(reason)) {
            return error(403, "FORBIDDEN", reference);
        }
        if ("FAILED".equals(receipt.businessOutcome())) return error(503, "OPERATION_FAILED", reference);
        if ("RESOLUTION_FORMAL_EVIDENCE_UNCONFIRMED".equals(reason)
                || "RESOLUTION_FORMAL_EVIDENCE_MISMATCH".equals(reason)) return error(422, reason, reference);
        if ("ACTIVATION_ELIGIBILITY_UNCONFIRMED".equals(reason) || "APPOINTMENT_HANDLING_REQUIRED".equals(reason)
                || "EFFECTIVE_SCHEDULE_CONFLICT".equals(reason)) return error(422, reason, reference);
        return error(409, reason == null ? "REQUEST_CONFLICT" : reason, reference);
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<JsonNode> malformed() {
        return error(400, "INVALID_INPUT");
    }

    private static ResponseEntity<JsonNode> denied(SecurityException denied) {
        return "AUTHENTICATION_REQUIRED".equals(denied.getMessage())
                ? error(401, "AUTHENTICATION_REQUIRED") : error(403, "FORBIDDEN");
    }

    static JsonNode historyBody(List<ResolutionHistoryReadService.History> histories) {
        var body = JSON.createArrayNode();
        for (var history : histories) {
            var item = body.addObject().put("rootResolutionId", Long.toString(history.root()))
                    .put("chainIntegrity", "VALID").put("currentHeadReference", Long.toString(history.head()))
                    .put("expectedResolutionToken", history.headFingerprint()).put("currentAssessment", history.currentAssessment());
            item.set("originalRecord", history.revisions().getFirst());
            var revisions = item.putArray("revisions");
            history.revisions().stream().skip(1).forEach(revisions::add);
            history.formalFacts().forEach(item.withArray("formalOperationFacts")::add);
        }
        return body;
    }

    private static ResponseEntity<JsonNode> error(int status, String code) {
        return error(status, code, null);
    }

    private static ResponseEntity<JsonNode> error(int status, String code, String reference) {
        var body = JSON.createObjectNode().put("code", code).put("message", "处理操作未完成");
        if (reference != null) body.put("requestReference", reference);
        return response(status, body);
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
