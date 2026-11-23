package cn.adc.appointments;
import cn.adc.shared.audit.AuditDocuments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
@RestController
@ConditionalOnProperty(name="adc.course.patient-accounts-enabled",havingValue="true")
final class PatientAccountLogoutController {
    private final MiniRequestSession sessions;
    PatientAccountLogoutController(MiniRequestSession sessions){this.sessions=sessions;}
    @PostMapping("/api/v1/mini/sessions/logout")
    ResponseEntity<JsonNode> logout(@RequestHeader(value="Authorization",required=false)String bearer){
        sessions.logout(bearer);return result(200,"SIGNED_OUT");
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<JsonNode> denied(){return result(401,"ACCOUNT_SESSION_REQUIRED");}
    private static ResponseEntity<JsonNode> result(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(AuditDocuments.JSON.createObjectNode().put("code",code));}
}
