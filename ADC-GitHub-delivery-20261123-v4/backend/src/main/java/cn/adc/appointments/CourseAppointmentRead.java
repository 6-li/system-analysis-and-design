package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

@RestController
@Profile("course-demo")
@ConditionalOnProperty(name = "adc.course.appointment-read-enabled", havingValue = "true")
final class CourseAppointmentRead {
    private static final String RECEPTION = "SYNTHETIC reception";
    private final JdbcTransactions transactions;
    private final long clinic;

    @Autowired
    CourseAppointmentRead(Environment env) {
        this(env.getRequiredProperty("adc.course.clinic-id", Long.class), courseTransactions(env, false));
    }

    CourseAppointmentRead(long clinic, JdbcTransactions transactions) {
        if (clinic <= 0) throw new IllegalArgumentException("Course clinic must be specified");
        this.clinic = clinic;
        this.transactions = transactions;
    }

    static JdbcTransactions courseTransactions(Environment env, boolean writer) {
        return courseTransactions(env, writer ? "adc_course_booking_writer" : "adc_course_read",
                writer ? "ADC_COURSE_DB_WRITE_PASSWORD" : "ADC_COURSE_DB_READ_PASSWORD", !writer);
    }

    static JdbcTransactions courseTransactions(Environment env, String role, String passwordKey, boolean readOnly) {
        String database = env.getRequiredProperty("adc.course.database");
        int port = env.getRequiredProperty("adc.course.database-port", Integer.class);
        String password = env.getRequiredProperty(passwordKey);
        if (!database.matches("adc_course_[a-z0-9_]+") || port < 1024 || port > 65535
                || port == 5432 || password.isBlank()) {
            throw new IllegalArgumentException("Course appointments require a dedicated local database");
        }
        var source = new PGSimpleDataSource();
        source.setServerNames(new String[]{"127.0.0.1"});
        source.setPortNumbers(new int[]{port});
        source.setDatabaseName(database);
        source.setUser(role);
        source.setPassword(password);
        source.setOptions(readOnly ? "-c default_transaction_read_only=on -c timezone=UTC" : "-c timezone=UTC");
        source.setConnectTimeout(3);
        source.setSocketTimeout(10);
        return new JdbcTransactions(source);
    }

    @GetMapping("/api/v1/staff/appointments/{id}")
    ResponseEntity<JsonNode> detail(@PathVariable String id, HttpServletRequest request) {
        return read(id, request, true);
    }

    @GetMapping("/api/v1/staff/appointments/{id}/schedule-support")
    ResponseEntity<JsonNode> support(@PathVariable String id, HttpServletRequest request) {
        return read(id, request, false);
    }

    private ResponseEntity<JsonNode> read(String id, HttpServletRequest request, boolean detail) {
        var identity = identity(request);
        var subject = identity.current();
        if (subject.isEmpty() || !RECEPTION.equals(subject.get())) {
            return ResponseEntity.status(subject.isEmpty() ? 401 : 403).cacheControl(CacheControl.noStore()).build();
        }
        var auth = receptionAuthorization(identity, clinic);
        var adapter = new AppointmentController(null, new AppointmentReadService(transactions, auth, Clock.systemUTC()));
        var response = detail ? adapter.detail(clinic, id) : adapter.support(clinic, id);
        return ResponseEntity.status(response.status()).cacheControl(CacheControl.noStore()).body(response.body());
    }

    static StaffSessionIdentityAdapter identity(HttpServletRequest request) {
        var session = request.getSession(false);
        return new StaffSessionIdentityAdapter(() -> {
            var value = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
            return value instanceof StaffSessionIdentityAdapter.VerifiedSession verified ? verified : null;
        });
    }

    static StaffAuthorization receptionAuthorization(StaffSessionIdentityAdapter identity, long clinic) {
        var grant = new StaffAuthorization.Grant("course-read", Set.of("BOOKING_RECEPTION"),
                Set.of("UC02"), Set.of(clinic), false);
        return new StaffAuthorization(identity, () -> Map.of(RECEPTION, grant));
    }
}
