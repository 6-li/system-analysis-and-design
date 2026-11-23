package cn.adc.appointments;

import cn.adc.shared.security.StaffAuthorization;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.util.*;

@Configuration(proxyBeanMethods=false)
@Profile("course-demo")
@ConditionalOnProperty(name="adc.course.inbox-enabled",havingValue="true")
class CourseMiniRequestInboxConfiguration {
    @Bean MiniRequestInboxController.Access courseMiniRequestInbox(Environment env){
        long clinic=env.getRequiredProperty("adc.course.clinic-id",Long.class);
        var tx=CourseAppointmentRead.courseTransactions(env,"adc_course_patient_intake","ADC_COURSE_DB_PATIENT_INTAKE_PASSWORD",true);
        return request->{
            var identity=CourseAppointmentRead.identity(request);
            if(!identity.current().filter("SYNTHETIC reception"::equals).isPresent())throw new SecurityException("INBOX_RECEPTION_REQUIRED");
            var grant=new StaffAuthorization.Grant("course-inbox",Set.of("BOOKING_RECEPTION"),Set.of("UC01","UC02","UC03","UC04"),Set.of(clinic),false);
            return new MiniRequestInboxService(tx,new StaffAuthorization(identity,()->Map.of("SYNTHETIC reception",grant)),clinic);
        };
    }
}
