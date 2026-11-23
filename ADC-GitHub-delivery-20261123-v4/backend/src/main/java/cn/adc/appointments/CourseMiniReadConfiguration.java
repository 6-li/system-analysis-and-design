package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@Profile("course-demo")
@ConditionalOnProperty(name = "adc.mini.appointment-read-enabled", havingValue = "true")
class CourseMiniReadConfiguration {
    @Bean
    JdbcTransactions miniReadTransactions(Environment env) {
        return CourseAppointmentRead.courseTransactions(env, "adc_course_mini_read", "ADC_COURSE_DB_MINI_READ_PASSWORD", true);
    }

    @Bean
    MiniAppointmentReadController.PatientScope courseMiniScope(Environment env) {
        if(env.getProperty("adc.course.patient-accounts-enabled",Boolean.class,false))return new JdbcPatientAccountScope();
        Path file = Path.of(env.getRequiredProperty("adc.course.patient-grants-file"));
        if (!file.isAbsolute()) throw new IllegalArgumentException("ABSOLUTE_COURSE_GRANTS_PATH_REQUIRED");
        return new FileScope(file);
    }

    /** Deployment-owned file, reloaded at each authorization check to observe revocation. */
    static final class FileScope implements MiniAppointmentReadController.PatientScope {
        private final Path file;
        FileScope(Path file) { this.file = file; }

        private CourseMiniPatientScope current() throws SQLException {
            try {
                byte[] bytes;
                try (var stream = Files.newInputStream(file)) { bytes = stream.readNBytes(65537); }
                if (bytes.length > 65536) throw new IOException("Grant document too large");
                var body = AuditDocuments.JSON.readTree(bytes);
                AuditDocuments.fields(body, "schemaVersion", "grants");
                if (!body.path("schemaVersion").isIntegralNumber() || body.path("schemaVersion").asInt() != 1
                        || !body.path("grants").isArray()) throw new IllegalArgumentException();
                var byAccount = new HashMap<String, List<MiniAppointmentReadController.Authorization>>();
                var ids = new java.util.HashSet<String>();
                for (var grant : body.path("grants")) {
                    AuditDocuments.fields(grant, "account", "authorizationId", "patientId", "kind", "permissions", "version");
                    String account = AuditDocuments.text(grant, "account");
                    if (!account.matches("WX:[a-f0-9]{64}|SYNTHETIC-[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
                    String id = AuditDocuments.text(grant, "authorizationId");
                    if (!ids.add(id) || !grant.path("permissions").isArray() || !grant.path("version").isIntegralNumber()
                            || !grant.path("version").canConvertToInt()) throw new IllegalArgumentException();
                    var permissions = new ArrayList<String>();
                    for (var permission : grant.path("permissions")) {
                        if (!permission.isTextual()) throw new IllegalArgumentException();
                        permissions.add(permission.asText());
                    }
                    String patient = AuditDocuments.text(grant, "patientId");
                    long patientId = Long.parseLong(patient);
                    if (!Long.toString(patientId).equals(patient)) throw new IllegalArgumentException();
                    byAccount.computeIfAbsent(account, ignored -> new ArrayList<>()).add(
                            new MiniAppointmentReadController.Authorization(id, patientId,
                                    MiniAppointmentReadController.Authorization.Kind.valueOf(AuditDocuments.text(grant, "kind")),
                                    permissions, grant.path("version").asInt()));
                }
                return new CourseMiniPatientScope(byAccount);
            } catch (IOException | RuntimeException failure) {
                throw new SQLException("COURSE_PATIENT_GRANTS_UNAVAILABLE", "58000");
            }
        }

        @Override public boolean mayRead(Connection c, String account, long patient) throws SQLException {
            return current().mayRead(c, account, patient);
        }

        @Override public List<MiniAppointmentReadController.Authorization> authorizations(Connection c, String account)
                throws SQLException {
            return current().authorizations(c, account);
        }
    }
}
