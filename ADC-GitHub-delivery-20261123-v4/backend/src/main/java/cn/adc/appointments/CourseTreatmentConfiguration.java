package cn.adc.appointments;

import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
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

/** Course adapter only. Existing UC09 service owns consent and implementation validation. */
@Configuration(proxyBeanMethods = false)
@Profile("course-demo")
@ConditionalOnProperty(name = "adc.uc09.enabled", havingValue = "true")
class CourseTreatmentConfiguration {
    @Bean
    VisitTreatmentController.Access courseTreatmentAccess(Environment env,org.springframework.beans.factory.ObjectProvider<CourseProjectCatalog> projects) {
        var base=treatmentAccess(env.getRequiredProperty("adc.course.clinic-id", Long.class),
                env.getRequiredProperty("adc.course.dentist-id", Long.class),
                CourseAppointmentRead.courseTransactions(env,"adc_course_health","ADC_COURSE_DB_HEALTH_PASSWORD",false),env.getProperty("adc.course.clinical-batch-enabled",Boolean.class,false)?CourseClinicalIds::record:CourseBookingIds::audit);
        return (request,csrf)->{var opened=base.open(request,csrf);if(env.getProperty("adc.course.quarterly-usability-enabled",Boolean.class,false))opened.service().enablePatientNotes();var catalog=projects.getIfAvailable();if(catalog!=null)opened.service().enableProjectScopes(catalog.scopes);return opened;};
    }

    static VisitTreatmentController.Access treatmentAccess(long clinic, long dentist, JdbcTransactions tx){
        return treatmentAccess(clinic,dentist,tx,CourseBookingIds::audit);
    }

    static VisitTreatmentController.Access treatmentAccess(long clinic, long dentist, JdbcTransactions tx, FormalBookingService.AppointmentIds factIds) {
        if (clinic <= 0 || dentist <= 0) throw new IllegalArgumentException("COURSE_CLINICAL_SCOPE_REQUIRED");
        var clock = Clock.systemUTC();
        var stamp = new SourceStamp("SYNTHETIC course treatment identity","course-v1","course",clock.instant(),true);
        var bindings = new StaffBindingAdapter(() -> List.of(new StaffBindingAdapter.Entry("SYNTHETIC dentist",
                new StaffAuthorization.Grant(stamp.version(),Set.of("DENTIST"),Set.of("UC09"),Set.of(clinic),false),stamp)));
        return new VisitTreatmentAccess(tx,bindings,request -> clinic,
                (actor,id) -> "SYNTHETIC dentist".equals(actor) && id == clinic ? dentist : 0,
                factIds,CourseBookingIds::audit,clock);
    }
}
