package cn.adc.appointments;

import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/** Explicit SYNTHETIC identity adapter; does not install roles or enable clinical routes. */
@Configuration(proxyBeanMethods = false)
@Profile("course-demo")
@ConditionalOnProperty(name = "adc.uc07.enabled", havingValue = "true")
class CourseHealthConfiguration {
    @Bean
    VisitHealthController.Access courseHealthAccess(Environment env) {
        long clinic = env.getRequiredProperty("adc.course.clinic-id", Long.class);
        long dentist = env.getRequiredProperty("adc.course.dentist-id", Long.class);
        if (clinic <= 0 || dentist <= 0) throw new IllegalArgumentException("COURSE_CLINICAL_SCOPE_REQUIRED");
        var tx = CourseAppointmentRead.courseTransactions(env, "adc_course_health",
                "ADC_COURSE_DB_HEALTH_PASSWORD", false);
        return healthAccess(clinic, dentist, tx,env.getProperty("adc.course.clinical-batch-enabled",Boolean.class,false)?CourseClinicalIds::health:CourseBookingIds::audit);
    }

    static VisitHealthController.Access healthAccess(long clinic, long dentist, cn.adc.shared.db.JdbcTransactions tx){
        return healthAccess(clinic,dentist,tx,CourseBookingIds::audit);
    }

    static VisitHealthController.Access healthAccess(long clinic, long dentist, cn.adc.shared.db.JdbcTransactions tx, FormalBookingService.AppointmentIds factIds) {
        if (clinic <= 0 || dentist <= 0) throw new IllegalArgumentException("COURSE_CLINICAL_SCOPE_REQUIRED");
        var clock = Clock.systemUTC();
        var stamp = new SourceStamp("SYNTHETIC course clinical identity", "course-v1", "course", clock.instant(), true);
        var bindings = new StaffBindingAdapter(() -> List.of(new StaffBindingAdapter.Entry("SYNTHETIC dentist",
                new StaffAuthorization.Grant(stamp.version(), Set.of("DENTIST"), Set.of("UC07"), Set.of(clinic), false), stamp)));
        return new VisitHealthAccess(tx, bindings, request -> clinic,
                (actor, requestedClinic) -> "SYNTHETIC dentist".equals(actor) && requestedClinic == clinic ? dentist : 0,
                factIds, CourseBookingIds::audit, clock);
    }
}
