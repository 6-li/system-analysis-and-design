package cn.adc.appointments;
import cn.adc.shared.security.StaffAuthorization;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
@RestController @Profile("course-demo")
@ConditionalOnProperty(name="adc.course.clinical-batch-enabled",havingValue="true")
final class CourseAttendance {
    private final Environment env;
    CourseAttendance(Environment env){this.env=env;if(!"adc_course_validation".equals(env.getRequiredProperty("adc.course.database"))||env.getRequiredProperty("adc.course.clinic-id",Long.class)!=81001)throw new IllegalArgumentException("COURSE_CLINIC_REQUIRED");}
    @PostMapping("/api/v1/staff/appointments/{id}/arrival-verifications")
    ResponseEntity<JsonNode> arrive(@PathVariable long id,HttpServletRequest request,@RequestHeader("X-CSRF-Token")String csrf,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode input){return write(ArrivalNoShowService.Action.ARRIVAL,id,request,csrf,key,input);}
    @PostMapping("/api/v1/staff/appointments/{id}/no-show-verifications")
    ResponseEntity<JsonNode> noShow(@PathVariable long id,HttpServletRequest request,@RequestHeader("X-CSRF-Token")String csrf,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode input){return write(ArrivalNoShowService.Action.NO_SHOW,id,request,csrf,key,input);}
    private ResponseEntity<JsonNode> write(ArrivalNoShowService.Action action,long id,HttpServletRequest request,String csrf,String key,JsonNode input){
        var identity=CourseAppointmentRead.identity(request);
        try{identity.requireCsrf(csrf);}catch(SecurityException denied){return ResponseEntity.status(identity.current().isEmpty()?401:403).cacheControl(CacheControl.noStore()).build();}
        var grant=new StaffAuthorization.Grant("course-attendance",Set.of("CLINIC_RECEPTION"),Set.of("UC05","UC06"),Set.of(81001L),false);
        var auth=new StaffAuthorization(identity,()->Map.of("SYNTHETIC reception",grant));var clock=Clock.systemUTC();
        var doctors=Arrays.stream(env.getRequiredProperty("adc.course.synthetic-dentist-ids").split(",")).map(String::trim).map(Long::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var service=new ArrivalNoShowService(CourseAppointmentRead.courseTransactions(env,true),auth,new AppointmentRepository(),
            (c,appointment,finding)->{
                if(finding.actualDentist()!=null&&!doctors.contains(finding.actualDentist()))return Optional.empty();
                return Optional.of(new ArrivalNoShowService.Verified("SYNTHETIC reception verification: "+finding.basis(),null));
            },CourseClinicalIds::visit,CourseBookingIds::audit,clock);
        var result=new AppointmentController(null,null,null,null,service).attendance(action,id,81001,key,input);
        return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(result.body());
    }
    @ExceptionHandler(cn.adc.shared.db.JdbcTransactions.Failure.class)ResponseEntity<JsonNode> failed(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(cn.adc.shared.audit.AuditDocuments.JSON.createObjectNode().put("code","ATTENDANCE_UNCONFIRMED"));}
}
