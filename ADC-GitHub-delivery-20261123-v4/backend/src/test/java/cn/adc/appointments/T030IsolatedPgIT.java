package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.config.DentistEligibilityProvider;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static cn.adc.appointments.T028IsolatedPgIT.IsolatedData;
import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;

class T030IsolatedPgIT {
    private static final String ACTOR = "SYNTHETIC-cancellation-reception";
    private static final long ORIGIN = 81001;
    private static final long OTHER = 81002;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
    private static final SourceStamp STAMP = new SourceStamp("SYNTHETIC","t030-v1","isolated",CLOCK.instant(),true);
    private static final AtomicLong AUDITS = new AtomicLong(39000);
    private final AppointmentRepository repository = new AppointmentRepository();
    private final StaffAuthorization authorization = new StaffAuthorization(() -> Optional.of(ACTOR),
            () -> Map.of(ACTOR,new StaffAuthorization.Grant("t030-v1",Set.of("BOOKING_RECEPTION"),
                    Set.of("UC02","UC04"),Set.of(ORIGIN,OTHER),false)));

    @Test void committedCancellationStopsReminderAndReleasesCapacityOnce() throws Exception {
        var db = new IsolatedData("runtime",0);
        var cancellation = cancellation(db,false);
        var booking = booking(db,cancellation);
        var original = appointment(db,901,ORIGIN);
        var denied = booking.book(ORIGIN,UUID.randomUUID(),bookingInput());
        assertEquals("APPOINTMENT_CAPACITY_CONFLICT",denied.reason());

        var request = cancelInput(original);
        var key = UUID.randomUUID();
        var receipt = cancellation.cancel(901,ORIGIN,key,request);
        assertEquals("SUCCEEDED",receipt.businessOutcome());
        var cancelled = appointment(db,901,ORIGIN);
        assertEquals("已取消",cancelled.status());
        assertEquals(original.terms().schedule(),cancelled.terms().schedule());
        assertEquals(original.terms().startsAt(),cancelled.terms().startsAt());
        assertEquals(original.terms().endsAt(),cancelled.terms().endsAt());
        assertNull(cancelled.noShow());
        var reminder = AuditDocuments.parse(cancelled.terms().reminder());
        assertEquals("STOPPED",reminder.path("state").asText());
        assertEquals(original.terms().reminder(),reminder.path("previousArrangement").asText());

        var same = cancellation.cancel(901,ORIGIN,key,request);
        var fresh = cancellation.cancel(901,ORIGIN,UUID.randomUUID(),request);
        for (var repeat : java.util.List.of(same,fresh)) {
            assertEquals("SUCCEEDED",repeat.businessOutcome());
            assertEquals(receipt.successEventId(),repeat.successEventId());
            assertTrue(repeat.existingResult());
        }
        assertEquals("SUCCEEDED",booking.book(ORIGIN,UUID.randomUUID(),bookingInput()).businessOutcome());
        assertEquals("1", scalar(db,"SELECT count(*) FROM adc.operation_audit_event WHERE operation_name='APPOINTMENT_CANCEL' AND outcome='SUCCESS' AND business_reference::jsonb->>'appointmentId'='901'"));
        assertEquals("0", scalar(db,"SELECT count(*) FROM adc.appointment_change_history WHERE appointment_id=901"));
    }

    @Test void auditFailurePreservesAppointmentReminderAndOccupiedResource() throws Exception {
        var db = new IsolatedData("runtime",0);
        var original = appointment(db,902,OTHER);
        var cancellation = cancellation(db,true);
        var receipt = cancellation.cancel(902,OTHER,UUID.randomUUID(),cancelInput(original));
        assertEquals("FAILED",receipt.businessOutcome());
        assertEquals("CONFIRMED",receipt.auditPersistence().name());
        assertEquals(original,appointment(db,902,OTHER));
        try (var c = db.getConnection()) {
            assertTrue(repository.overlaps(c,79001,original.terms().startsAt(),original.terms().endsAt())
                    .stream().anyMatch(overlap -> overlap.appointmentId()==902 && "待到诊".equals(overlap.status())));
        }
        assertEquals("STARTED,FAILED",audit(db,receipt.requestReference()));
    }

