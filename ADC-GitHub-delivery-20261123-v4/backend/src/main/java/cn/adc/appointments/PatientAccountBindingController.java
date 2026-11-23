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
final class PatientAccountBindingController {
    interface Access { void bind(HttpServletRequest request,String csrf,JsonNode input); }
    private final Access access;
    PatientAccountBindingController(Access access){this.access=access;}
    @PostMapping("/api/v1/staff/patient-account-bindings")
    ResponseEntity<JsonNode> bind(HttpServletRequest request,
            @RequestHeader(value="X-CSRF-Token",required=false)String csrf,@RequestBody JsonNode input) {
        AuditDocuments.fields(input,"requestId","requestVersion","patientId","verificationBasis","identityConfirmed");
        if(!input.path("identityConfirmed").isBoolean()||!input.path("identityConfirmed").asBoolean())
            throw new IllegalArgumentException("EXPLICIT_IDENTITY_CONFIRMATION_REQUIRED");
        access.bind(request,csrf,input);
        return response(200,AuditDocuments.JSON.createObjectNode().put("bound",true));
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<JsonNode> denied(){return error(403,"ACCOUNT_BINDING_DENIED");}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<JsonNode> invalid(){return error(400,"INVALID_BINDING_INPUT");}
    @ExceptionHandler(JdbcTransactions.Failure.class) ResponseEntity<JsonNode> unavailable(){return error(503,"ACCOUNT_BINDING_UNCONFIRMED");}
    private static ResponseEntity<JsonNode> error(int status,String code){return response(status,AuditDocuments.JSON.createObjectNode().put("code",code));}
    private static ResponseEntity<JsonNode> response(int status,JsonNode body){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);}
}
