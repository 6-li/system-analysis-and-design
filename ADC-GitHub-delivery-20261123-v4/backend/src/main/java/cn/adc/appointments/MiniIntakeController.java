package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import static cn.adc.shared.audit.AuditDocuments.JSON;

/** Controlled preliminary NEW intake. The deployment must supply trusted session and storage adapters. */
@RestController
@ConditionalOnProperty(name = "adc.mini.preliminary-intake-enabled", havingValue = "true")
@RequestMapping("/api/v1/mini")
final class MiniIntakeController {
    private final MiniRequestSession sessions;
    private final MiniNewRequestIntake intake;

    MiniIntakeController(MiniRequestSession sessions, MiniNewRequestIntake intake) {
        this.sessions = sessions;
        this.intake = intake;
    }

    @PostMapping("/sessions/wechat")
    ResponseEntity<JsonNode> login(@RequestBody JsonNode input) {
        return respond(200, sessions.login(input));
    }

    @PostMapping("/requests")
    ResponseEntity<JsonNode> submit(@RequestHeader(value = "Authorization", required = false) String bearer,
                                    @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode input) {
        return respond(201, intake.submit(bearer, key, input));
    }

    @GetMapping("/submission-receipts/{submissionKey}")
    ResponseEntity<JsonNode> receipt(@RequestHeader(value = "Authorization", required = false) String bearer,
                                     @PathVariable String submissionKey, @RequestParam String operation) {
        return respond(200, intake.recover(bearer, submissionKey, operation));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> denied() { return error(401, "MINI_SESSION_OR_CODE_UNVERIFIED"); }
    @ExceptionHandler(cn.adc.shared.audit.CommandRecovery.Rejection.class)
    ResponseEntity<JsonNode> stale(){return error(409,"MINI_SELECTED_SLOT_UNAVAILABLE");}

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> invalid(IllegalArgumentException failure) {
        return "MINI_SUBMISSION_CONFLICT".equals(failure.getMessage())
                ? error(409, "MINI_SUBMISSION_CONFLICT") : error(400, "INVALID_INPUT");
    }

    @ExceptionHandler({JdbcTransactions.Failure.class, IllegalStateException.class})
    ResponseEntity<JsonNode> unavailable() { return error(503, "MINI_RECEIPT_UNAVAILABLE"); }

    private static ResponseEntity<JsonNode> error(int status, String code) {
        return respond(status, JSON.createObjectNode().put("code", code).put("message", "小程序请求未能确认"));
    }

    private static ResponseEntity<JsonNode> respond(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