    @Test void lostCommitReplyRecoversOriginalCancellationAndRejectsChangedPayload() throws Exception {
        var db = new IsolatedData("runtime",0);
        var original = appointment(db,903,ORIGIN);
        var service = cancellation(new IsolatedData("runtime",2),false);
        var request = cancelInput(original);
        var key = UUID.randomUUID();
        var unknown = service.cancel(903,ORIGIN,key,request);
        assertEquals("UNKNOWN",unknown.businessOutcome());
        assertEquals("STARTED,SUCCESS",audit(db,unknown.requestReference()));
        var recovered = service.recover(903,ORIGIN,key,request);
        assertEquals("SUCCEEDED",recovered.businessOutcome());
        assertEquals("903",recovered.result().path("appointmentId").asText());
        assertEquals("REQUEST_PAYLOAD_CONFLICT",service.cancel(903,ORIGIN,key,
                request.deepCopy().put("cancellationVerification","different")).reason());
        assertEquals(recovered.successEventId(),service.cancel(903,ORIGIN,key,request).successEventId());
        assertEquals("0",scalar(db,"SELECT count(*) FROM adc.appointment_change_history WHERE appointment_id=903"));
    }

    private AppointmentCancellationService cancellation(IsolatedData db, boolean failAudit) {
        long first = AUDITS.incrementAndGet();
        var calls = new AtomicInteger();
        return new AppointmentCancellationService(new JdbcTransactions(db),authorization,
                (c,row,submitted) -> "SYNTHETIC request".equals(submitted) ? Optional.of("SYNTHETIC verified") : Optional.empty(),
                c -> switch(calls.getAndIncrement()) {
                    case 0 -> first;
                    case 1 -> failAudit ? first : AUDITS.incrementAndGet();
                    default -> AUDITS.incrementAndGet();
                },CLOCK,repository);
    }

    private FormalBookingService booking(IsolatedData db, AppointmentCancellationService cancellation) {
        var tx = new JdbcTransactions(db);
        var start = Instant.parse("2026-11-08T09:00:00Z");
        var scheme = new InitialBookingReview.AcceptedScheme(88001,new Version(87001,2),ORIGIN,79001,
                start,start.plusSeconds(1620),"SYNTHETIC request",new Basis(85001,1,27));
        var evidence = new BookingAcceptanceVerifier((c,ref) -> Optional.of(new BookingAcceptanceVerifier.Confirmation(
                "SYNTHETIC acceptance",STAMP,"SYNTHETIC patient",null,CLOCK.instant(),null,scheme)),
                new BookingAcceptanceVerifier.ApplicantAuthority() {
                    public boolean isPatient(Connection c,String subject,long patient) { return patient==88001 && "SYNTHETIC patient".equals(subject); }
                    public boolean mayAcceptBooking(Connection c,String subject,InitialBookingReview.AcceptedScheme s,String grant) { return false; }
                },CLOCK);
        var review = new InitialBookingReview(tx,authorization,
                id -> new ClinicDirectoryProvider.Clinic(id,"SYNTHETIC",ZoneId.of("Etc/UTC"),STAMP),evidence);
        return new FormalBookingService(tx,authorization,review,
                (dentist,clinic) -> new DentistEligibilityProvider.Eligibility(dentist,clinic,"SYNTHETIC",STAMP),
                c -> 1301,c -> AUDITS.incrementAndGet(),(c,s,coord,source) -> "SYNTHETIC reminder",
                cancellation::confirmsRelease,CLOCK);
    }

    private static ObjectNode bookingInput() {
        var input = JSON.createObjectNode().put("patientId","88001").put("acceptanceEvidenceReference","SYNTHETIC acceptance");
        var scheme = input.putObject("scheme").put("startsAt","2026-11-08T09:00:00Z")
                .put("endsAt","2026-11-08T09:27:00Z").put("treatmentRequest","SYNTHETIC request");
        scheme.putObject("effectiveScheduleRef").put("scheduleId","87001").put("versionNo",2);
        scheme.putObject("durationBasis").put("schemaVersion",1).put("basisMode","INITIAL_CURRENT")
                .put("primaryProjectId","85001").put("projectVersion",1).put("adoptedDurationMinutes",27);
        return input;
    }

    private static ObjectNode cancelInput(AppointmentRepository.Appointment row) {
        return JSON.createObjectNode().put("expectedAppointmentToken",AppointmentReadService.token(row))
                .put("cancellationVerification","SYNTHETIC request");
    }

    private AppointmentRepository.Appointment appointment(IsolatedData db,long id,long clinic) throws Exception {
        try (var c=db.getConnection()) {
            return repository.findAppointment(c,id,clinic).orElseThrow();
        }
    }

    private static String audit(IsolatedData db,String reference) throws Exception {
        try (var c=db.getConnection(); var s=c.prepareStatement(
                "SELECT string_agg(outcome,',' ORDER BY event_sequence_no) FROM adc.operation_audit_event WHERE request_reference=?")) {
            s.setString(1,reference);
            try (var r=s.executeQuery()) { assertTrue(r.next()); return r.getString(1); }
        }
    }

    private static String scalar(IsolatedData db,String sql) throws Exception {
        try (var c=db.getConnection(); var s=c.createStatement(); var r=s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }
}
