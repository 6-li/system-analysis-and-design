package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(name="adc.course.patient-accounts-enabled",havingValue="true")
final class PatientAccountRequestsController {
    private final MiniRequestSession sessions; private final JdbcTransactions tx;
    PatientAccountRequestsController(MiniRequestSession sessions,
            @Qualifier("patientAccountRequestTransactions") JdbcTransactions tx) {this.sessions=sessions;this.tx=tx;}
    @GetMapping("/api/v1/mini/requests")
    ResponseEntity<JsonNode> list(@RequestHeader(value="Authorization",required=false)String bearer,
                                  @RequestParam(required=false)String cursor) {
        String account=sessions.account(bearer);
        var body=tx.execute(c->{
            var result=new PatientAccountRequests().list(c,account,cursor);
            if(!account.equals(sessions.account(bearer)))throw new SecurityException("ACCOUNT_SESSION_REQUIRED");
            return result;
        });
        return response(200,body);
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<JsonNode> denied(){return error(401,"ACCOUNT_SESSION_REQUIRED");}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<JsonNode> invalid(){return error(400,"INVALID_CURSOR");}
    @ExceptionHandler(JdbcTransactions.Failure.class) ResponseEntity<JsonNode> unavailable(){return error(503,"ACCOUNT_REQUESTS_UNAVAILABLE");}
    private static ResponseEntity<JsonNode> error(int status,String code){return response(status,AuditDocuments.JSON.createObjectNode().put("code",code));}
    private static ResponseEntity<JsonNode> response(int status,JsonNode body){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);}
}
