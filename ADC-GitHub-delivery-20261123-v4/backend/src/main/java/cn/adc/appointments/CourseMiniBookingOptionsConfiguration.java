package cn.adc.appointments;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import java.time.ZoneId;import java.util.*;

@Configuration(proxyBeanMethods=false) @Profile("course-demo")
@ConditionalOnProperty(name="adc.mini.booking-options-enabled",havingValue="true")
class CourseMiniBookingOptionsConfiguration {
    @Bean MiniBookingOptionsController.Directory miniBookingDirectory(Environment env){
        long clinic=env.getRequiredProperty("adc.course.clinic-id",Long.class);
        var zone=ZoneId.of(env.getRequiredProperty("adc.course.clinic-zone"));
        Set<Long> dentists=Arrays.stream(env.getRequiredProperty("adc.course.synthetic-dentist-ids").split(",")).map(String::trim).map(Long::parseLong).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new MiniBookingOptionsController.Directory(){
            public ZoneId zone(long id){if(id!=clinic)throw new IllegalStateException("CLINIC_DIRECTORY_UNAVAILABLE");return zone;}
            public boolean dentist(long id,long dentist){return id==clinic&&dentists.contains(dentist);}
        };
    }
}
