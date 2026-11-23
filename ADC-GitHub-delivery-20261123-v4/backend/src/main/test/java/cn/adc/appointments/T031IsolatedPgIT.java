package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static cn.adc.appointments.ArrivalNoShowService.Action.ARRIVAL;
import static cn.adc.appointments.ArrivalNoShowService.Action.NO_SHOW;
import static cn.adc.appointments.T028IsolatedPgIT.IsolatedData;
import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;

class T031IsolatedPgIT {
    private static final long CLINIC = 81001;
    private static final long OTHER_CLINIC = 81002;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T12:27:00Z"), ZoneOffset.UTC);
    private static final AtomicLong AUDIT = new AtomicLong(41000);
    private static final AtomicLong VISITS = new AtomicLong(51000);
    private final AppointmentRepository repository = new AppointmentRepository();
    private final StaffAuthorization reception = new StaffAuthorization(() -> Optional.of("SYNTHETIC clinic reception"),
            () -> Map.of("SYNTHETIC clinic reception",new StaffAuthorization.Grant("t031-v1",Set.of("CLINIC_RECEPTION"),
                    Set.of("UC05","UC06"),Set.of(CLINIC,OTHER_CLINIC),false)));
    private final StaffAuthorization booking = new StaffAuthorization(() -> Optional.of("SYNTHETIC booking reception"),
            () -> Map.of("SYNTHETIC booking reception",new StaffAuthorization.Grant("t031-v1",Set.of("BOOKING_RECEPTION"),
                    Set.of("UC04"),Set.of(CLINIC),false)));

