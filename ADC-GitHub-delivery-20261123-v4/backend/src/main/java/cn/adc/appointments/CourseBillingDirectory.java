package cn.adc.appointments;
import cn.adc.shared.db.JdbcTransactions;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import static cn.adc.shared.audit.AuditDocuments.JSON;
@RestController @Profile("course-demo")
@ConditionalOnProperty(name="adc.course.ui-unification-enabled",havingValue="true")
final class CourseBillingDirectory {
 private final JdbcTransactions tx;private final long clinic;
 CourseBillingDirectory(Environment env){clinic=env.getRequiredProperty("adc.course.clinic-id",Long.class);if(clinic!=81001||!"adc_course_validation".equals(env.getRequiredProperty("adc.course.database")))throw new IllegalArgumentException("BILLING_DIRECTORY_SCOPE_REQUIRED");tx=CourseAppointmentRead.courseTransactions(env,"adc_course_billing","ADC_COURSE_DB_BILLING_PASSWORD",true);}
 static void validateStatus(String status){if(!java.util.Set.of("PENDING","","待核对确认","待结算","已结算","待再次确认","待核实").contains(status))throw new IllegalArgumentException("INVALID_STATUS");}
 @GetMapping("/api/v1/staff/billing/invoices")
 ResponseEntity<JsonNode> list(HttpServletRequest req,@RequestParam(defaultValue="0")String cursor,@RequestParam(name="status",required=false)String requestedStatus){
  CourseVisitDirectory.require(req,"SYNTHETIC billing");long after="0".equals(cursor)?0:CourseVisitDirectory.id(cursor);
  String status=requestedStatus==null?"PENDING":requestedStatus;
  validateStatus(status);
  return CourseVisitDirectory.response(200,tx.execute(c->{var out=JSON.createObjectNode();var items=out.putArray("items");
   try(var p=c.prepareStatement("SELECT i.invoice_id,i.visit_id,a.patient_id,i.invoice_status,v.invoice_version_no,v.total_amount FROM adc.treatment_invoice i JOIN adc.visit t ON t.visit_id=i.visit_id JOIN adc.appointment a ON a.appointment_id=t.appointment_id JOIN adc.treatment_invoice_version v ON v.invoice_id=i.invoice_id AND v.is_current WHERE t.clinic_id=? AND i.invoice_id>? AND (?='' OR (?='PENDING' AND i.invoice_status<>'已结算') OR i.invoice_status=?) ORDER BY i.invoice_id LIMIT 26")){
    p.setLong(1,clinic);p.setLong(2,after);p.setString(3,status);p.setString(4,status);p.setString(5,status);try(var r=p.executeQuery()){String last=null;while(r.next()){if(items.size()==25){out.put("nextCursor",last);break;}last=r.getString(1);items.addObject().put("invoiceId",last).put("visitId",r.getString(2)).put("patientId",r.getString(3)).put("status",r.getString(4)).put("invoiceVersionNo",r.getInt(5)).put("total",r.getString(6));}}}
   CourseVisitDirectory.require(req,"SYNTHETIC billing");return out;}));
 }
 @GetMapping("/api/v1/staff/billing/invoices/{invoiceId}")
 ResponseEntity<JsonNode> detail(HttpServletRequest req,@PathVariable String invoiceId){
  CourseVisitDirectory.require(req,"SYNTHETIC billing");long id=CourseVisitDirectory.id(invoiceId);
  return CourseVisitDirectory.response(200,tx.execute(c->{long visit;
   try(var p=c.prepareStatement("SELECT i.visit_id FROM adc.treatment_invoice i JOIN adc.visit v ON v.visit_id=i.visit_id WHERE i.invoice_id=? AND v.clinic_id=?")){p.setLong(1,id);p.setLong(2,clinic);try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("BILLING_DIRECTORY_DENIED");visit=r.getLong(1);}}
   var bill=TreatmentInvoiceService.read(c,visit,null).orElseThrow(()->new IllegalArgumentException("INVOICE_NOT_FOUND"));
   var out=TreatmentInvoiceService.evidence(bill,visit).after();CourseVisitDirectory.require(req,"SYNTHETIC billing");return out;}));
 }
 @GetMapping("/api/v1/staff/billing/unbilled-visits")
 ResponseEntity<JsonNode> unbilled(HttpServletRequest req,@RequestParam(defaultValue="0")String cursor){
  CourseVisitDirectory.require(req,"SYNTHETIC billing");long after="0".equals(cursor)?0:CourseVisitDirectory.id(cursor);
  return CourseVisitDirectory.response(200,tx.execute(c->{var out=JSON.createObjectNode();var items=out.putArray("items");
   try(var p=c.prepareStatement("SELECT v.visit_id,a.patient_id FROM adc.visit v JOIN adc.appointment a ON a.appointment_id=v.appointment_id WHERE v.clinic_id=? AND v.visit_id>? AND NOT EXISTS(SELECT 1 FROM adc.treatment_invoice i WHERE i.visit_id=v.visit_id) AND EXISTS(SELECT 1 FROM adc.clinical_record r JOIN adc.clinical_record_version t USING(clinical_record_id) WHERE r.visit_id=v.visit_id AND t.is_current AND t.implementation_result='已实施') ORDER BY v.visit_id LIMIT 26")){
    p.setLong(1,clinic);p.setLong(2,after);try(var r=p.executeQuery()){String last=null;while(r.next()){if(items.size()==25){out.put("nextCursor",last);break;}last=r.getString(1);items.addObject().put("visitId",last).put("patientId",r.getString(2));}}}CourseVisitDirectory.require(req,"SYNTHETIC billing");return out;}));
 }
 @ExceptionHandler(SecurityException.class)ResponseEntity<JsonNode> denied(SecurityException e){return CourseVisitDirectory.response("AUTHENTICATION_REQUIRED".equals(e.getMessage())?401:403,JSON.createObjectNode().put("code","BILLING_DIRECTORY_DENIED"));}
 @ExceptionHandler(IllegalArgumentException.class)ResponseEntity<JsonNode> invalid(){return CourseVisitDirectory.response(400,JSON.createObjectNode().put("code","INVALID_BILLING_QUERY"));}
 @ExceptionHandler({JdbcTransactions.Failure.class,cn.adc.shared.audit.CommandRecovery.Rejection.class})ResponseEntity<JsonNode> unavailable(){return CourseVisitDirectory.response(503,JSON.createObjectNode().put("code","BILLING_DIRECTORY_UNAVAILABLE"));}
}
