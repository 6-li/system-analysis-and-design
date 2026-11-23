package cn.adc.appointments;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.audit.AuditDocuments;
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
@ConditionalOnProperty(name="adc.course.booking-batch-enabled",havingValue="true")
final class CourseAppointmentCancellation {
    private final Environment env;
    CourseAppointmentCancellation(Environment env){this.env=env;if(!"adc_course_validation".equals(env.getRequiredProperty("adc.course.database"))||env.getRequiredProperty("adc.course.clinic-id",Long.class)!=81001)throw new IllegalArgumentException("COURSE_SCOPE_REQUIRED");}
    @PostMapping("/api/v1/staff/appointments/{id}/cancellations")
    ResponseEntity<JsonNode> cancel(@PathVariable long id,HttpServletRequest request,@RequestHeader("X-CSRF-Token")String csrf,
        @RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode input){
        var identity=CourseAppointmentRead.identity(request);
        try{identity.requireCsrf(csrf);}catch(SecurityException denied){return error(identity.current().isEmpty()?401:403,"CANCELLATION_ACCESS_DENIED");}
        var grant=new StaffAuthorization.Grant("course-uc04",Set.of("BOOKING_RECEPTION"),Set.of("UC04"),Set.of(81001L),false);
        var auth=new StaffAuthorization(identity,()->Map.of("SYNTHETIC reception",grant));
        if(!input.has("sourceContextReference"))return error(400,"CANCELLATION_REQUEST_REQUIRED");
        var tx=CourseAppointmentRead.courseTransactions(env,true);var clock=Clock.systemUTC();
        var service=new AppointmentCancellationService(tx,auth,(c,appointment,basis)->basis.isBlank()?Optional.empty():Optional.of(basis),CourseBookingIds::audit,clock,new AppointmentRepository(),new JdbcMiniCancellationSource(new CourseCancellationSource(),clock));
        var result=new AppointmentController(null,null,null,service).cancel(id,81001,key,input);
        return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(result.body());
    }
    @ExceptionHandler(cn.adc.shared.db.JdbcTransactions.Failure.class)ResponseEntity<JsonNode> failed(){return error(503,"CANCELLATION_UNCONFIRMED");}
    private static ResponseEntity<JsonNode> error(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(AuditDocuments.JSON.createObjectNode().put("code",code));}
}
