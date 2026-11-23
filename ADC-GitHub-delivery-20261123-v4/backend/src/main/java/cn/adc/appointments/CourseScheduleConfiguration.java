package cn.adc.appointments;

import cn.adc.scheduling.ScheduleAccessFactory;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.config.ConfigurationUnavailable;
import cn.adc.shared.config.DentistEligibilityProvider;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reuses the accepted scheduling services in an explicitly enabled course deployment. */
@Configuration(proxyBeanMethods = false)
@Profile("course-demo")
@ConditionalOnProperty(name = "adc.course.scheduling-enabled", havingValue = "true")
class CourseScheduleConfiguration {
    @Bean
    ScheduleAccessFactory courseSchedules(Environment env) {
        long clinic = env.getRequiredProperty("adc.course.clinic-id", Long.class);
        long dentist = env.getRequiredProperty("adc.course.dentist-id", Long.class);
        var dentists=configuredDentists(env,dentist);
        String zone = env.getRequiredProperty("adc.course.clinic-zone");
        if (clinic <= 0 || dentist <= 0 || !ZoneId.getAvailableZoneIds().contains(zone)) {
            throw new IllegalArgumentException("COURSE_SCHEDULE_DIRECTORY_REQUIRED");
        }
        var clock = Clock.systemUTC();
        var stamp = new SourceStamp("SYNTHETIC course schedule directory", "course-v1", "course", clock.instant(), true);
        var bindings = new StaffBindingAdapter(() -> List.of(new StaffBindingAdapter.Entry("SYNTHETIC manager",
                new StaffAuthorization.Grant(stamp.version(), Set.of("CLINIC_MANAGER"),
                        env.getProperty("adc.uc22.resolution-enabled",Boolean.class,false)?Set.of("UC21","UC22"):Set.of("UC21"),
                        Set.of(clinic), false), stamp)));
        var transactions = CourseAppointmentRead.courseTransactions(env, "adc_course_schedule",
                "ADC_COURSE_DB_SCHEDULE_PASSWORD", false);
        return new ScheduleAccessFactory(transactions, bindings, () -> stamp,
                id -> {
                    if (id != clinic) throw new ConfigurationUnavailable();
                    return new ClinicDirectoryProvider.Clinic(id, "SYNTHETIC course clinic", ZoneId.of(zone), stamp);
                }, (doctor, id) -> {
                    if (id != clinic || !dentists.contains(doctor)) throw new ConfigurationUnavailable();
                    return new DentistEligibilityProvider.Eligibility(doctor, id, "SYNTHETIC course qualification", stamp);
                }, (id, doctor, start, end, submitted, conflicts) -> Optional.empty(), clock,
                CourseBookingIds::audit, c -> {
                    try (var statement = c.prepareStatement("SELECT nextval('adc.course_resolution_id_seq'::regclass)");
                         var row = statement.executeQuery()) {
                        if (row.next()) {
                            long value = row.getLong(1);
                            if (value > 0) return value;
                        }
                    }
                    throw new java.sql.SQLException("COURSE_RESOLUTION_ID_UNAVAILABLE", "22003");
                },env.getProperty("adc.course.uc22-narrow-locks-enabled",Boolean.class,false));
    }
    static Set<Long> configuredDentists(Environment env,long fallback){
        var ids=new java.util.HashSet<Long>();
        for(String value:env.getProperty("adc.course.synthetic-dentist-ids",Long.toString(fallback)).split(",",-1)){
            if(!value.trim().matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("COURSE_DENTIST_DIRECTORY_REQUIRED");
            long id=Long.parseLong(value.trim());
            if(id<=0)throw new IllegalArgumentException("COURSE_DENTIST_DIRECTORY_REQUIRED");ids.add(id);
        }
        if(!ids.contains(fallback))throw new IllegalArgumentException("COURSE_PERSONAL_DENTIST_NOT_IN_DIRECTORY");
        return Set.copyOf(ids);
    }
}
