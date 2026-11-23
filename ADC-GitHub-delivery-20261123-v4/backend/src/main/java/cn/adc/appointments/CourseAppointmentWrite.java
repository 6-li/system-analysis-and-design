package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.config.ConfigurationUnavailable;
import cn.adc.shared.config.DentistEligibilityProvider;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

/** Isolated classroom write probe. Its fixture evidence is deliberately scoped to one scheme. */
@RestController
@Profile("course-demo")
@ConditionalOnProperty(name = {"adc.course.appointment-read-enabled", "adc.course.appointment-write-validation"},
        havingValue = "true")
final class CourseAppointmentWrite {
    private static final long CLINIC = 81001L;
    private static final String ACCEPTANCE = "SYNTHETIC-course-booking-acceptance";
    private static final String PATIENT = "SYNTHETIC course patient";
    private static final InitialBookingReview.AcceptedScheme SCHEME = new InitialBookingReview.AcceptedScheme(
            88001, new Version(87001, 2), CLINIC, 79001,
            Instant.parse("2026-11-08T10:30:00Z"), Instant.parse("2026-11-08T10:57:00Z"),
            "SYNTHETIC treatment", new Basis(85001, 1, 27));
    private final JdbcTransactions transactions;
    private final InitialBookingReview.AcceptedScheme acceptedScheme;
    private final boolean miniSourceEnabled;
    private final boolean patientAccounts;
    private final boolean interactive;
    private final String clinicZone;
    private final Set<Long> configuredDentists;
    private final Clock clock = Clock.systemUTC();

    CourseAppointmentWrite(Environment env) {
        patientAccounts=env.getProperty("adc.course.patient-accounts-enabled",Boolean.class,false);
        cn.adc.shared.config.CoursePublicOrigin.require(env.getRequiredProperty("adc.course.public-origin"));
        if (!"adc_course_validation".equals(env.getRequiredProperty("adc.course.database"))
                || env.getRequiredProperty("adc.course.clinic-id", Long.class) != CLINIC) {
            throw new IllegalArgumentException("Course booking writer is restricted to the isolated validation fixture");
        }
        transactions = CourseAppointmentRead.courseTransactions(env, true);
        interactive=env.getProperty("adc.course.booking-interaction-enabled",Boolean.class,false);
        clinicZone=interactive?env.getRequiredProperty("adc.course.clinic-zone"):"Etc/UTC";ZoneId.of(clinicZone);
        configuredDentists=interactive?java.util.Arrays.stream(env.getRequiredProperty("adc.course.synthetic-dentist-ids").split(",")).map(String::trim).map(Long::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet()):Set.of(SCHEME.dentistId());
        long patient = env.getProperty("adc.course.accepted-synthetic-patient-id", Long.class, SCHEME.patientId());
        if (patient <= 0) throw new IllegalArgumentException("Synthetic accepted patient must be positive");
        acceptedScheme = new InitialBookingReview.AcceptedScheme(patient, SCHEME.schedule(), SCHEME.clinicId(),
                SCHEME.dentistId(), SCHEME.startsAt(), SCHEME.endsAt(), SCHEME.treatmentRequest(), SCHEME.duration());
        miniSourceEnabled = env.getProperty("adc.course.booking-mini-source-validation", Boolean.class, false);
    }

    @PostMapping("/api/v1/staff/appointments")
    ResponseEntity<JsonNode> create(HttpServletRequest request, @RequestHeader("X-CSRF-Token") String csrf,
                                   @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode payload) {
        var identity = CourseAppointmentRead.identity(request);
        var auth = CourseAppointmentRead.receptionAuthorization(identity, CLINIC);
        try {
            auth.require("UC02", Set.of(CLINIC));
            identity.requireCsrf(csrf);
        } catch (SecurityException denied) {
            return ResponseEntity.status(identity.current().isEmpty() ? 401 : 403).cacheControl(CacheControl.noStore()).build();
        }
        var stamp = new SourceStamp("SYNTHETIC", "course-booking-validation", "fixture",
                Instant.parse("2026-11-01T00:00:00Z"), true);
        var legacyVerifier = new BookingAcceptanceVerifier((connection, reference) -> ACCEPTANCE.equals(reference)
                ? Optional.of(new BookingAcceptanceVerifier.Confirmation(reference, stamp, PATIENT, null,
                        stamp.verifiedAt(), null, acceptedScheme)) : Optional.empty(), new BookingAcceptanceVerifier.ApplicantAuthority() {
            @Override public boolean isPatient(Connection c, String subject, long patientId) {
                return PATIENT.equals(subject) && patientId == acceptedScheme.patientId();
            }
            @Override public boolean mayAcceptBooking(Connection c, String subject,
                                                       InitialBookingReview.AcceptedScheme scheme, String grant) {
                return false;
            }
        }, clock);
        var verifier=interactive?BookingInteractionController.verifier(request):legacyVerifier;
        var review = new InitialBookingReview(transactions, auth, clinic -> {
            if (clinic != CLINIC) throw new ConfigurationUnavailable();
            return new ClinicDirectoryProvider.Clinic(clinic, "SYNTHETIC", ZoneId.of(clinicZone), stamp);
        }, verifier);
        var service = new FormalBookingService(transactions, auth, review, (dentist, clinic) -> {
            if (!configuredDentists.contains(dentist) || clinic != CLINIC) throw new ConfigurationUnavailable();
            return new DentistEligibilityProvider.Eligibility(dentist, clinic, "SYNTHETIC qualification", stamp);
        }, CourseBookingIds::appointment, CourseBookingIds::audit, (c, scheme, coordination, source) -> {
            if (coordination != null || source != null && !miniSourceEnabled) throw new Rejection("COURSE_FIXTURE_SCHEME_REQUIRED");
            return "SYNTHETIC reminder";
        }, CommittedBookingRelease::confirms, clock, miniSourceEnabled
                ? new JdbcMiniBookingSource(new CourseMiniBookingContext().withPatientAccounts(patientAccounts), clock) : null);
        var response = interactive?BookingInteractionController.serialized(request,()->new AppointmentController(service).create(CLINIC,key,payload)):new AppointmentController(service).create(CLINIC,key,payload);
        return ResponseEntity.status(response.status()).cacheControl(CacheControl.noStore()).body(response.body());
    }
}
