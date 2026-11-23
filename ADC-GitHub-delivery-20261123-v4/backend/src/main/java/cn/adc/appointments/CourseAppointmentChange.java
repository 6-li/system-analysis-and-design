package cn.adc.appointments;

import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.config.*;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

@RestController @Profile("course-demo")
@ConditionalOnProperty(name="adc.course.uc03-enabled",havingValue="true")
final class CourseAppointmentChange {
    private final Environment env;
    CourseAppointmentChange(Environment env){
        if(!"adc_course_validation".equals(env.getRequiredProperty("adc.course.database"))
            ||env.getRequiredProperty("adc.course.clinic-id",Long.class)!=81001)throw new IllegalArgumentException("COURSE_CHANGE_SCOPE_REQUIRED");
        this.env=env;
    }
    static StaffAuthorization authorization(HttpServletRequest request){
        var grant=new StaffAuthorization.Grant("course-uc03",Set.of("BOOKING_RECEPTION"),Set.of("UC02","UC03"),Set.of(81001L),false);
        return new StaffAuthorization(CourseAppointmentRead.identity(request),()->Map.of("SYNTHETIC reception",grant));
    }
    @PostMapping("/api/v1/staff/appointments/{id}/changes")
    ResponseEntity<JsonNode> change(@PathVariable long id,HttpServletRequest request,@RequestHeader("X-CSRF-Token")String csrf,
        @RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode input){
        var auth=authorization(request);
        try{auth.require("UC03",Set.of(81001L));CourseAppointmentRead.identity(request).requireCsrf(csrf);}
        catch(SecurityException denied){return ResponseEntity.status(CourseAppointmentRead.identity(request).current().isEmpty()?401:403).cacheControl(CacheControl.noStore()).build();}
        if(!input.has("sourceContextReference"))return error(400,"COURSE_CHANGE_REQUEST_REQUIRED");
        var tx=CourseAppointmentRead.courseTransactions(env,true);var clock=Clock.systemUTC();
        var stamp=new SourceStamp("SYNTHETIC configured course change directory","course-change-v1","course configuration",clock.instant(),true);
        Set<Long> doctors=Arrays.stream(env.getRequiredProperty("adc.course.synthetic-dentist-ids").split(",")).map(String::trim).map(Long::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var service=new AppointmentChangeService(tx,auth,clinic->{if(clinic!=81001)throw new ConfigurationUnavailable();return new ClinicDirectoryProvider.Clinic(clinic,"SYNTHETIC course clinic",ZoneId.of(env.getRequiredProperty("adc.course.clinic-zone")),stamp);},
            (doctor,clinic)->{if(clinic!=81001||!doctors.contains(doctor))throw new ConfigurationUnavailable();return new DentistEligibilityProvider.Eligibility(doctor,clinic,"SYNTHETIC course association",stamp);},
            BookingInteractionController.verifier(request),CourseBookingIds::change,CourseBookingIds::audit,
            (c,before,scheme)->"SYNTHETIC course change reminder",CommittedBookingRelease::confirms,clock,
            new AppointmentRepository(),new JdbcMiniChangeSource(new CourseChangeSource(),clock));
        var result=BookingInteractionController.serialized(request,()->new AppointmentController(null,null,service).change(id,81001,81001,key,input));
        return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(result.body());
    }
    @ExceptionHandler(cn.adc.shared.db.JdbcTransactions.Failure.class) ResponseEntity<JsonNode> unavailable(){return error(503,"COURSE_CHANGE_UNAVAILABLE");}
    @ExceptionHandler(Rejection.class) ResponseEntity<JsonNode> stale(){return error(409,"COURSE_CHANGE_SOURCE_STALE");}
    private static ResponseEntity<JsonNode> error(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(cn.adc.shared.audit.AuditDocuments.JSON.createObjectNode().put("code",code));}
}
