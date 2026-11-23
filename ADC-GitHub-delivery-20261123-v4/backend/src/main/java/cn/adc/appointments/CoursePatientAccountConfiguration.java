package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Configuration(proxyBeanMethods=false)
@Profile("course-demo")
@ConditionalOnProperty(name="adc.course.patient-accounts-enabled",havingValue="true")
class CoursePatientAccountConfiguration {
    @Bean ExistingPatientLinkController.Access existingPatientLinkAccess(Environment env) {
        long clinic=env.getRequiredProperty("adc.course.clinic-id",Long.class);
        var tx=CourseAppointmentRead.courseTransactions(env,"adc_course_patient_intake","ADC_COURSE_DB_PATIENT_INTAKE_PASSWORD",false);
        var grant=new cn.adc.shared.security.StaffAuthorization.Grant("course-existing-patient-link",java.util.Set.of("BOOKING_RECEPTION"),java.util.Set.of("UC01"),java.util.Set.of(clinic),false);
        return (request,csrf,input,link)->{
            var identity=CourseAppointmentRead.identity(request);identity.requireCsrf(csrf);
            var auth=new cn.adc.shared.security.StaffAuthorization(identity,()->java.util.Map.of("SYNTHETIC reception",grant));
            String actor=auth.require("UC01",java.util.Set.of(clinic)).actor();
            UUID id=UUID.fromString(cn.adc.shared.audit.AuditDocuments.text(input,"requestId"));
            if(!id.toString().equals(input.path("requestId").asText())||!input.path("requestVersion").isIntegralNumber()
                    ||!input.path("requestVersion").canConvertToInt()||input.path("requestVersion").asInt()<0)
                throw new IllegalArgumentException("INVALID_SOURCE");
            return tx.execute(c->{
                var service=new ExistingPatientLink();
                var result=link?service.link(c,id,input.path("requestVersion").asInt(),clinic,
                    InitialBookingReview.id(input,"patientId"),actor,
                    cn.adc.shared.audit.AuditDocuments.text(input,"verificationBasis"))
                    :service.lookup(c,id,input.path("requestVersion").asInt(),clinic);
                if(!actor.equals(auth.require("UC01",java.util.Set.of(clinic)).actor()))throw new SecurityException("EXISTING_PATIENT_LINK_DENIED");
                return result;
            });
        };
    }
    @Bean PatientAccountBindingController.Access patientAccountBindingAccess(Environment env) {
        long clinic=env.getRequiredProperty("adc.course.clinic-id",Long.class);
        var tx=CourseAppointmentRead.courseTransactions(env,"adc_course_patient_intake",
                "ADC_COURSE_DB_PATIENT_INTAKE_PASSWORD",false);
        var grant=new cn.adc.shared.security.StaffAuthorization.Grant("course-patient-account-binding",
                java.util.Set.of("BOOKING_RECEPTION"),java.util.Set.of("UC01"),java.util.Set.of(clinic),false);
        return (request,csrf,input)->{
            var identity=CourseAppointmentRead.identity(request);identity.requireCsrf(csrf);
            var auth=new cn.adc.shared.security.StaffAuthorization(identity,()->java.util.Map.of("SYNTHETIC reception",grant));
            String actor=auth.require("UC01",java.util.Set.of(clinic)).actor();
            UUID id=UUID.fromString(cn.adc.shared.audit.AuditDocuments.text(input,"requestId"));
            String patient=cn.adc.shared.audit.AuditDocuments.text(input,"patientId");
            if(!id.toString().equals(input.path("requestId").asText())||!patient.matches("[1-9][0-9]{0,18}")
                    ||!input.path("requestVersion").isIntegralNumber()||!input.path("requestVersion").canConvertToInt())
                throw new IllegalArgumentException("INVALID_BINDING_INPUT");
            tx.execute(c->{
                new PatientAccountBinding().bind(c,id,input.path("requestVersion").asInt(),clinic,Long.parseLong(patient),actor,
                        cn.adc.shared.audit.AuditDocuments.text(input,"verificationBasis"));
                if(!actor.equals(auth.require("UC01",java.util.Set.of(clinic)).actor()))throw new SecurityException("ACCOUNT_BINDING_DENIED");
                return null;
            });
        };
    }
    @Bean JdbcTransactions patientAccountRequestTransactions(Environment env) {
        return CourseAppointmentRead.courseTransactions(env,"adc_course_patient_intake",
                "ADC_COURSE_DB_PATIENT_INTAKE_PASSWORD",true);
    }
    @Bean JdbcPatientAccounts patientAccountStore(Environment env) {
        if(env.getProperty("adc.mini.wechat-exchange-enabled",Boolean.class,false)
                || !"adc_course_validation".equals(env.getRequiredProperty("adc.course.database"))
                || env.getRequiredProperty("adc.course.database-port",Integer.class)!=55432)
            throw new IllegalArgumentException("DEDICATED_PATIENT_ACCOUNT_ENVIRONMENT_REQUIRED");
        cn.adc.shared.config.CoursePublicOrigin.require(env.getRequiredProperty("adc.course.public-origin"));
        JdbcTransactions tx=CourseAppointmentRead.courseTransactions(env,"adc_course_patient_account",
                "ADC_COURSE_DB_PATIENT_ACCOUNT_PASSWORD",false);
        return new JdbcPatientAccounts(tx);
    }
    @Bean MiniRequestSession.Sessions patientAccountSessions(PatientAccounts.Store store) {
        var delegate=new WeChatCourseSessionConfiguration.ExpiringSessions(Clock.systemUTC());
        return new MiniRequestSession.Sessions() {
            public String issue(String account) { return delegate.issue(account); }
            public void revoke(String token) { delegate.revoke(token); }
            public Optional<String> activeAccount(String token) {
                return delegate.activeAccount(token).filter(account -> {
                    if(!account.startsWith("ACCOUNT:"))return false;
                    try{return store.enabled(UUID.fromString(account.substring(8)));}
                    catch(IllegalArgumentException invalid){return false;}
                });
            }
        };
    }
    @Bean PatientAccounts patientAccounts(JdbcPatientAccounts store, MiniRequestSession.Sessions sessions) {
        return new PatientAccounts(store,new PatientAccountPasswords(),sessions);
    }
    @Bean MiniRequestSession patientAccountRequestSessions(MiniRequestSession.Sessions sessions) {
        return new MiniRequestSession(code -> Optional.empty(),sessions);
    }
}
