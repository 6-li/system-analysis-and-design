package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.time.*;import java.util.*;import java.util.function.Supplier;

/** Explicitly synthetic course entry. No database writes and no automatic booking. */
@RestController
@ConditionalOnProperty(name="adc.course.booking-interaction-enabled",havingValue="true")
public final class BookingInteractionController {
 public record Context(BookingProposalService proposals,StaffAuthorization auth,long clinic,String zone,SourceStamp stamp){}
 public interface Access { Context open(HttpServletRequest request); void source(HttpServletRequest request,String reference,long patient);
  default JsonNode selected(HttpServletRequest request,String reference){return null;}
  default InitialBookingReview.AcceptedScheme changePlan(HttpServletRequest request,String reference,long patient,JsonNode scheme){throw new Rejection("CHANGE_CONFIRMATION_UNAVAILABLE");}
 }
 private final Access access;
 public BookingInteractionController(Access access){this.access=access;}
 @GetMapping("/api/v1/staff/booking-calendar")
 public ResponseEntity<JsonNode> calendar(HttpServletRequest request,@RequestParam String date){var context=access.open(request);return response(200,context.proposals().calendar(context.clinic(),LocalDate.parse(date)));}
 @GetMapping("/api/v1/staff/booking-options")
 public ResponseEntity<JsonNode> options(HttpServletRequest request,@RequestParam String date,
           @RequestParam(required=false) String sourceContextReference){
  var context=access.open(request);String actor=context.auth().require("UC02",Set.of(context.clinic())).actor();
  if(sourceContextReference!=null)access.source(request,sourceContextReference,0);
  var result=AuditDocuments.JSON.createObjectNode().put("synthetic",true).put("clinicId",Long.toString(context.clinic())).put("timeZone",context.zone()).put("reservesCapacity",false);
  var projects=result.putArray("projects");
  for(var row:context.proposals().projects(context.clinic()))projects.addObject().put("projectId",row.path("project_id").asText()).put("projectName",row.path("project_name").asText()).put("projectVersion",row.path("duration_version_no").asInt()).put("minutes",row.path("duration_minutes").asInt());
  var windows=result.putArray("windows");
  for(var window:context.proposals().windows(context.clinic(),LocalDate.parse(date)))windows.addObject().put("scheduleId",Long.toString(window.ref().scheduleId())).put("versionNo",window.ref().versionNo()).put("dentistId",Long.toString(window.dentistId())).put("startsAt",window.startsAt().toString()).put("endsAt",window.endsAt().toString());
  if(!actor.equals(context.auth().require("UC02",Set.of(context.clinic())).actor()))throw new SecurityException("ACTOR_CHANGED");
  return response(200,result);
 }
 @PostMapping("/api/v1/staff/booking-confirmations")
 public ResponseEntity<JsonNode> confirm(HttpServletRequest request,@RequestHeader("X-CSRF-Token")String csrf,@RequestBody JsonNode input){
  CourseAppointmentRead.identity(request).requireCsrf(csrf);
  return serialized(request,()->{
   var context=access.open(request);String actor=context.auth().require("UC02",Set.of(context.clinic())).actor();
   if(!input.isObject()||!Set.of("patientId","scheme","confirmed","actingMode","verificationBasis","sourceContextReference").containsAll(input.propertyNames()))throw new IllegalArgumentException("INVALID_CONFIRMATION_FIELDS");
   if(!input.path("confirmed").isBoolean()||!input.path("confirmed").booleanValue()||!"SELF".equals(input.path("actingMode").asText()))throw new IllegalArgumentException("EXPLICIT_SELF_CONFIRMATION_REQUIRED");
   String verification=AuditDocuments.text(input,"verificationBasis");if(verification.length()>2000)throw new IllegalArgumentException("VERIFICATION_TOO_LONG");
   long patient=InitialBookingReview.id(input,"patientId");String source=input.has("sourceContextReference")?AuditDocuments.text(input,"sourceContextReference"):null;
   if(source!=null)access.source(request,source,patient);
   var scheme=input.path("scheme");AuditDocuments.fields(scheme,"effectiveScheduleRef","startsAt","endsAt","treatmentRequest","durationBasis");
   var original=source==null?null:access.selected(request,source);
   if(original!=null&&!original.equals(scheme))throw new Rejection("PATIENT_SELECTED_SCHEME_CHANGED");
   var ref=scheme.path("effectiveScheduleRef");AuditDocuments.fields(ref,"scheduleId","versionNo");
   var basis=scheme.path("durationBasis");AuditDocuments.fields(basis,"schemaVersion","primaryProjectId","projectVersion","adoptedDurationMinutes","basisMode");
   if(integer(basis,"schemaVersion")!=1)throw new Rejection("INVALID_DURATION_BASIS");
   boolean change=!"INITIAL_CURRENT".equals(basis.path("basisMode").asText());
   if(change && source==null)throw new Rejection("CHANGE_SOURCE_REQUIRED");
   var start=OffsetDateTime.parse(AuditDocuments.text(scheme,"startsAt"));
   var plan=change?access.changePlan(request,source,patient,scheme):context.proposals().propose(context.clinic(),patient,new Version(InitialBookingReview.id(ref,"scheduleId"),integer(ref,"versionNo")),InitialBookingReview.id(basis,"primaryProjectId"),start.toLocalDateTime().toString(),start.getOffset(),AuditDocuments.text(scheme,"treatmentRequest"));
   if(!plan.endsAt().equals(cn.adc.shared.time.StrictInputPolicy.instant(AuditDocuments.text(scheme,"endsAt")))||plan.duration().versionNo()!=integer(basis,"projectVersion")||plan.duration().minutes()!=integer(basis,"adoptedDurationMinutes"))throw new Rejection("STALE_BOOKING_PROPOSAL");
   if(!actor.equals(context.auth().require("UC02",Set.of(context.clinic())).actor()))throw new SecurityException("ACTOR_CHANGED");
   var evidence=confirmations(request).record(actor,"SYNTHETIC patient:"+patient,plan,new SourceStamp("SYNTHETIC manual verification: "+verification,context.stamp().version(),actor,Instant.now(),true),true,null);
   var result=AuditDocuments.JSON.createObjectNode().put("synthetic",true).put("patientId",Long.toString(patient)).put("acceptanceEvidenceReference",evidence);result.set("scheme",scheme.deepCopy());
   return response(200,result);
  });
 }
 private static int integer(JsonNode node,String key){return cn.adc.shared.time.StrictInputPolicy.positiveMinutes(node.get(key));}
 static SyntheticBookingConfirmations confirmations(HttpServletRequest request){var session=request.getSession(false);if(session==null)throw new SecurityException("AUTHENTICATION_REQUIRED");synchronized(session){var existing=session.getAttribute("ADC_SYNTHETIC_BOOKING_CONFIRMATIONS");if(existing instanceof SyntheticBookingConfirmations value)return value;var value=new SyntheticBookingConfirmations(Clock.systemUTC());session.setAttribute("ADC_SYNTHETIC_BOOKING_CONFIRMATIONS",value);return value;}}
 static <T>T serialized(HttpServletRequest request,Supplier<T> work){synchronized(confirmations(request)){return work.get();}}
 static BookingAcceptanceVerifier verifier(HttpServletRequest request){String actor=CourseAppointmentRead.identity(request).current().orElseThrow(()->new SecurityException("AUTHENTICATION_REQUIRED"));return new BookingAcceptanceVerifier(confirmations(request).forStaff(actor),new BookingAcceptanceVerifier.ApplicantAuthority(){public boolean isPatient(java.sql.Connection c,String subject,long patient){return subject.equals("SYNTHETIC patient:"+patient);}public boolean mayAcceptBooking(java.sql.Connection c,String subject,InitialBookingReview.AcceptedScheme scheme,String grant){return false;}},Clock.systemUTC());}
 @ExceptionHandler(SecurityException.class) public ResponseEntity<JsonNode> denied(SecurityException e){return error("AUTHENTICATION_REQUIRED".equals(e.getMessage())?401:403,"BOOKING_INTERACTION_DENIED");}
 @ExceptionHandler(Rejection.class) public ResponseEntity<JsonNode> stale(Rejection e){return error(409,"BOOKING_PROPOSAL_STALE");}
 @ExceptionHandler({IllegalArgumentException.class,DateTimeException.class,org.springframework.web.bind.ServletRequestBindingException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})public ResponseEntity<JsonNode> invalid(Exception e){return error(400,"INVALID_BOOKING_SELECTION");}
 @ExceptionHandler(cn.adc.shared.db.JdbcTransactions.Failure.class) public ResponseEntity<JsonNode> unavailable(){return error(503,"BOOKING_SOURCE_UNAVAILABLE");}
 private static ResponseEntity<JsonNode> error(int status,String code){return response(status,AuditDocuments.JSON.createObjectNode().put("code",code));}
 private static ResponseEntity<JsonNode> response(int status,JsonNode body){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);}
}



