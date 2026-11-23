package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.config.DentistEligibilityProvider;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.ClinicTimePolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static cn.adc.appointments.T028IsolatedPgIT.IsolatedData;
import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;

class T029IsolatedPgIT {
    private static final long ORIGIN = 81001;
    private static final long TARGET = 81002;
    private static final Version OLD_VERSION = new Version(87001, 2);
    private static final Version NEW_VERSION = new Version(87002, 1);
    private static final String ACTOR = "SYNTHETIC-change-reception";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
    private static final SourceStamp STAMP = new SourceStamp("SYNTHETIC", "t029-v1", "isolated", CLOCK.instant(), true);
    private static final Basis BASIS = new Basis(85001, 1, 27);
    private static final AtomicLong AUDIT = new AtomicLong(19000);
    private static final AtomicLong HISTORY = new AtomicLong(29000);
    private final AppointmentRepository repository = new AppointmentRepository();
    private final StaffAuthorization authorization = new StaffAuthorization(() -> Optional.of(ACTOR),
            () -> Map.of(ACTOR, new StaffAuthorization.Grant("t029-v1", Set.of("BOOKING_RECEPTION"),
                    Set.of("UC03"), Set.of(ORIGIN,TARGET), false)));

    @Test void concurrentChangesSerializeAndOnlyOneAppendsHistory() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var row = seed(db, 1101, "10:00:00");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = service(db, false, (c, old, target) -> {
            entered.countDown();
            try {
                if (!release.await(6, TimeUnit.SECONDS)) throw new SQLException("synthetic gate timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
            return "SYNTHETIC changed reminder";
        });
        var second = service(db, false, T029IsolatedPgIT::reminder);
        try (var observer = db.getConnection(); var pool = Executors.newFixedThreadPool(2)) {
            var winner = pool.submit(() -> first.change(row.id(), ORIGIN, TARGET, UUID.randomUUID(), input(row,"13:00:00")));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var contender = pool.submit(() -> second.change(row.id(), ORIGIN, TARGET, UUID.randomUUID(), input(row,"15:00:00")));
                awaitLockWait(observer);
                release.countDown();
                assertEquals("SUCCEEDED", winner.get(8, TimeUnit.SECONDS).businessOutcome());
                assertEquals("STALE_APPOINTMENT", contender.get(8, TimeUnit.SECONDS).reason());
            } finally {
                release.countDown();
            }
        }
        assertEquals("1", scalar(db, "SELECT count(*) FROM adc.appointment_change_history WHERE appointment_id=1101"));
        try (var c = db.getConnection()) {
            assertTrue(repository.findAppointment(c, row.id(), ORIGIN).isEmpty());
            var current = repository.findAppointment(c, row.id(), TARGET).orElseThrow();
            assertEquals(time("13:00:00"), current.terms().startsAt());
            assertEquals("SYNTHETIC changed reminder", current.terms().reminder());
        }
    }

