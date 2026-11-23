package cn.adc.inventory;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.security.*;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.time.StrictInputPolicy;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
@RestController @Profile("course-demo")
@ConditionalOnProperty(name="adc.course.quarterly-usability-enabled",havingValue="true")
final class ConsumptionCostController {
 private final CourseInventoryConfiguration course;
 ConsumptionCostController(CourseInventoryConfiguration course){this.course=course;}
 @PostMapping("/api/v1/staff/clinics/{clinicId}/consumption-cost-confirmations")
 ResponseEntity<JsonNode> confirm(HttpServletRequest req,@RequestHeader("X-CSRF-Token") String csrf,@RequestHeader("Idempotency-Key") String key,@PathVariable String clinicId,@RequestBody JsonNode input){
  AuditDocuments.fields(input,"supplyId","quantity","occurredAt","verificationBasis");
  var identity=new StaffSessionIdentityAdapter(()->{var s=req.getSession(false);var v=s==null?null:s.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);return v instanceof StaffSessionIdentityAdapter.VerifiedSession verified?verified:null;});identity.requireCsrf(csrf);
  String q=AuditDocuments.text(input,"quantity");if(!q.matches("[0-9]{1,15}(\\.[0-9]{1,3})?"))throw new IllegalArgumentException("INVALID_QUANTITY");
  long clinic=id(clinicId),supply=id(AuditDocuments.text(input,"supplyId"));
  var service=new ConsumptionCostConfirmation(course.transactions(),new StaffAuthorization(identity,CourseInventoryConfiguration.bindings()::bindings),Clock.systemUTC());
  var receipt=service.confirm(new ConsumptionCostConfirmation.Input(clinic,supply,new BigDecimal(q),StrictInputPolicy.instant(AuditDocuments.text(input,"occurredAt")),AuditDocuments.text(input,"verificationBasis")),UUID.fromString(key));
  if("SUCCEEDED".equals(receipt.businessOutcome())&&receipt.result()!=null){var out=AuditDocuments.JSON.createObjectNode().put("requestReference",receipt.requestReference());out.set("fact",receipt.result());return response(200,out);}
  return response("REJECTED".equals(receipt.businessOutcome())?409:503,AuditDocuments.JSON.createObjectNode().put("code","COST_CONFIRMATION_UNCONFIRMED").put("requestReference",receipt.requestReference()));
 }
 static long id(String value){long id=Long.parseLong(value);if(id<=0||!Long.toString(id).equals(value))throw new IllegalArgumentException("INVALID_ID");return id;}
 @ExceptionHandler(SecurityException.class) ResponseEntity<JsonNode> denied(SecurityException e){return response("AUTHENTICATION_REQUIRED".equals(e.getMessage())?401:403,AuditDocuments.JSON.createObjectNode().put("code","COST_CONFIRMATION_ACCESS_DENIED"));}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<JsonNode> invalid(){return response(400,AuditDocuments.JSON.createObjectNode().put("code","INVALID_COST_CONFIRMATION"));}
 @ExceptionHandler(JdbcTransactions.Failure.class) ResponseEntity<JsonNode> unavailable(){return response(503,AuditDocuments.JSON.createObjectNode().put("code","COST_CONFIRMATION_UNCONFIRMED"));}
 static ResponseEntity<JsonNode> response(int status,JsonNode body){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);}
}
