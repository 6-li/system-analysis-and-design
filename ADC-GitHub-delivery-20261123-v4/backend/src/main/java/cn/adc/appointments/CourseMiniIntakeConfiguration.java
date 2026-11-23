package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.time.Clock;

/** Course deployment adapter for the existing preliminary request contract. */
@Configuration(proxyBeanMethods = false)
@Profile("course-demo")
@ConditionalOnProperty(name = "adc.mini.preliminary-intake-enabled",
        havingValue = "true")
class CourseMiniIntakeConfiguration {
    @Bean
    JdbcTransactions courseMiniTransactions(Environment environment) {
        return CourseAppointmentRead.courseTransactions(environment, "adc_course_mini_intake",
                "ADC_COURSE_DB_MINI_INTAKE_PASSWORD", false);
    }

    @Bean
    MiniNewRequestIntake courseMiniIntake(MiniRequestSession sessions,
                                          @Qualifier("courseMiniTransactions") JdbcTransactions transactions,
                                          Environment env,
                                          org.springframework.beans.factory.ObjectProvider<MiniBookingOptionsController> options) {
        var reader=options.getIfAvailable();
        MiniNewRequestIntake.Store base=new JdbcMiniRequestIntakeStore(transactions,Clock.systemUTC());
        var store=(JdbcMiniRequestIntakeStore)base;
        if(env.getProperty("adc.course.patient-accounts-enabled",Boolean.class,false))store.withBoundPatient(BoundPatientRequest::resolve);
        if(env.getProperty("adc.course.uc03-enabled",Boolean.class,false)){
            var read=CourseAppointmentRead.courseTransactions(env,"adc_course_mini_read","ADC_COURSE_DB_MINI_READ_PASSWORD",true);
            var changes=new JdbcMiniRequestIntakeStore(transactions,Clock.systemUTC()).withBoundPatient((c,account,input)->{
                Long bound=BoundPatientRequest.resolve(c,account,input);
                long patient=InitialBookingReview.id(input,"patientId"),appointment=InitialBookingReview.id(input,"originalAppointmentId");
                if(bound==null||bound!=patient)throw new SecurityException("MINI_CHANGE_PATIENT_DENIED");
                boolean allowed=read.execute(connection->{
                    if(!new JdbcPatientAccountScope().mayRead(connection,account,patient))return false;
                    var row=new AppointmentRepository().findAppointment(connection,appointment,81001);
                    return row.isPresent()&&row.get().patientId()==patient&&"待到诊".equals(row.get().status())
                        &&row.get().cancellation()==null&&row.get().noShow()==null&&!new AppointmentRepository().hasVisit(connection,row.get());
                });
                if(!allowed)throw new SecurityException("MINI_CHANGE_APPOINTMENT_DENIED");return bound;
            });
            base=new MiniNewRequestIntake.Store(){
                public MiniNewRequestIntake.Receipt saveOnce(String a,String k,String h,tools.jackson.databind.JsonNode i){return store.saveOnce(a,k,h,i);}
                public MiniNewRequestIntake.Receipt saveChange(String a,String k,String h,tools.jackson.databind.JsonNode i){if(env.getProperty("adc.course.booking-batch-enabled",Boolean.class,false)){if(!i.has("selectedScheme"))throw new IllegalArgumentException("PATIENT_CHANGE_SELECTION_REQUIRED");MiniChangeSelection.validate(i);}return changes.saveOnce(a,k,h,i);}
                public MiniNewRequestIntake.Receipt saveCancel(String a,String k,String h,tools.jackson.databind.JsonNode i){if(!env.getProperty("adc.course.booking-batch-enabled",Boolean.class,false))throw new SecurityException("CANCEL_DISABLED");return changes.saveOnce(a,k,h,i);}
                public java.util.Optional<MiniNewRequestIntake.Receipt> find(String a,String k){return store.find(a,k);}
            };
        }
        return new MiniNewRequestIntake(sessions,base,reader==null?null:reader::requireAvailable);
    }

    // Existing internal tests/adapters retain the original non-selected intake overload.
    MiniNewRequestIntake courseMiniIntake(MiniRequestSession sessions,JdbcTransactions transactions){return intake(sessions,transactions,Clock.systemUTC());}

    static MiniNewRequestIntake intake(MiniRequestSession sessions, JdbcTransactions transactions, Clock clock) {
        return new MiniNewRequestIntake(sessions, new JdbcMiniRequestIntakeStore(transactions, clock));
    }
}
