package cn.adc.appointments;
import cn.adc.shared.db.JdbcTransactions;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import static cn.adc.shared.audit.AuditDocuments.JSON;
@RestController @Profile("course-demo")
@ConditionalOnProperty(name="adc.course.ui-unification-enabled",havingValue="true")
final class CourseVisitDirectory {
 private final JdbcTransactions reception,doctor;private final long clinic,dentist;
 @org.springframework.beans.factory.annotation.Autowired
 CourseVisitDirectory(Environment env){
  clinic=env.getRequiredProperty("adc.course.clinic-id",Long.class);dentist=env.getRequiredProperty("adc.course.dentist-id",Long.class);
  if(clinic!=81001||dentist<=0||!"adc_course_validation".equals(env.getRequiredProperty("adc.course.database")))throw new IllegalArgumentException("VISIT_DIRECTORY_SCOPE_REQUIRED");
  reception=CourseAppointmentRead.courseTransactions(env,false);doctor=CourseAppointmentRead.courseTransactions(env,"adc_course_health","ADC_COURSE_DB_HEALTH_PASSWORD",true);
 }
 CourseVisitDirectory(JdbcTransactions reception,JdbcTransactions doctor,long clinic,long dentist){this.reception=reception;this.doctor=doctor;this.clinic=clinic;this.dentist=dentist;}
 @GetMapping("/api/v1/staff/my-visits/by-appointment/{appointmentId}")
 ResponseEntity<JsonNode> ownAppointment(HttpServletRequest req,@PathVariable String appointmentId){
  require(req,"SYNTHETIC dentist");long appointment=id(appointmentId);
  return doctor.execute(c->{
   var out=JSON.createObjectNode();boolean found;
   try(var p=c.prepareStatement("SELECT v.visit_id,a.patient_id,v.appointment_id,v.registered_at,(SELECT string_agg(t.performed_treatment,'、' ORDER BY r.clinical_record_id) FROM adc.clinical_record r JOIN adc.clinical_record_version t USING(clinical_record_id) WHERE r.visit_id=v.visit_id AND t.is_current AND t.implementation_result='已实施') FROM adc.visit v JOIN adc.appointment a ON a.appointment_id=v.appointment_id WHERE v.clinic_id=? AND v.actual_dentist_id=? AND a.appointment_status='已到诊' AND v.appointment_id=? LIMIT 2")){
    p.setLong(1,clinic);p.setLong(2,dentist);p.setLong(3,appointment);
    try(var r=p.executeQuery()){
     found=r.next();
     if(found){out.put("visitId",r.getString(1)).put("patientId",r.getString(2)).put("appointmentId",r.getString(3)).put("registeredAt",r.getObject(4,OffsetDateTime.class).toInstant().toString()).put("projects",r.getString(5));if(r.next())throw new java.sql.SQLException("AMBIGUOUS_VISIT","40001");}
    }
   }
   require(req,"SYNTHETIC dentist");
   return found?response(200,out):response(404,JSON.createObjectNode().put("code","VISIT_NOT_FOUND"));
  });
 }
 static void require(HttpServletRequest request,String expected){var actor=CourseAppointmentRead.identity(request).current();if(actor.isEmpty())throw new SecurityException("AUTHENTICATION_REQUIRED");if(!expected.equals(actor.get()))throw new SecurityException("VISIT_DIRECTORY_DENIED");}
 static long id(String value){long n=Long.parseLong(value);if(n<1||!Long.toString(n).equals(value))throw new IllegalArgumentException("INVALID_ID");return n;}
 @GetMapping("/api/v1/staff/appointments/{appointmentId}/visit")
 ResponseEntity<JsonNode> appointment(HttpServletRequest req,@PathVariable String appointmentId){
  require(req,"SYNTHETIC reception");long appointment=id(appointmentId);
  return response(200,reception.execute(c->{
   try(var p=c.prepareStatement("SELECT a.patient_id FROM adc.appointment a JOIN adc.reception_schedule_version s USING(schedule_id,schedule_version_no) WHERE a.appointment_id=? AND s.clinic_id=?")){
    p.setLong(1,appointment);p.setLong(2,clinic);try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("VISIT_DIRECTORY_DENIED");}
   }
   var out=JSON.createObjectNode().put("appointmentId",appointmentId);out.putNull("visit");
   try(var p=c.prepareStatement("SELECT visit_id,actual_dentist_id,registered_at FROM adc.visit WHERE appointment_id=? AND clinic_id=?")){
    p.setLong(1,appointment);p.setLong(2,clinic);try(var r=p.executeQuery()){if(r.next())out.putObject("visit").put("visitId",r.getString(1)).put("dentistId",r.getString(2)).put("registeredAt",r.getObject(3,OffsetDateTime.class).toInstant().toString());}
   }
   require(req,"SYNTHETIC reception");return out;
  }));
 }
 @GetMapping("/api/v1/staff/my-visits")
 ResponseEntity<JsonNode> mine(HttpServletRequest req,@RequestParam String date,@RequestParam(defaultValue="0") String cursor){
  require(req,"SYNTHETIC dentist");LocalDate day=LocalDate.parse(date);if(!day.toString().equals(date))throw new IllegalArgumentException("INVALID_DATE");long after="0".equals(cursor)?0:id(cursor);
  return response(200,doctor.execute(c->{
   var out=JSON.createObjectNode().put("date",date);var items=out.putArray("items");
   try(var p=c.prepareStatement("SELECT v.visit_id,a.patient_id,v.appointment_id,v.registered_at,(SELECT string_agg(t.performed_treatment,'、' ORDER BY r.clinical_record_id) FROM adc.clinical_record r JOIN adc.clinical_record_version t USING(clinical_record_id) WHERE r.visit_id=v.visit_id AND t.is_current AND t.implementation_result='已实施') FROM adc.visit v JOIN adc.appointment a ON a.appointment_id=v.appointment_id WHERE v.clinic_id=? AND v.actual_dentist_id=? AND a.appointment_status='已到诊' AND v.registered_at>=? AND v.registered_at<? AND v.visit_id>? ORDER BY v.visit_id LIMIT 26")){
    p.setLong(1,clinic);p.setLong(2,dentist);p.setObject(3,day.atStartOfDay().atOffset(ZoneOffset.ofHours(8)));p.setObject(4,day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.ofHours(8)));p.setLong(5,after);
    try(var r=p.executeQuery()){String last=null;while(r.next()){if(items.size()==25){out.put("nextCursor",last);break;}last=r.getString(1);items.addObject().put("visitId",last).put("patientId",r.getString(2)).put("appointmentId",r.getString(3)).put("registeredAt",r.getObject(4,OffsetDateTime.class).toInstant().toString()).put("projects",r.getString(5));}}
   }
   require(req,"SYNTHETIC dentist");return out;
  }));
 }
 @ExceptionHandler(SecurityException.class)ResponseEntity<JsonNode> denied(SecurityException e){return response("AUTHENTICATION_REQUIRED".equals(e.getMessage())?401:403,JSON.createObjectNode().put("code","VISIT_DIRECTORY_DENIED"));}
 @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class})ResponseEntity<JsonNode> invalid(){return response(400,JSON.createObjectNode().put("code","INVALID_VISIT_QUERY"));}
 @ExceptionHandler(JdbcTransactions.Failure.class)ResponseEntity<JsonNode> unavailable(){return response(503,JSON.createObjectNode().put("code","VISIT_DIRECTORY_UNAVAILABLE"));}
 static ResponseEntity<JsonNode> response(int status,JsonNode body){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);}
}
