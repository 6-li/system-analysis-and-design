package cn.adc.appointments;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.db.JdbcTransactions;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import java.sql.*;
import java.time.*;
import java.util.*;

@RestController @Profile("course-demo")
@ConditionalOnProperty(name="adc.course.booking-batch-enabled",havingValue="true")
final class RequestRejectionController {
    private final JdbcTransactions tx;
    RequestRejectionController(Environment env){tx=CourseAppointmentRead.courseTransactions(env,true);tx.execute(c->{if(!RequestDecisions.ready(c))throw new SQLException("BOOKING_BATCH_DEPENDENCIES_REQUIRED");return null;});}
    @PostMapping("/api/v1/staff/mini-requests/{requestId}/rejections")
    ResponseEntity<JsonNode> reject(@PathVariable UUID requestId,HttpServletRequest request,@RequestHeader("X-CSRF-Token")String csrf,
        @RequestHeader("Idempotency-Key")UUID key,@RequestBody JsonNode input){
        var identity=CourseAppointmentRead.identity(request);identity.requireCsrf(csrf);
        var grant=new StaffAuthorization.Grant("course-request-decision",Set.of("BOOKING_RECEPTION"),Set.of("UC01","UC02","UC03","UC04"),Set.of(81001L),false);
        var auth=new StaffAuthorization(identity,()->Map.of("SYNTHETIC reception",grant));
        String actor=auth.require("UC02",Set.of(81001L)).actor();
        AuditDocuments.fields(input,"expectedRequestVersion","reason");
        if(!input.path("expectedRequestVersion").isIntegralNumber()||!input.path("expectedRequestVersion").canConvertToInt()||input.path("expectedRequestVersion").asInt()<0)throw new IllegalArgumentException();
        int version=input.path("expectedRequestVersion").asInt();String reason=AuditDocuments.text(input,"reason");if(reason.length()>2000)throw new IllegalArgumentException();
        String hash=AuditDocuments.hash(AuditDocuments.JSON.createObjectNode().put("requestId",requestId.toString()).set("input",input));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tx.execute(c->{var result=reject(c,requestId,version,reason,actor,key,hash);if(!actor.equals(auth.require("UC02",Set.of(81001L)).actor()))throw new SecurityException("ACTOR_CHANGED");return result;}));
    }
    static JsonNode reject(Connection c,UUID id,int version,String reason,String actor,UUID key,String hash)throws SQLException{
        // Read only receipt recovery before taking the same source row lock used by successful commands.
        try(var q=c.prepareStatement("SELECT decision_id,request_hash FROM adc_mini.request_decision WHERE decided_by=? AND idempotency_key=?")){
            q.setString(1,actor);q.setObject(2,key);try(var r=q.executeQuery()){if(r.next()){if(!hash.equals(r.getString(2)))throw new Rejection("DECISION_KEY_CONFLICT");return result(r.getObject(1,UUID.class));}}
        }
        Long patient;JsonNode input;
        try(var q=c.prepareStatement("SELECT associated_patient_id,submitted_content::text,request_version FROM adc_mini.preliminary_request WHERE request_id=? FOR UPDATE")){
            q.setObject(1,id);try(var r=q.executeQuery()){if(!r.next()||r.getInt(3)!=version)throw new Rejection("REQUEST_VERSION_STALE");patient=r.getObject(1,Long.class);input=AuditDocuments.parse(r.getString(2));}
        }
        String type=input.path("type").asText();long clinic;
        if("NEW".equals(type))clinic=InitialBookingReview.id(input,"intendedClinicId");
        else {
            try(var q=c.prepareStatement("SELECT s.clinic_id FROM adc.appointment a JOIN adc.reception_schedule_version s ON (s.schedule_id,s.schedule_version_no)=(a.schedule_id,a.schedule_version_no) WHERE a.appointment_id::text=? AND a.patient_id=?")){
                q.setString(1,input.path("originalAppointmentId").asText());q.setObject(2,patient,Types.BIGINT);try(var r=q.executeQuery()){if(!r.next())throw new SecurityException("REQUEST_SCOPE_DENIED");clinic=r.getLong(1);}
            }
        }
        if(clinic!=81001)throw new SecurityException("REQUEST_SCOPE_DENIED");
        if(MiniRequestInboxService.completed(c,id,type,patient,input,clinic))throw new Rejection("REQUEST_ALREADY_COMPLETED");
        if(RequestDecisions.reason(c,id)!=null){
            try(var q=c.prepareStatement("SELECT decision_id,request_hash FROM adc_mini.request_decision WHERE decided_by=? AND idempotency_key=?")){
                q.setString(1,actor);q.setObject(2,key);try(var r=q.executeQuery()){if(r.next()&&hash.equals(r.getString(2)))return result(r.getObject(1,UUID.class));}
            }
            throw new Rejection("REQUEST_ALREADY_REJECTED");
        }
        UUID decision=UUID.randomUUID();var now=cn.adc.shared.time.StrictInputPolicy.eventTime(Clock.systemUTC()).atOffset(ZoneOffset.UTC);
        try(var q=c.prepareStatement("INSERT INTO adc_mini.request_decision VALUES(?,?,?,'REJECTED',?,?,?,?,?)")){
            q.setObject(1,decision);q.setObject(2,id);q.setInt(3,version);q.setString(4,reason);q.setString(5,actor);q.setObject(6,now);q.setObject(7,key);q.setString(8,hash);if(q.executeUpdate()!=1)throw new SQLException("DECISION_UNCONFIRMED");
        }
        try(var q=c.prepareStatement("INSERT INTO adc_mini.operation_audit_event(request_id,operation,event_at,event_id,original_request_reference) VALUES(?,'REQUEST_REJECTED',?,?,?)")){
            q.setObject(1,id);q.setObject(2,now);q.setObject(3,decision);q.setString(4,"REQUEST_REJECTED:"+decision);if(q.executeUpdate()!=1)throw new SQLException("DECISION_AUDIT_UNCONFIRMED");
        }
        return result(decision);
    }
    private static JsonNode result(UUID id){return AuditDocuments.JSON.createObjectNode().put("status","REJECTED").put("decisionId",id.toString());}
    @ExceptionHandler(SecurityException.class)ResponseEntity<JsonNode> denied(){return error(403,"REQUEST_DECISION_DENIED");}
    @ExceptionHandler(Rejection.class)ResponseEntity<JsonNode> stale(){return error(409,"REQUEST_DECISION_STALE");}
    @ExceptionHandler(IllegalArgumentException.class)ResponseEntity<JsonNode> invalid(){return error(400,"INVALID_REJECTION");}
    @ExceptionHandler(JdbcTransactions.Failure.class)ResponseEntity<JsonNode> failed(){return error(503,"REQUEST_DECISION_UNCONFIRMED");}
    private static ResponseEntity<JsonNode> error(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(AuditDocuments.JSON.createObjectNode().put("code",code));}
}