    @Test void arrivalWinsAgainstConcurrentNoShowAndCancellation() throws Exception {
        var db = new IsolatedData("runtime",0);
        var initial = appointment(db,901,CLINIC);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var arrival = service(db,false,(c,row,finding) -> {
            entered.countDown();
            try {
                if (!release.await(5,TimeUnit.SECONDS)) throw new SQLException("test barrier timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
            return verified(c,row,finding);
        });
        var noShow = service(db,false,T031IsolatedPgIT::verified);
        var cancellation = new AppointmentCancellationService(new JdbcTransactions(db),booking,
                (c,row,ref) -> Optional.of("SYNTHETIC cancellation"),c -> AUDIT.incrementAndGet(),CLOCK,repository);
        var cancel = JSON.createObjectNode().put("expectedAppointmentToken",AppointmentReadService.token(initial))
                .put("cancellationVerification","SYNTHETIC submitted");
        try (var observer = db.getConnection(); var pool = Executors.newFixedThreadPool(3)) {
            var winner = pool.submit(() -> arrival.submit(ARRIVAL,901,CLINIC,UUID.randomUUID(),input(initial,"ARRIVAL_VERIFIED",CLINIC)));
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                var opposing = pool.submit(() -> noShow.submit(NO_SHOW,901,CLINIC,UUID.randomUUID(),input(initial,"NO_SHOW_VERIFIED",CLINIC)));
                var cancelling = pool.submit(() -> cancellation.cancel(901,CLINIC,UUID.randomUUID(),cancel));
                awaitWaiters(observer);
                release.countDown();
                assertEquals("SUCCEEDED",winner.get(8,TimeUnit.SECONDS).businessOutcome());
                assertEquals("ATTENDANCE_FACT_CONFLICT",opposing.get(8,TimeUnit.SECONDS).reason());
                assertTrue(Set.of("STALE_APPOINTMENT","APPOINTMENT_NOT_CANCELLABLE")
                        .contains(cancelling.get(8,TimeUnit.SECONDS).reason()));
            } finally {
                release.countDown();
            }
        }
        var result = appointment(db,901,CLINIC);
        assertEquals("已到诊",result.status());
        assertNull(result.noShow());
        assertNull(result.cancellation());
        assertEquals("1",count(db,"SELECT count(*) FROM adc.visit WHERE appointment_id=901"));
        assertEquals("1",count(db,"SELECT count(*) FROM adc.operation_audit_event WHERE outcome='SUCCESS' AND business_reference::jsonb->>'appointmentId'='901'"));
    }

    @Test void pendingAndConfirmedNoShowAtExactEndNeverCreateAVisit() throws Exception {
        var db = new IsolatedData("runtime",0);
        var svc = service(db,false,T031IsolatedPgIT::verified);
        var initial = appointment(db,902,OTHER_CLINIC);
        assertEquals(CLOCK.instant(),initial.terms().endsAt());
        var pending = svc.submit(NO_SHOW,902,OTHER_CLINIC,UUID.randomUUID(),input(initial,"PENDING_VERIFICATION",OTHER_CLINIC));
        assertEquals("PERSISTED_INCOMPLETE",AppointmentController.attendanceResponse(NO_SHOW,pending).body().path("status").asText());
        var waiting = appointment(db,902,OTHER_CLINIC);
        assertEquals("待核实",waiting.status());
        assertNull(waiting.noShow());
        var result = svc.submit(NO_SHOW,902,OTHER_CLINIC,UUID.randomUUID(),input(waiting,"NO_SHOW_VERIFIED",OTHER_CLINIC));
        assertEquals("SUCCEEDED",result.businessOutcome());
        var noShow = appointment(db,902,OTHER_CLINIC);
        assertEquals("已确认未到诊",noShow.status());
        assertNotNull(noShow.noShow());
        assertEquals("0",count(db,"SELECT count(*) FROM adc.visit WHERE appointment_id=902"));
        assertEquals("ATTENDANCE_FACT_CONFLICT",svc.submit(ARRIVAL,902,OTHER_CLINIC,UUID.randomUUID(),
                input(noShow,"ARRIVAL_VERIFIED",OTHER_CLINIC)).reason());
    }

    @Test void failedAuditRollsBackBothAppointmentAndVisit() throws Exception {
        var db = new IsolatedData("runtime",0);
        var initial = appointment(db,903,CLINIC);
        var receipt = service(db,true,T031IsolatedPgIT::verified).submit(ARRIVAL,903,CLINIC,UUID.randomUUID(),
                input(initial,"ARRIVAL_VERIFIED",CLINIC));
        assertEquals("FAILED",receipt.businessOutcome());
        assertEquals(initial,appointment(db,903,CLINIC));
        assertEquals("0",count(db,"SELECT count(*) FROM adc.visit WHERE appointment_id=903"));
        assertEquals("STARTED,FAILED",audit(db,receipt.requestReference()));
    }

    @Test void lostCommitReplyRecoversExactVisitAndRejectsChangedPayload() throws Exception {
        var db = new IsolatedData("runtime",0);
        var initial = appointment(db,904,CLINIC);
        var svc = service(new IsolatedData("runtime",2),false,T031IsolatedPgIT::verified);
        var key = UUID.randomUUID();
        var input = input(initial,"ARRIVAL_VERIFIED",CLINIC);
        var unknown = svc.submit(ARRIVAL,904,CLINIC,key,input);
        assertEquals("UNKNOWN",unknown.businessOutcome());
        assertEquals("STARTED,SUCCESS",audit(db,unknown.requestReference()));
        var found = svc.recover(ARRIVAL,904,CLINIC,key,input);
        assertEquals("SUCCEEDED",found.businessOutcome());
        try (var c = db.getConnection()) {
            var visit = repository.findVisit(c,appointment(db,904,CLINIC)).orElseThrow();
            assertEquals(Long.toString(visit.id()),found.result().path("visitId").asText());
            assertNull(visit.arrivedAt());
        }
        assertEquals("REQUEST_PAYLOAD_CONFLICT",svc.submit(ARRIVAL,904,CLINIC,key,
                input.deepCopy().put("verificationBasis","SYNTHETIC changed")).reason());
        assertEquals(found.successEventId(),svc.submit(ARRIVAL,904,CLINIC,UUID.randomUUID(),input).successEventId());
        assertEquals("1",count(db,"SELECT count(*) FROM adc.visit WHERE appointment_id=904"));
    }

    private ArrivalNoShowService service(IsolatedData db, boolean failAudit, ArrivalNoShowService.Verification verification) {
        var first = AUDIT.incrementAndGet();
        var calls = new AtomicInteger();
        return new ArrivalNoShowService(new JdbcTransactions(db),reception,repository,verification,
                c -> VISITS.incrementAndGet(),c -> switch (calls.getAndIncrement()) {
                    case 0 -> first;
                    case 1 -> failAudit ? first : AUDIT.incrementAndGet();
                    default -> AUDIT.incrementAndGet();
                },CLOCK);
    }

    private static Optional<ArrivalNoShowService.Verified> verified(Connection c, AppointmentRepository.Appointment row,
                                                                  ArrivalNoShowService.Finding finding) {
        return "SYNTHETIC human evidence".equals(finding.basis())
                ? Optional.of(new ArrivalNoShowService.Verified("SYNTHETIC verified",null)) : Optional.empty();
    }

    private static ObjectNode input(AppointmentRepository.Appointment row,String finding,long clinic) {
        var input = JSON.createObjectNode().put("expectedAppointmentToken",AppointmentReadService.token(row))
                .put("finding",finding).put("verificationBasis","SYNTHETIC human evidence");
        if ("ARRIVAL_VERIFIED".equals(finding)) input.put("actualClinicId",Long.toString(clinic))
                .put("actualDentistId","79001").put("verifiedIdentityReference","SYNTHETIC identity");
        return input;
    }

    private static void awaitWaiters(Connection connection) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1600);
        do {
            try (var s=connection.createStatement(); var r=s.executeQuery(
                    "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted AND mode='ExclusiveLock'")) {
                assertTrue(r.next());
                if (r.getInt(1)==2) return;
            }
            Thread.sleep(20);
        } while (System.nanoTime()<deadline);
        fail("Expected both competing commands to wait");
    }

    private AppointmentRepository.Appointment appointment(IsolatedData db,long id,long clinic) throws SQLException {
        try (var c=db.getConnection()) {
            return repository.findAppointment(c,id,clinic).orElseThrow();
        }
    }

    private static String count(IsolatedData db,String sql) throws SQLException {
        try (var c=db.getConnection(); var s=c.createStatement(); var r=s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }

    private static String audit(IsolatedData db,String reference) throws SQLException {
        try (var c=db.getConnection(); var s=c.prepareStatement(
                "SELECT string_agg(outcome,',' ORDER BY event_sequence_no) FROM adc.operation_audit_event WHERE request_reference=?")) {
            s.setString(1,reference);
            try (var r=s.executeQuery()) { assertTrue(r.next()); return r.getString(1); }
        }
    }
}
