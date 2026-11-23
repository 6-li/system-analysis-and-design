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

/** Reuses UC08's dentist/visit checks; course identities never represent real consent. */
@Configuration(proxyBeanMethods = false)
@Profile("course-demo")
@ConditionalOnProperty(name = "adc.uc08.enabled", havingValue = "true")
class CourseConsentConfiguration {
    @Bean
    VisitConsentController.Access courseConsentAccess(Environment env,org.springframework.beans.factory.ObjectProvider<CourseProjectCatalog> projects) {
        long clinic = env.getRequiredProperty("adc.course.clinic-id", Long.class);
        long dentist = env.getRequiredProperty("adc.course.dentist-id", Long.class);
        var base=consentAccess(clinic, dentist, CourseAppointmentRead.courseTransactions(env,
                "adc_course_health", "ADC_COURSE_DB_HEALTH_PASSWORD", false),env.getProperty("adc.course.clinical-batch-enabled",Boolean.class,false)?CourseClinicalIds::consent:CourseBookingIds::audit);
        return (request,csrf)->{var opened=base.open(request,csrf);var catalog=projects.getIfAvailable();if(catalog!=null)opened.service().enableProjectScopes(catalog.scopes);return opened;};
    }

    static VisitConsentController.Access consentAccess(long clinic, long dentist, JdbcTransactions transactions){
        return consentAccess(clinic,dentist,transactions,CourseBookingIds::audit);
    }

    static VisitConsentController.Access consentAccess(long clinic, long dentist, JdbcTransactions transactions, FormalBookingService.AppointmentIds factIds) {
        if (clinic <= 0 || dentist <= 0) throw new IllegalArgumentException("COURSE_CLINICAL_SCOPE_REQUIRED");
        var clock = Clock.systemUTC();
        var stamp = new SourceStamp("SYNTHETIC course consent identity", "course-v1", "course", clock.instant(), true);
        var bindings = new StaffBindingAdapter(() -> List.of(new StaffBindingAdapter.Entry("SYNTHETIC dentist",
                new StaffAuthorization.Grant(stamp.version(), Set.of("DENTIST"), Set.of("UC08"), Set.of(clinic), false), stamp)));
        return new VisitConsentAccess(transactions, bindings, request -> clinic,
                (actor, id) -> "SYNTHETIC dentist".equals(actor) && id == clinic ? dentist : 0,
                factIds, CourseBookingIds::audit, clock);
    }
}
