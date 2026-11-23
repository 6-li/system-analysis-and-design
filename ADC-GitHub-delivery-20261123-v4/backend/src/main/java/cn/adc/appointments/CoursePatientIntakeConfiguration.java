package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.security.StaffAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Isolated classroom association; the submitted request never grants patient access. */
@Configuration(proxyBeanMethods = false)
@Profile("course-demo")
@ConditionalOnProperty(name = {"adc.uc01.enabled", "adc.course.patient-intake-validation"}, havingValue = "true")
class CoursePatientIntakeConfiguration {
    @Bean
    PatientMaintenanceController.Access coursePatientIntake(Environment env) {
        cn.adc.shared.config.CoursePublicOrigin.require(env.getRequiredProperty("adc.course.public-origin"));
        if (!"adc_course_validation".equals(env.getRequiredProperty("adc.course.database"))) {
            throw new IllegalArgumentException("Course patient intake is restricted to the isolated validation fixture");
        }
        long clinic = env.getRequiredProperty("adc.course.clinic-id", Long.class);
        if (clinic <= 0) throw new IllegalArgumentException("Course clinic must be specified");
        var tx = CourseAppointmentRead.courseTransactions(env, "adc_course_patient_intake",
                "ADC_COURSE_DB_PATIENT_INTAKE_PASSWORD", false);
        var clock = Clock.systemUTC();
        boolean patientAccounts=env.getProperty("adc.course.patient-accounts-enabled",Boolean.class,false);
        var miniSource = new JdbcMiniPatientSource((c,actor,scope,patient,input)->
                verifyFixtureSource(c,actor,scope,patient,input,patientAccounts), clock);
        var grant = new StaffAuthorization.Grant("course-patient-intake", Set.of("BOOKING_RECEPTION"),
                Set.of("UC01"), Set.of(clinic), false);
        return new PatientMaintenanceController.Access() {
            @Override public JsonNode query(HttpServletRequest request, String csrf, JsonNode input) {
                throw new SecurityException("COURSE_PATIENT_CREATE_ONLY");
            }

            @Override public CommandRecovery.Receipt save(HttpServletRequest request, String csrf, UUID key,
                                                          Long patient, JsonNode input) {
                var identity = CourseAppointmentRead.identity(request);
                identity.requireCsrf(csrf);
                var auth = new StaffAuthorization(identity, () -> Map.of("SYNTHETIC reception", grant));
                auth.require("UC01", Set.of(clinic));
                if (patient != null || !input.has("source")) throw new SecurityException("COURSE_PATIENT_CREATE_ONLY");
                return new PatientMaintenanceService(tx, auth,
                        (c, actor, scope, id, document) -> { throw new SecurityException("MINI_SOURCE_REQUIRED"); },
                        CourseBookingIds::patient, CourseBookingIds::audit, clock, miniSource)
                        .save(clinic, null, key, input);
            }
        };
    }

    static void verifyFixtureSource(Connection c, String actor, long clinic, Long patient, JsonNode input) throws SQLException {
        verifyFixtureSource(c,actor,clinic,patient,input,false);
    }
    static void verifyFixtureSource(Connection c, String actor, long clinic, Long patient, JsonNode input,boolean accounts) throws SQLException {
        var source = input.path("source");
        String identity = AuditDocuments.text(input, "identityInformation");
        if (!"SYNTHETIC reception".equals(actor)
                || AuditDocuments.text(source, "identityMatchBasis").isBlank()) {
            throw new SecurityException("COURSE_PATIENT_SOURCE_DENIED");
        }
        String owner=accounts ? "EXISTS (SELECT 1 FROM adc_mini.patient_account a WHERE applicant_account=('ACCOUNT:' || a.account_id::text) AND NOT a.disabled)"
                : "applicant_account='SYNTHETIC-course-mini'";
        try (var query = c.prepareStatement("""
                SELECT 1 FROM adc_mini.preliminary_request
                WHERE request_id=? AND %s
                  AND submitted_content->>'intendedClinicId'=?
                  AND submitted_content->>'type'='NEW'
                  AND submitted_content#>>'{identity,patientIdentification}'=?
                """.formatted(owner))) {
            query.setObject(1, UUID.fromString(AuditDocuments.text(source, "requestId")));
            query.setString(2, Long.toString(clinic));
            query.setString(3, identity);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new SecurityException("COURSE_PATIENT_SOURCE_DENIED");
            }
        }
    }
}
