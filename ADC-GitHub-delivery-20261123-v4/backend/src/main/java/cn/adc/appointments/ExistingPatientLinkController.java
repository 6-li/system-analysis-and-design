package cn.adc.appointments;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
@RestController
@ConditionalOnProperty(name="adc.course.patient-accounts-enabled",havingValue="true")
final class ExistingPatientLinkController {
    interface Access { JsonNode execute(HttpServletRequest request,String csrf,JsonNode input,boolean link); }
    private final Access access;
    ExistingPatientLinkController(Access access){this.access=access;}
    @PostMapping("/api/v1/staff/patient-account-bindings/existing-preview")
    ResponseEntity<JsonNode> preview(HttpServletRequest r,@RequestHeader(value="X-CSRF-Token",required=false)String csrf,@RequestBody JsonNode input){
        AuditDocuments.fields(input,"requestId","requestVersion");return response(200,access.execute(r,csrf,input,false));
    }
    @PostMapping("/api/v1/staff/patient-account-bindings/existing")
    ResponseEntity<JsonNode> link(HttpServletRequest r,@RequestHeader(value="X-CSRF-Token",required=false)String csrf,@RequestBody JsonNode input){
        AuditDocuments.fields(input,"requestId","requestVersion","patientId","verificationBasis","identityConfirmed");
        if(!input.path("identityConfirmed").isBoolean()||!input.path("identityConfirmed").asBoolean())throw new IllegalArgumentException("EXPLICIT_VERIFICATION_REQUIRED");
        return response(200,access.execute(r,csrf,input,true));
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<JsonNode> denied(){return error(403,"EXISTING_PATIENT_LINK_DENIED");}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<JsonNode> stale(){return error(409,"EXISTING_PATIENT_SOURCE_OR_MATCH_UNAVAILABLE");}
    @ExceptionHandler(JdbcTransactions.Failure.class) ResponseEntity<JsonNode> unavailable(){return error(503,"EXISTING_PATIENT_LINK_UNCONFIRMED");}
    private static ResponseEntity<JsonNode> error(int status,String code){return response(status,AuditDocuments.JSON.createObjectNode().put("code",code));}
    private static ResponseEntity<JsonNode> response(int status,JsonNode body){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);}
}
