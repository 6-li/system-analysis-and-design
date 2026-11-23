package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.shared.audit.CommandRecovery.Receipt;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.config.DentistEligibilityProvider;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FormalBookingServiceTest {
    static final long CLINIC = 81001;
    static final long DENTIST = 79001;
    static final long PATIENT = 88001;
    static final long SCHEDULE = 87001;
    static final long PROJECT = 85001;
    static final String ACTOR = "SYNTHETIC-reception";
    static final UUID KEY = UUID.fromString("76c45f5b-6214-491b-966e-c3c82cb0034e");
    static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    static final Instant START = Instant.parse("2026-11-08T09:00:00.123456Z");
    static final Instant END = START.plusSeconds(1620);
    static final SourceStamp STAMP = new SourceStamp("SYNTHETIC", "fixture-1", "test-only", NOW, true);
    final List<String> calls = new ArrayList<>();
    final List<Map<Integer,Object>> journal = new ArrayList<>();
    Map<Integer,Object> appointment;
    int auditId = 96000;
    int allocations;
    int version = 2;
    int commits;
    long clinicAfterLock = CLINIC;
    boolean allow = true;
    boolean occupies;
    boolean hasVisit;
    boolean releaseProven;
    String overlapStatus = "待到诊";
    boolean representative;
    boolean revokeInPreparation;
    boolean failSuccessAudit;
    boolean failFailureAudit;
    int loseCommit;
    boolean commitActuallySaved = true;

    @Test void rejectsMiniSourceWhenTrustedAdapterIsMissing() throws Exception {
        var proposal = input().put("sourceContextReference", "SYNTHETIC-source");
        assertEquals("VERIFIED_MINIPROGRAM_BOOKING_UNAVAILABLE", service().book(CLINIC, KEY, proposal).reason());
        assertNoSuccess();
    }

    @Test void rollsBackBookingWhenMiniResultLinkCannotBeWritten() throws Exception {
        var source = mock(FormalBookingService.MiniSource.class);
        doThrow(new SQLException("SYNTHETIC extension failure", "23514")).when(source)
                .linkCommittedResult(any(), eq("SYNTHETIC-source"), any(), anyString());
        service(source).book(CLINIC, KEY, input().put("sourceContextReference", "SYNTHETIC-source"));
        assertTrue(calls.contains("insert-29"));
        assertNoSuccess();
    }

    @Test void recoversMiniBookingOnlyWhenCommittedSourceLinkMatches() throws Exception {
        var source = mock(FormalBookingService.MiniSource.class);
        var service = service(source);
        var proposal = input().put("sourceContextReference", "SYNTHETIC-source");
        var result = service.book(CLINIC, KEY, proposal);
        assertEquals("SUCCEEDED", result.businessOutcome());
        verify(source).requireCurrent(any(), eq("SYNTHETIC-source"), any());
        verify(source).linkCommittedResult(any(), eq("SYNTHETIC-source"), any(), eq(result.requestReference()));
        assertNotEquals("SUCCEEDED", service.recover(CLINIC, KEY, proposal).businessOutcome());
        when(source.matchesCommitted(any(), eq("SYNTHETIC-source"), eq(91001L),
                eq(result.requestReference()))).thenReturn(true);
        assertEquals("SUCCEEDED", service.recover(CLINIC, KEY, proposal).businessOutcome());
        assertEquals(1, allocations);
    }

    @Test void createsFrozenBookingAndAuditsInOneTransaction() throws Exception {
        var r = service().book(CLINIC, KEY, input());
        assertEquals("SUCCEEDED", r.businessOutcome());
        assertEquals("91001", r.result().path("appointmentId").asText());
        assertEquals(List.of("STARTED", "SUCCESS"), outcomes());
        assertOrder("L1", "L2", "CURRENT", "L3", "L4", "L5", "schedule-read-locked", "acceptance",
                "eligibility", "capacity", "preparation", "insert-29", "audit-SUCCESS");
        assertEquals("待到诊", appointment.get(9));
        assertEquals("SYNTHETIC-reminder-arrangement", appointment.get(11));
        var doc = JSON.readTree((String) appointment.get(7));
        assertEquals("SYNTHETIC-cleaning", doc.at("/bookingDurationBasis/projectNameSnapshot").asText());
        assertEquals(2, doc.at("/bookingDurationBasis/projectVersion").intValue());
        assertEquals(START, ((OffsetDateTime) appointment.get(5)).toInstant());
        assertEquals(r.requestReference(), JSON.readTree((String) appointment.get(8)).path("requestReference").asText());
        assertNull(appointment.get(15));
        assertEquals(2, commits);
    }

    @Test void freezesVerifiedRepresentativeAuthority() throws Exception {
        representative = true;
        assertEquals("SUCCEEDED", service().book(CLINIC, KEY, input()).businessOutcome());
        assertEquals("SYNTHETIC-proxy-grant", appointment.get(15));
    }

    @Test void staleSameMinutesVersionNeverWritesBooking() throws Exception {
        version = 3;
        assertEquals("STALE_DURATION_BASIS", service().book(CLINIC, KEY, input()).reason());
        assertNoSuccess();
    }

    @Test void clinicReassignmentAfterLockFailsClosed() throws Exception {
        clinicAfterLock++;
        assertEquals("STALE_SCHEDULE", service().book(CLINIC, KEY, input()).reason());
        assertNoSuccess();
        assertFalse(calls.contains("acceptance"));
    }

    @Test void wrongClinicAndRevokedPermissionAreDenied() throws Exception {
        var service = service();
        assertEquals("EXECUTION_PERMISSION_DENIED", service.book(CLINIC + 1, KEY, input()).reason());
        assertEquals(0, allocations);
        revokeInPreparation = true;
        assertEquals("EXECUTION_PERMISSION_DENIED", service.book(CLINIC, UUID.randomUUID(), input()).reason());
        assertNoSuccess();
    }

    @Test void overlappingGlobalDentistBlocksNewBooking() throws Exception {
        occupies = true;
        assertEquals("APPOINTMENT_CAPACITY_CONFLICT", service().book(CLINIC, KEY, input()).reason());
        assertNoSuccess();
    }

    @Test void terminalStatusAloneDoesNotReleaseCapacity() throws Exception {
        occupies = true;
        overlapStatus = "已取消";
        assertEquals("APPOINTMENT_CAPACITY_CONFLICT", service().book(CLINIC, KEY, input()).reason());
        assertNoSuccess();
    }

    @Test void provenCancellationCanReleaseButContradictoryVisitCannot() throws Exception {
        occupies = true;
        overlapStatus = "已取消";
        releaseProven = true;
        hasVisit = true;
        var service = service();
        assertEquals("APPOINTMENT_CAPACITY_CONFLICT", service.book(CLINIC, KEY, input()).reason());
        assertNoSuccess();
        hasVisit = false;
        assertEquals("SUCCEEDED", service.book(CLINIC, UUID.randomUUID(), input()).businessOutcome());
    }

    @Test void historicalSuccessWithUnverifiableRowIsNotReplayed() throws Exception {
        var service = service();
        service.book(CLINIC, KEY, input());
        appointment.put(7, "SYNTHETIC-unrelated-legacy-document");
        assertUnknown(service.recover(CLINIC, KEY, input()));
        assertEquals(1, allocations);
    }

    @Test void duplicateReadsOriginalBookingAndDifferentPayloadNeverExecutesWork() throws Exception {
        var service = service();
        var first = service.book(CLINIC, KEY, input());
        var duplicate = service.book(CLINIC, KEY, input());
        assertEquals(first.result(), duplicate.result());
        assertEquals(first.successEventId(), duplicate.successEventId());
        assertTrue(duplicate.existingResult());
        assertEquals("REQUEST_PAYLOAD_CONFLICT", service.book(CLINIC, KEY, input().put("manualCoordinationBasis","SYNTHETIC-different")).reason());
        assertEquals(1, allocations);
    }

    @Test void recoveryDeniesRevokedStaffAndDoesNotLeakResult() throws Exception {
        var service = service();
        service.book(CLINIC, KEY, input());
        allow = false;
        var denied = service.recover(CLINIC, KEY, input());
        assertEquals("RECOVERY_PERMISSION_DENIED", denied.reason());
        assertNull(denied.result());
        assertEquals(1, allocations);
    }

    @Test void lostBusinessCommitAcknowledgmentCanRecoverWithoutReplay() throws Exception {
        loseCommit = 2;
        var service = service();
        var unknown = service.book(CLINIC, KEY, input());
        assertUnknown(unknown);
        var recovered = service.recover(CLINIC, KEY, input());
        assertEquals("SUCCEEDED", recovered.businessOutcome());
        assertEquals(unknown.requestReference(), recovered.requestReference());
        assertEquals(1, allocations);
    }

    @Test void unknownCommitWithoutVerifiedBusinessRemainsUnknown() throws Exception {
        loseCommit = 2;
        commitActuallySaved = false;
        var service = service();
        assertUnknown(service.book(CLINIC, KEY, input()));
        assertUnknown(service.recover(CLINIC, KEY, input()));
        assertEquals(1, allocations);
        assertNull(appointment);
        assertFalse(outcomes().contains("FAILED"));
    }

    @Test void lostStartAcknowledgmentDoesNotRunBusinessOrReplay() throws Exception {
        loseCommit = 1;
        var service = service();
        assertUnknown(service.book(CLINIC, KEY, input()));
        assertUnknown(service.book(CLINIC, KEY, input()));
        assertEquals(0, allocations);
        assertNull(appointment);
    }

    @Test void auditInsertFailureRollsBackAppointmentBeforeIndependentFailureAudit() throws Exception {
        failSuccessAudit = true;
        var r = service().book(CLINIC, KEY, input());
        assertEquals("FAILED", r.businessOutcome());
        assertEquals("CONFIRMED", r.auditPersistence().name());
        assertNull(appointment);
        assertOrder("insert-29", "audit-SUCCESS", "rollback", "audit-FAILED");
        assertEquals(List.of("STARTED", "FAILED"), outcomes());
    }

    @Test void failureAuditOutageIsNotReportedAsPersisted() throws Exception {
        failSuccessAudit = true;
        failFailureAudit = true;
        var r = service().book(CLINIC, KEY, input());
        assertEquals("FAILED", r.businessOutcome());
        assertEquals("NOT_PERSISTED", r.auditPersistence().name());
        assertNull(appointment);
        assertEquals(List.of("STARTED"), outcomes());
    }

    @Test void mockMvcAcceptsContractAndRejectsDifferentPayload() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new OfflineHttp(service())).build();
        mvc.perform(post("/staff/appointments").header("Idempotency-Key", KEY).contentType("application/json").content(input().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.businessOutcome").value("SUCCEEDED")).andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.links.appointmentId").value("91001"))
                .andExpect(jsonPath("$.links.originalUcSuccessEventId").value("96002"));
        mvc.perform(post("/staff/appointments").header("Idempotency-Key", KEY).contentType("application/json")
                        .content(input().put("manualCoordinationBasis", "SYNTHETIC-other").toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_PAYLOAD_CONFLICT"));
    }

    @Test void mockMvcPreservesUnknownAndStaleContract() throws Exception {
        loseCommit = 2;
        var mvc = MockMvcBuilders.standaloneSetup(new OfflineHttp(service())).build();
        mvc.perform(post("/staff/appointments").header("Idempotency-Key", KEY).contentType("application/json").content(input().toString()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.requestReference").isNotEmpty()).andExpect(jsonPath("$.links").doesNotExist());
        version = 3;
        mvc.perform(post("/staff/appointments").header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content(input().toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_DURATION_BASIS"));
    }

    @RestController
    static class OfflineHttp {
        final AppointmentController adapter;
        OfflineHttp(FormalBookingService service) { adapter = new AppointmentController(service); }
        @PostMapping("/staff/appointments")
        ResponseEntity<String> post(@RequestHeader("Idempotency-Key") String key, @RequestBody String input) {
            var r = adapter.create(CLINIC, key, JSON.readTree(input));
            return ResponseEntity.status(r.status()).header("Content-Type", "application/json").body(r.body().toString());
        }
    }

    static ObjectNode input() {
        var n = JSON.createObjectNode().put("patientId", ""+PATIENT)
                .put("acceptanceEvidenceReference", "SYNTHETIC-acceptance");
        var scheme = n.putObject("scheme").put("treatmentRequest", "SYNTHETIC-cleaning")
                .put("startsAt", "2026-11-08T17:00:00.123456+08:00").put("endsAt", END.toString());
        scheme.putObject("effectiveScheduleRef").put("scheduleId", ""+SCHEDULE).put("versionNo", 1);
        scheme.putObject("durationBasis").put("schemaVersion",1).put("basisMode","INITIAL_CURRENT")
                .put("primaryProjectId", ""+PROJECT).put("projectVersion",2).put("adoptedDurationMinutes",27);
        return n;
    }

    private FormalBookingService service() throws Exception {
        return service(null);
    }

    private FormalBookingService service(FormalBookingService.MiniSource miniSource) throws Exception {
        var ds = fake(DataSource.class);
        when(ds.getConnection()).thenAnswer(i -> connection());
        var tx = new JdbcTransactions(ds);
        var auth = new StaffAuthorization(() -> Optional.of(ACTOR), () -> allow ? Map.of(ACTOR,
                new StaffAuthorization.Grant("SYNTHETIC", Set.of("BOOKING_RECEPTION"), Set.of("UC02"),
                        Set.of(CLINIC), false)) : Map.of());
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var confirmation = new BookingAcceptanceVerifier((c, reference) -> {
            calls.add("acceptance");
            if (!reference.equals("SYNTHETIC-acceptance")) return Optional.empty();
            return Optional.of(new BookingAcceptanceVerifier.Confirmation(reference, STAMP,
                    representative ? "SYNTHETIC-proxy" : "SYNTHETIC-patient",
                    representative ? "SYNTHETIC-proxy-grant" : null, NOW, NOW.plusSeconds(3600),
                    new InitialBookingReview.AcceptedScheme(PATIENT, new Version(SCHEDULE, 1), CLINIC, DENTIST,
                            START, END, "SYNTHETIC-cleaning", new Basis(PROJECT, 2, 27))));
        }, new BookingAcceptanceVerifier.ApplicantAuthority() {
            public boolean isPatient(Connection c, String subject, long patient) {
                return patient == PATIENT && subject.equals("SYNTHETIC-patient");
            }
            public boolean mayAcceptBooking(Connection c, String subject, InitialBookingReview.AcceptedScheme scheme, String grant) {
                return subject.equals("SYNTHETIC-proxy") && grant.equals("SYNTHETIC-proxy-grant");
            }
        }, clock);
        var review = new InitialBookingReview(tx, auth,
                id -> new ClinicDirectoryProvider.Clinic(id, "SYNTHETIC-clinic", ZoneId.of("Asia/Shanghai"), STAMP),
                confirmation);
        return new FormalBookingService(tx, auth, review, (dentist, clinic) -> {
            calls.add("eligibility");
            return new DentistEligibilityProvider.Eligibility(dentist, clinic, "SYNTHETIC-license", STAMP);
        }, c -> 91001 + allocations++, c -> ++auditId, (c, scheme, coordination, source) -> {
            calls.add("preparation");
            if (revokeInPreparation) allow = false;
            return "SYNTHETIC-reminder-arrangement";
        }, (c, overlap) -> releaseProven, clock, miniSource);
    }

    private Connection connection() throws SQLException {
        var c = fake(Connection.class);
        when(c.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(c.createStatement()).thenReturn(fake(Statement.class));
        var auditPending = new ArrayList<Map<Integer,Object>>();
        var bookingPending = new ArrayList<Map<Integer,Object>>();
        var locked = new boolean[1];
        when(c.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            var p = fake(PreparedStatement.class);
            var params = new HashMap<Integer,Object>();
            doAnswer(i -> {params.put(i.getArgument(0),i.getArgument(1));return null;}).when(p).setString(anyInt(), nullable(String.class));
            doAnswer(i -> {params.put(i.getArgument(0),i.getArgument(1));return null;}).when(p).setLong(anyInt(),anyLong());
            doAnswer(i -> {params.put(i.getArgument(0),i.getArgument(1));return null;}).when(p).setInt(anyInt(),anyInt());
            doAnswer(i -> {params.put(i.getArgument(0),i.getArgument(1));return null;}).when(p).setObject(anyInt(),any());
            when(p.execute()).thenAnswer(i -> {
                calls.add(switch (sql) {
                    case "SELECT pg_advisory_xact_lock(41001,?)" -> "L1";
                    case "SELECT pg_advisory_xact_lock_shared(41002,1)" -> "L2";
                    case "SELECT pg_advisory_xact_lock(?)" -> "L3";
                    default -> throw new AssertionError(sql);
                });
                return true;
            });
            when(p.executeQuery()).thenAnswer(i -> {
                if (sql.startsWith("SELECT audit_event_id::text")) {
                    return rows(journal.stream().filter(r -> Objects.equals(r.get(2), params.get(1)))
                            .map(r -> values(r.get(1),r.get(3),r.get(6),r.get(5),r.get(10),r.get(12))).toList());
                }
                if (sql.startsWith("SELECT COALESCE(max(event_sequence_no)")) {
                    return rows(List.of(values((int)journal.stream().filter(r -> Objects.equals(r.get(2),params.get(1))).count())));
                }
                if (sql.startsWith("SELECT clinic_id,dentist_id")) {
                    calls.add(locked[0] ? "schedule-read-locked" : "schedule-read");
                    return rows(List.of(values(locked[0] ? clinicAfterLock : CLINIC, DENTIST,
                            START.atOffset(ZoneOffset.UTC), END.atOffset(ZoneOffset.UTC), "EFFECTIVE")));
                }
                if (sql.startsWith("SELECT row_to_json(t)::text")) {
                    calls.add("CURRENT");
                    return rows(List.of(values("""
                            {"project_id":85001,"duration_version_no":%d,"duration_minutes":27,
                            "publication_status":"CURRENT","project_name":"SYNTHETIC-cleaning"}
                            """.formatted(version))));
                }
                if (sql.startsWith("SELECT dentist_id FROM adc.dentist")) {
                    calls.add("L4");
                    return rows(List.of(values(DENTIST)));
                }
                if (sql.startsWith("SELECT schedule_id FROM adc.reception_schedule_version")) {
                    calls.add("L5");
                    locked[0] = true;
                    return rows(List.of(values(SCHEDULE)));
                }
                if (sql.startsWith("SELECT a.appointment_id, v.clinic_id")) {
                    calls.add("capacity");
                    assertTrue(sql.contains("a.starts_at < ? AND ? < a.ends_at"));
                    assertEquals(DENTIST, params.get(1));
                    assertEquals(END.atOffset(ZoneOffset.UTC), params.get(2));
                    assertEquals(START.atOffset(ZoneOffset.UTC), params.get(3));
                    return rows(occupies ? List.of(values(91000L, CLINIC + 1, overlapStatus,
                            "SYNTHETIC-original-verification", END.atOffset(ZoneOffset.UTC), hasVisit)) : List.of());
                }
                if (sql.startsWith("SELECT appointment_id,")) {
                    assertEquals(CLINIC, params.get(2));
                    return rows(appointment == null ? List.of() : List.of(appointment));
                }
                throw new AssertionError(sql);
            });
            when(p.executeUpdate()).thenAnswer(i -> {
                if (sql.startsWith("INSERT INTO adc.appointment")) {
                    calls.add("insert-29");
                    bookingPending.add(new HashMap<>(params));
                } else if (sql.startsWith("INSERT INTO adc.operation_audit_event")) {
                    calls.add("audit-" + params.get(10));
                    if ((failSuccessAudit && "SUCCESS".equals(params.get(10))) ||
                            (failFailureAudit && "FAILED".equals(params.get(10)))) throw new SQLException("SYNTHETIC", "23505");
                    auditPending.add(new HashMap<>(params));
                } else throw new AssertionError(sql);
                return 1;
            });
            return p;
        });
        doAnswer(i -> {
            commits++;
            if (commits != loseCommit || commitActuallySaved) {
                journal.addAll(auditPending);
                if (!bookingPending.isEmpty()) appointment = bookingPending.getFirst();
            }
            if (commits == loseCommit) throw new SQLException("SYNTHETIC lost acknowledgment", "08006");
            return null;
        }).when(c).commit();
        doAnswer(i -> {
            calls.add("rollback");
            auditPending.clear();
            bookingPending.clear();
            return null;
        }).when(c).rollback();
        return c;
    }

    private static Map<Integer,Object> values(Object... columns) {
        var row = new HashMap<Integer,Object>();
        for (int i = 0; i < columns.length; i++) row.put(i + 1, columns[i]);
        return row;
    }

    private static ResultSet rows(List<Map<Integer,Object>> rows) throws SQLException {
        var result = fake(ResultSet.class);
        var index = new int[] {-1};
        when(result.next()).thenAnswer(i -> ++index[0] < rows.size());
        when(result.getString(anyInt())).thenAnswer(i -> rows.get(index[0]).get(i.<Integer>getArgument(0)));
        when(result.getLong(anyInt())).thenAnswer(i -> rows.get(index[0]).get(i.<Integer>getArgument(0)));
        when(result.getInt(anyInt())).thenAnswer(i -> rows.get(index[0]).get(i.<Integer>getArgument(0)));
        when(result.getBoolean(anyInt())).thenAnswer(i -> rows.get(index[0]).get(i.<Integer>getArgument(0)));
        when(result.getObject(anyInt(), any(Class.class))).thenAnswer(i -> rows.get(index[0]).get(i.<Integer>getArgument(0)));
        return result;
    }

    private static <T> T fake(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.PROXY));
    }

    private void assertNoSuccess() {
        assertNull(appointment);
        assertFalse(outcomes().contains("SUCCESS"));
    }

    private static void assertUnknown(Receipt r) {
        assertEquals("UNKNOWN", r.businessOutcome());
        assertNull(r.result());
    }

    private List<Object> outcomes() {
        return journal.stream().map(row -> row.get(10)).toList();
    }

    private void assertOrder(String... expected) {
        int cursor = 0;
        for (var item : calls) if (cursor < expected.length && item.equals(expected[cursor])) cursor++;
        assertEquals(expected.length, cursor, calls.toString());
    }
}
