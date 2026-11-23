package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(name="adc.course.patient-accounts-enabled", havingValue="true")
@RequestMapping("/api/v1/mini")
final class PatientAccountController {
    private final PatientAccounts accounts;
    PatientAccountController(PatientAccounts accounts) { this.accounts = accounts; }
    @PostMapping("/accounts") ResponseEntity<JsonNode> register(@RequestBody JsonNode input) {
        AuditDocuments.fields(input,"username","password");
        var id = accounts.register(AuditDocuments.text(input,"username"), password(input));
        return response(201, AuditDocuments.JSON.createObjectNode().put("accountId",id.toString()).put("identityVerified",false));
    }
    @PostMapping("/sessions/password") ResponseEntity<JsonNode> login(@RequestBody JsonNode input) {
        AuditDocuments.fields(input,"username","password");
        String token = accounts.login(AuditDocuments.text(input,"username"), password(input));
        return response(200, AuditDocuments.JSON.createObjectNode().put("accessToken",token).put("identityVerified",false));
    }
    private static char[] password(JsonNode input) {
        var value=input.path("password");
        if(!value.isTextual() || value.asText().length()>128) throw new IllegalArgumentException("INVALID_INPUT");
        return value.asText().toCharArray();
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<JsonNode> denied() { return error(401,"ACCOUNT_LOGIN_FAILED"); }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<JsonNode> invalid(IllegalArgumentException e) {
        return "ACCOUNT_USERNAME_UNAVAILABLE".equals(e.getMessage()) ? error(409,"ACCOUNT_USERNAME_UNAVAILABLE") : error(400,"INVALID_INPUT");
    }
    @ExceptionHandler({JdbcTransactions.Failure.class, IllegalStateException.class})
    ResponseEntity<JsonNode> unavailable() { return error(503,"ACCOUNT_SERVICE_UNAVAILABLE"); }
    private static ResponseEntity<JsonNode> error(int status,String code) { return response(status,AuditDocuments.JSON.createObjectNode().put("code",code)); }
    private static ResponseEntity<JsonNode> response(int status,JsonNode body) { return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body); }
}