    @Test void auditCollisionRollsBackAppointmentReminderAndHistory() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var row = seed(db, 1102, "10:35:00");
        var service = service(db, true, T029IsolatedPgIT::reminder);
        var receipt = service.change(row.id(), ORIGIN, TARGET, UUID.randomUUID(), input(row, "13:35:00"));
        assertEquals("FAILED", receipt.businessOutcome());
        assertEquals("CONFIRMED", receipt.auditPersistence().name());
        try (var c = db.getConnection()) {
            assertEquals(row, repository.findAppointment(c, row.id(), ORIGIN).orElseThrow());
        }
        assertEquals("0", scalar(db, "SELECT count(*) FROM adc.appointment_change_history WHERE appointment_id=1102"));
        assertEquals("STARTED,FAILED", audit(db, receipt.requestReference()));
    }

    @Test void committedChangeWithLostReplyRecoversFromBothTablesWithoutReplay() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var row = seed(db, 1103, "11:10:00");
        var service = service(new IsolatedData("runtime", 2), false, T029IsolatedPgIT::reminder);
        var payload = input(row, "14:10:00");
        var key = UUID.randomUUID();
        var unknown = service.change(row.id(), ORIGIN, TARGET, key, payload);
        assertEquals("UNKNOWN", unknown.businessOutcome());
        assertEquals("STARTED,SUCCESS", audit(db, unknown.requestReference()));
        var found = service.recover(row.id(), ORIGIN, TARGET, key, payload);
        assertEquals("SUCCEEDED", found.businessOutcome());
        assertEquals("1103", found.result().path("appointmentId").asText());
        var duplicate = service.change(row.id(), ORIGIN, TARGET, key, payload);
        assertEquals(found.result(), duplicate.result());
        assertEquals("REQUEST_PAYLOAD_CONFLICT", service.change(row.id(), ORIGIN, TARGET, key,
                payload.deepCopy().put("changeBasis", "SYNTHETIC different")).reason());
        assertEquals("1", scalar(db, "SELECT count(*) FROM adc.appointment_change_history WHERE appointment_id=1103"));
        try (var c = db.getConnection()) {
            var history = repository.findChange(c, row.id(), found.result().path("appointmentChangeId").asLong()).orElseThrow();
            assertEquals(row.terms(), history.before());
            assertEquals(time("14:10:00"), history.after().startsAt());
            assertEquals(unknown.requestReference(), history.evidence().requestReference());
        }
    }

    private AppointmentRepository.Appointment seed(IsolatedData db, long id, String start) {
        var old = scheme(ORIGIN, OLD_VERSION, time(start));
        var zone = new ClinicTimePolicy("t029-v1", Map.of(ORIGIN,"Etc/UTC"));
        var interval = zone.resolve(ORIGIN, time(start).atOffset(ZoneOffset.UTC).toLocalDateTime().toString(), ZoneOffset.UTC, 27);
        var terms = new AppointmentRepository.Terms(OLD_VERSION, old.startsAt(), old.endsAt(),
                FormalBookingService.freeze(old, "SYNTHETIC project", interval, "INITIAL_CURRENT"), "SYNTHETIC original reminder");
        var appointment = new AppointmentRepository.Appointment(id,88001,terms,"SYNTHETIC original acceptance",
                "待到诊",null,null,ACTOR,CLOCK.instant(),null,null);
        return new JdbcTransactions(db).execute(c -> {
            repository.insertAppointment(c, appointment);
            return appointment;
        });
    }

    private AppointmentChangeService service(IsolatedData db, boolean failAudit, AppointmentChangeService.ReminderPreparation reminders) {
        var acceptance = new BookingAcceptanceVerifier((c,reference) -> {
            if (!reference.startsWith("SYNTHETIC-")) return Optional.empty();
            return Optional.of(new BookingAcceptanceVerifier.Confirmation(reference,STAMP,"SYNTHETIC patient",null,
                    CLOCK.instant(),null,scheme(TARGET, NEW_VERSION, time(reference.substring("SYNTHETIC-".length())))));
        }, new BookingAcceptanceVerifier.ApplicantAuthority() {
            public boolean isPatient(Connection c, String applicant, long patient) { return patient == 88001 && applicant.equals("SYNTHETIC patient"); }
            public boolean mayAcceptBooking(Connection c, String applicant, InitialBookingReview.AcceptedScheme scheme, String grant) { return false; }
        }, CLOCK);
        long firstAudit = AUDIT.incrementAndGet();
        var calls = new AtomicInteger();
        return new AppointmentChangeService(new JdbcTransactions(db), authorization,
                clinic -> new ClinicDirectoryProvider.Clinic(clinic,"SYNTHETIC",ZoneId.of("Etc/UTC"),STAMP),
                (dentist,clinic) -> new DentistEligibilityProvider.Eligibility(dentist,clinic,"SYNTHETIC",STAMP),
                acceptance, c -> HISTORY.incrementAndGet(), c -> switch (calls.getAndIncrement()) {
                    case 0 -> firstAudit;
                    case 1 -> failAudit ? firstAudit : AUDIT.incrementAndGet();
                    default -> AUDIT.incrementAndGet();
                }, reminders, (c,overlap) -> false, CLOCK);
    }

    private static ObjectNode input(AppointmentRepository.Appointment row, String start) {
        var target = scheme(TARGET, NEW_VERSION, time(start));
        var input = JSON.createObjectNode().put("expectedAppointmentToken",AppointmentReadService.token(row))
                .put("acceptanceEvidenceReference","SYNTHETIC-"+start).put("changeBasis","SYNTHETIC changed time");
        var scheme = input.putObject("scheme").put("startsAt", target.startsAt().toString())
                .put("endsAt",target.endsAt().toString()).put("treatmentRequest",target.treatmentRequest());
        scheme.putObject("effectiveScheduleRef").put("scheduleId","87002").put("versionNo",1);
        scheme.putObject("durationBasis").put("schemaVersion",1).put("basisMode","PRESERVED_ORIGINAL")
                .put("primaryProjectId","85001").put("projectVersion",1).put("adoptedDurationMinutes",27);
        return input;
    }

    private static InitialBookingReview.AcceptedScheme scheme(long clinic, Version version, Instant start) {
        return new InitialBookingReview.AcceptedScheme(88001,version,clinic,79001,start,start.plusSeconds(1620),
                "SYNTHETIC request",BASIS);
    }

    private static Instant time(String clock) { return Instant.parse("2026-11-08T"+clock+"Z"); }

    private static String reminder(Connection c, AppointmentRepository.Appointment old, InitialBookingReview.AcceptedScheme target) {
        return "SYNTHETIC changed reminder";
    }

    private static void awaitLockWait(Connection c) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        do {
            try (var s=c.createStatement(); var r=s.executeQuery(
                    "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype='advisory' AND NOT granted AND mode='ExclusiveLock')")) {
                assertTrue(r.next());
                if (r.getBoolean(1)) return;
            }
            Thread.sleep(20);
        } while (System.nanoTime()<deadline);
        fail("No competing request waiting for an advisory lock");
    }

    private static String scalar(IsolatedData db, String sql) throws SQLException {
        try (var c=db.getConnection(); var s=c.createStatement(); var r=s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }

    private static String audit(IsolatedData db, String reference) throws SQLException {
        try (var c=db.getConnection(); var s=c.prepareStatement(
                "SELECT string_agg(outcome,',' ORDER BY event_sequence_no) FROM adc.operation_audit_event WHERE request_reference=?")) {
            s.setString(1,reference);
            try (var r=s.executeQuery()) { assertTrue(r.next()); return r.getString(1); }
        }
    }
}
