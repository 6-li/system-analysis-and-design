package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.config.DentistEligibilityProvider;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.ClinicTimePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppointmentChangeServiceTest {
    private static final String ACTOR = "SYNTHETIC-reception";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant OLD_START = Instant.parse("2026-11-08T09:00:00Z");
    private static final Instant START = Instant.parse("2026-11-08T09:30:00Z");
    private static final Version OLD = new Version(801, 1);
    private static final Version TARGET = new Version(802, 2);
    private static final UUID KEY = UUID.fromString("c56be4b6-10c2-4792-889a-03d9092946c7");
    private static final SourceStamp STAMP = new SourceStamp("SYNTHETIC", "v1", "fixture", CLOCK.instant(), true);

    private final Connection c = mock(Connection.class);
    private final AppointmentRepository repo = mock(AppointmentRepository.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    private final AppointmentChangeService.MiniSource miniSource = mock(AppointmentChangeService.MiniSource.class);
    private final List<String> locksTaken = new ArrayList<>();
    private final Basis originalBasis = new Basis(501, 1, 27);
    private Basis acceptedBasis = originalBasis;
    private Set<Long> grants = Set.of(11L, 12L);
    private boolean accept = true;
    private String targetStatus = "EFFECTIVE";
    private final List<Map<Integer, String>> auditRows = new ArrayList<>();
    private boolean failSuccessAudit;
    private JdbcTransactions transactions;
    private AppointmentChangeService service;
    private AppointmentRepository.Appointment original;

    @BeforeEach void setup() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(c);
        when(c.createStatement()).thenReturn(mock(Statement.class));
        transactions = new JdbcTransactions(source);
        var auth = new StaffAuthorization(() -> Optional.of(ACTOR), () -> Map.of(ACTOR,
                new StaffAuthorization.Grant("v1", Set.of("BOOKING_RECEPTION"), Set.of("UC03"), grants, false)));
        var verifier = new BookingAcceptanceVerifier((connection, reference) -> accept
                ? Optional.of(new BookingAcceptanceVerifier.Confirmation(reference, STAMP, "SYNTHETIC-patient",
                null, CLOCK.instant(), null, new InitialBookingReview.AcceptedScheme(201, TARGET, 12, 22,
                START, START.plusSeconds(acceptedBasis.minutes() * 60L), "SYNTHETIC request", acceptedBasis)))
                : Optional.empty(), new BookingAcceptanceVerifier.ApplicantAuthority() {
            public boolean isPatient(Connection connection, String subject, long patient) { return patient == 201; }
            public boolean mayAcceptBooking(Connection connection, String subject, InitialBookingReview.AcceptedScheme scheme, String grant) { return false; }
        }, CLOCK);
        service = new AppointmentChangeService(transactions, auth,
                clinic -> new ClinicDirectoryProvider.Clinic(clinic, "SYNTHETIC", ZoneId.of("Etc/UTC"), STAMP),
                (dentist, clinic) -> new DentistEligibilityProvider.Eligibility(dentist, clinic, "SYNTHETIC", STAMP),
                verifier, connection -> 901, connection -> 999,
                (connection, before, target) -> "new reminder", (connection, overlap) -> false, CLOCK, repo, miniSource);
        var oldScheme = new InitialBookingReview.AcceptedScheme(201, OLD, 11, 21, OLD_START,
                OLD_START.plusSeconds(1620), "old request", originalBasis);
        var time = new ClinicTimePolicy("v1", Map.of(11L, "Etc/UTC"))
                .resolve(11, "2026-11-08T09:00:00", ZoneOffset.UTC, 27);
        original = new AppointmentRepository.Appointment(301, 201,
                new AppointmentRepository.Terms(OLD, OLD_START, OLD_START.plusSeconds(1620),
                        FormalBookingService.freeze(oldScheme, "original", time, "INITIAL_CURRENT"), "old reminder"),
                "old acceptance", "待到诊", null, null, ACTOR, CLOCK.instant(), null, null);
        when(repo.findOrigin(c,301,11)).thenReturn(Optional.of(OLD));
        when(repo.findAppointment(c,301,11)).thenReturn(Optional.of(original));
        when(c.prepareStatement(anyString())).thenAnswer(call -> statement(call.getArgument(0)));
    }

    @Test void preservesOriginalDurationAndIdentityAndWritesHistoryWithReminder() throws Exception {
        var change = apply(input(originalBasis, "PRESERVED_ORIGINAL"));
        assertEquals("301", change.after().path("appointmentId").asText());
        assertEquals("901", change.after().path("appointmentChangeId").asText());
        var after = ArgumentCaptor.forClass(AppointmentRepository.Appointment.class);
        var history = ArgumentCaptor.forClass(AppointmentRepository.ChangeEvidence.class);
        var order = inOrder(repo);
        order.verify(repo).updateAppointment(eq(c), eq(original), after.capture());
        order.verify(repo).appendChange(eq(c), eq(original), eq(after.getValue()), history.capture());
        assertEquals(301, after.getValue().id());
        assertEquals(201, after.getValue().patientId());
        assertEquals("new reminder", after.getValue().terms().reminder());
        assertEquals(originalBasis, AppointmentEvidenceCodec.readStructured(after.getValue().terms().treatmentRequest()).basis());
        assertEquals("old reminder", original.terms().reminder());
        assertEquals(AppointmentChangeService.reference(ACTOR, KEY), history.getValue().requestReference());
        assertEquals(List.of("L1","L2","L3","L3","L4","L4","L5","L5","L6"), locksTaken);
    }

    @Test void changedProjectUsesCurrentAndRejectsAnEarlierVersion() throws Exception {
        var stale = new Basis(502, 1, 35);
        rejects("STALE_DURATION_BASIS", input(stale, "CHANGED_PROJECT_CURRENT"));
        acceptedBasis = new Basis(502, 2, 35);
        assertEquals("901", apply(input(acceptedBasis, "CHANGED_PROJECT_CURRENT"))
                .after().path("appointmentChangeId").asText());
    }

    @Test void staleTokenAndDraftFailBeforeWrite() throws Exception {
        rejects("STALE_APPOINTMENT", input(originalBasis, "PRESERVED_ORIGINAL").put("expectedAppointmentToken","obsolete"));
        targetStatus = "DRAFT";
        rejects("EFFECTIVE_SCHEDULE_REQUIRED", input(originalBasis, "PRESERVED_ORIGINAL"));
        verifyNoWrites();
    }

    @Test void missingPermissionOnEitherClinicAndUnverifiedAcceptanceFail() throws Exception {
        for (long clinic : List.of(11L, 12L)) {
            grants = Set.of(clinic);
            assertThrows(SecurityException.class, () -> apply(input(originalBasis,"PRESERVED_ORIGINAL")));
        }
        grants = Set.of(11L, 12L);
        accept = false;
        rejects("ACCEPTANCE_RECONFIRMATION_REQUIRED", input(originalBasis,"PRESERVED_ORIGINAL"));
        verifyNoWrites();
    }

    @Test void capacityExcludesOnlyOwnAppointment() throws Exception {
        when(repo.overlaps(eq(c),eq(22L),any(),any())).thenReturn(List.of(
                new AppointmentRepository.Overlap(301,11,"待到诊",null,START,false),
                new AppointmentRepository.Overlap(302,12,"待到诊",null,START,false)));
        rejects("APPOINTMENT_CAPACITY_CONFLICT", input(originalBasis,"PRESERVED_ORIGINAL"));
        verifyNoWrites();
        when(repo.overlaps(eq(c),eq(22L),any(),any())).thenReturn(List.of(
                new AppointmentRepository.Overlap(301,11,"待到诊",null,START,false)));
        assertNotNull(apply(input(originalBasis,"PRESERVED_ORIGINAL")));
    }

    @Test void historyFailureRollsBackAndRecoveryRequiresMatchingHistory() throws Exception {
        var input = input(originalBasis,"PRESERVED_ORIGINAL");
        var command = AppointmentChangeService.command(301,11,12,KEY,input);
        doThrow(new SQLException("history unavailable","23505")).when(repo).appendChange(eq(c),any(),any(),any());
        assertThrows(JdbcTransactions.Failure.class, () -> transactions.execute(connection -> apply(input)));
        verify(c).rollback();
        verify(c, never()).commit();
        doNothing().when(repo).appendChange(eq(c),any(),any(),any());
        clearInvocations(repo);
        var change = apply(input);
        var after = ArgumentCaptor.forClass(AppointmentRepository.Appointment.class);
        var evidence = ArgumentCaptor.forClass(AppointmentRepository.ChangeEvidence.class);
        verify(repo).appendChange(eq(c),eq(original),after.capture(),evidence.capture());
        var saved = JSON.createObjectNode();
        saved.set("after",change.after());
        saved.set("businessReference",change.businessReference());
        saved.set("verification",change.verification());
        assertFalse(service.matchesCommitted(c,command,saved));
        when(repo.findChange(c,301,901)).thenReturn(Optional.of(new AppointmentRepository.Change(
                301,original.terms(),after.getValue().terms(),evidence.getValue())));
        assertTrue(service.matchesCommitted(c,command,saved));
    }

    @Test void successAuditFailureRollsBackAndRecordsFailureAfterward() throws Exception {
        failSuccessAudit = true;
        var receipt = service.change(301,11,12,KEY,input(originalBasis,"PRESERVED_ORIGINAL"));
        assertEquals("FAILED", receipt.businessOutcome());
        assertNull(receipt.result());
        var order = inOrder(repo, c);
        order.verify(c).commit(); // START
        order.verify(repo).updateAppointment(eq(c), eq(original), any());
        order.verify(repo).appendChange(eq(c), eq(original), any(), any());
        order.verify(c).rollback();
        order.verify(c).commit(); // Independent failure audit
        assertEquals(List.of("STARTED","FAILED"), auditRows.stream().map(row -> row.get(10)).toList());
    }

    @Test void checksMiniSourceBeforeWritesAndRollsBackWhenLinkFails() throws Exception {
        var source = "SYNTHETIC-verified-context";
        var input = input(originalBasis, "PRESERVED_ORIGINAL").put("sourceContextReference", source);
        doThrow(new SQLException("SYNTHETIC link failure", "23514")).when(miniSource)
                .linkCommittedResult(eq(c), eq(source), any());
        var receipt = service.change(301, 11, 12, KEY, input);
        assertEquals("FAILED", receipt.businessOutcome());
        var order = inOrder(miniSource, repo, c);
        order.verify(miniSource).requireCurrent(eq(c), eq(source), eq(original), any(), eq("SYNTHETIC acceptance"));
        order.verify(repo).updateAppointment(eq(c), eq(original), any());
        order.verify(repo).appendChange(eq(c), eq(original), any(), any());
        order.verify(miniSource).linkCommittedResult(eq(c), eq(source), any());
        order.verify(c).rollback();
        assertEquals(List.of("STARTED", "FAILED"), auditRows.stream().map(row -> row.get(10)).toList());
    }

    @Test void miniRecoveryRequiresBothHistoryAndMatchingLink() throws Exception {
        var source = "SYNTHETIC-verified-context";
        var input = input(originalBasis, "PRESERVED_ORIGINAL").put("sourceContextReference", source);
        var change = apply(input);
        var linked = ArgumentCaptor.forClass(AppointmentRepository.Change.class);
        verify(miniSource).linkCommittedResult(eq(c), eq(source), linked.capture());
        var saved = JSON.createObjectNode();
        saved.set("after", change.after());
        saved.set("businessReference", change.businessReference());
        saved.set("verification", change.verification());
        var command = AppointmentChangeService.command(301, 11, 12, KEY, input);
        when(repo.findChange(c, 301, 901)).thenReturn(Optional.of(linked.getValue()));
        assertFalse(service.matchesCommitted(c, command, saved));
        when(miniSource.matchesCommitted(c, source, linked.getValue())).thenReturn(true);
        assertTrue(service.matchesCommitted(c, command, saved));
    }

    @Test void changeReceiptIncludesHistoryAndDoesNotClaimUnverifiedSuccess() {
        var result = JSON.createObjectNode().put("appointmentId", "301").put("appointmentChangeId","901");
        var receipt = new cn.adc.shared.audit.CommandRecovery.Receipt("reference", "SUCCEEDED",
                cn.adc.shared.audit.CommandRecovery.Persistence.CONFIRMED, result, false, "999", null);
        var response = AppointmentController.response(receipt,true);
        assertEquals(200, response.status());
        assertEquals("UC03", response.body().path("ucCode").asText());
        assertEquals("901", response.body().at("/links/appointmentChangeId").asText());
        result.remove("appointmentChangeId");
        assertEquals(202, AppointmentController.response(receipt,true).status());
        assertFalse(AppointmentController.response(receipt,true).body().has("links"));
    }

    private void rejects(String code, ObjectNode input) {
        assertEquals(code, assertThrows(Rejection.class, () -> apply(input)).getMessage());
    }

    private void verifyNoWrites() throws Exception {
        verify(repo,never()).updateAppointment(any(),any(),any());
        verify(repo,never()).appendChange(any(),any(),any(),any());
    }

    private cn.adc.shared.audit.AuditDocuments.Change apply(ObjectNode input) throws SQLException {
        var scope = new ResourceLockCoordinator().scope(c);
        scope.command(AppointmentChangeService.reference(ACTOR,KEY));
        return service.apply(c,scope,AppointmentChangeService.command(301,11,12,KEY,input),ACTOR);
    }

    private ObjectNode input(Basis basis, String mode) {
        var node = JSON.createObjectNode().put("expectedAppointmentToken",AppointmentReadService.token(original))
                .put("acceptanceEvidenceReference","SYNTHETIC acceptance").put("changeBasis","SYNTHETIC request");
        var scheme = node.putObject("scheme").put("startsAt",START.toString())
                .put("endsAt",START.plusSeconds(basis.minutes()*60L).toString()).put("treatmentRequest","SYNTHETIC request");
        scheme.putObject("effectiveScheduleRef").put("scheduleId","802").put("versionNo",2);
        scheme.putObject("durationBasis").put("schemaVersion",1).put("basisMode",mode)
                .put("primaryProjectId",Long.toString(basis.projectId())).put("projectVersion",basis.versionNo())
                .put("adoptedDurationMinutes",basis.minutes());
        return node;
    }

    private PreparedStatement statement(String sql) throws SQLException {
        var statement = mock(PreparedStatement.class);
        var keys = new HashMap<Integer,Long>();
        doAnswer(call -> { keys.put(call.getArgument(0),call.getArgument(1)); return null; })
                .when(statement).setLong(anyInt(),anyLong());
        if (sql.startsWith("INSERT INTO adc.operation_audit_event")) {
            var values = new HashMap<Integer, String>();
            doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(1)); return null; })
                    .when(statement).setString(anyInt(), nullable(String.class));
            when(statement.executeUpdate()).thenAnswer(call -> {
                if (failSuccessAudit && "SUCCESS".equals(values.get(10))) throw new SQLException("audit unavailable","23505");
                auditRows.add(values);
                return 1;
            });
        }
        if (sql.contains("41001")) locksTaken.add("L1");
        else if (sql.contains("41002")) locksTaken.add("L2");
        else if (sql.contains("advisory_xact_lock")) locksTaken.add("L3");
        else if (sql.contains("dentist") && sql.contains("FOR UPDATE")) locksTaken.add("L4");
        else if (sql.contains("reception_schedule_version") && sql.contains("FOR UPDATE")) locksTaken.add("L5");
        else if (sql.contains("appointment") && sql.contains("FOR UPDATE")) locksTaken.add("L6");
        when(statement.executeQuery()).thenAnswer(call -> {
            var row = mock(ResultSet.class);
            when(row.next()).thenReturn(true);
            if (sql.startsWith("SELECT audit_event_id::text")) {
                var snapshot = List.copyOf(auditRows);
                var iterator = snapshot.iterator();
                var current = new java.util.concurrent.atomic.AtomicReference<Map<Integer, String>>();
                when(row.next()).thenAnswer(next -> {
                    if (!iterator.hasNext()) return false;
                    current.set(iterator.next());
                    return true;
                });
                when(row.getInt(2)).thenReturn(1);
                when(row.getString(anyInt())).thenAnswer(column -> switch (column.<Integer>getArgument(0)) {
                    case 1 -> current.get().get(1);
                    case 3 -> current.get().get(6);
                    case 4 -> current.get().get(5);
                    case 5 -> current.get().get(10);
                    case 6 -> current.get().get(12);
                    default -> null;
                });
            } else if (sql.startsWith("SELECT COALESCE(max(event_sequence_no)")) {
                when(row.getInt(1)).thenReturn(auditRows.size());
            } else if (sql.contains("activation_status")) {
                boolean old = keys.get(1) == 801L;
                when(row.getLong(1)).thenReturn(old ? 11L : 12L);
                when(row.getLong(2)).thenReturn(old ? 21L : 22L);
                when(row.getObject(3,OffsetDateTime.class)).thenReturn(OLD_START.atOffset(ZoneOffset.UTC));
                when(row.getObject(4,OffsetDateTime.class)).thenReturn(OLD_START.plusSeconds(7200).atOffset(ZoneOffset.UTC));
                when(row.getString(5)).thenReturn(old ? "SUPERSEDED" : targetStatus);
            } else if (sql.contains("project_duration_version")) {
                when(row.getString(1)).thenReturn("""
                    {"project_id":502,"duration_version_no":2,"duration_minutes":35,"publication_status":"CURRENT","project_name":"SYNTHETIC current"}
                    """);
            }
            return row;
        });
        return statement;
    }
}
