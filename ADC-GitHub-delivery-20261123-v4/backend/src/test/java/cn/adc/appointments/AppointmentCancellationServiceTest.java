package cn.adc.appointments;

import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppointmentCancellationServiceTest {
    private static final UUID KEY = UUID.fromString("f93445f6-d3bc-4cc4-8d6d-a020df1e5343");
    private static final String ACTOR = "SYNTHETIC-reception";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), ZoneOffset.UTC);
    private static final Instant START = Instant.parse("2026-11-12T09:00:00Z");
    private final Connection connection = mock(Connection.class);
    private final AppointmentRepository repository = mock(AppointmentRepository.class, withSettings().mockMaker(MockMakers.INLINE));
    private final Version version = new Version(801, 1);
    private final AppointmentRepository.Appointment original = new AppointmentRepository.Appointment(301,201,
            new AppointmentRepository.Terms(version, START, START.plusSeconds(1620), "historical plan", "original reminder"),
            "original acceptance","待到诊",null,null,ACTOR,CLOCK.instant(),null,null);
    private final List<Map<Integer,String>> audits = new ArrayList<>();
    private final List<String> locks = new ArrayList<>();
    private AppointmentCancellationService service;
    private AppointmentRepository.Appointment stored;
    private boolean allowed = true;
    private boolean verified = true;
    private boolean hasVisit;
    private boolean failSuccess;
    private boolean loseCommit;

    @BeforeEach void setup() throws Exception {
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        when(connection.prepareStatement(anyString())).thenAnswer(call -> statement(call.getArgument(0)));
        stored = original;
        when(repository.findOrigin(connection,301,11)).thenReturn(Optional.of(version));
        when(repository.findAppointment(connection,301,11)).thenAnswer(call -> Optional.of(stored));
        when(repository.findVisit(eq(connection),any())).thenAnswer(call -> hasVisit
                ? Optional.of(new AppointmentRepository.Visit(0,301,11,21,START,"arrived",ACTOR,START)) : Optional.empty());
        var auth = new StaffAuthorization(() -> Optional.of(ACTOR), () -> allowed
                ? Map.of(ACTOR,new StaffAuthorization.Grant("v1",Set.of("BOOKING_RECEPTION"),Set.of("UC04"),Set.of(11L),false))
                : Map.of());
        var ids = new java.util.concurrent.atomic.AtomicLong(990);
        service = new AppointmentCancellationService(new JdbcTransactions(ds),auth,
                (c,row,ref) -> verified && "submitted".equals(ref) ? Optional.of("verified cancellation evidence") : Optional.empty(),
                c -> ids.incrementAndGet(),CLOCK,repository);
    }

    @Test void cancelPreservesHistoricalIdentityAndStopsReminderWithoutNoShowOrChangeHistory() throws Exception {
        var change = apply(input());
        assertEquals("301",change.after().path("appointmentId").asText());
        var after = after();
        assertEquals(original.id(),after.id());
        assertEquals(original.terms().schedule(),after.terms().schedule());
        assertEquals(original.terms().startsAt(),after.terms().startsAt());
        assertEquals("已取消",after.status());
        assertNull(after.noShow());
        var reminder = JSON.readTree(after.terms().reminder());
        assertEquals("STOPPED",reminder.path("state").asText());
        assertEquals("original reminder",reminder.path("previousArrangement").asText());
        assertEquals("verified cancellation evidence",JSON.readTree(after.cancellation()).path("verificationReference").asText());
        assertEquals(List.of("L1","L3","L4","L5","L6"),locks);
        verify(repository,never()).appendChange(any(),any(),any(),any());
    }

    @Test void staleDeniedUnverifiedAndContradictoryVisitNeverWrite() throws Exception {
        rejects("STALE_APPOINTMENT",input().put("expectedAppointmentToken","old"));
        allowed = false;
        assertThrows(SecurityException.class, () -> apply(input()));
        allowed = true;
        verified = false;
        rejects("CANCELLATION_VERIFICATION_REQUIRED",input());
        verified = true;
        hasVisit = true;
        rejects("APPOINTMENT_NOT_CANCELLABLE",input());
        verify(repository,never()).updateAppointment(any(),any(),any());
    }

    @Test void repeatedNewKeyReturnsOriginalSuccessAndReleaseRequiresItsEvidence() throws Exception {
        var initial = service.cancel(301,11,KEY,input());
        assertEquals("SUCCEEDED",initial.businessOutcome());
        stored = after();
        var overlap = new AppointmentRepository.Overlap(301,11,"已取消",stored.cancellation(),stored.terms().endsAt(),false);
        assertTrue(service.confirmsRelease(connection,overlap));
        var retry = service.cancel(301,11,UUID.randomUUID(),input());
        assertEquals("SUCCEEDED",retry.businessOutcome());
        assertTrue(retry.existingResult());
        assertEquals(initial.successEventId(),retry.successEventId());
        assertEquals(1,audits.stream().filter(row -> "SUCCESS".equals(row.get(10))).count());
        hasVisit = true;
        assertFalse(service.confirmsRelease(connection,overlap));
        assertEquals("UNKNOWN",service.recover(301,11,KEY,input()).businessOutcome());
        verify(repository,times(1)).updateAppointment(any(),any(),any());
    }

    @Test void legacyCancelledRowWithoutCommittedEvidenceDoesNotReleaseOrSucceed() throws Exception {
        stored = new AppointmentRepository.Appointment(original.id(), original.patientId(), original.terms(),
                original.acceptance(), "已取消", null, "legacy note", ACTOR, CLOCK.instant(), null, null);
        var overlap = new AppointmentRepository.Overlap(301,11,"已取消",stored.cancellation(),START,false);
        assertFalse(service.confirmsRelease(connection,overlap));
        var controller = new AppointmentController(null,null,null,service);
        var response = controller.cancel(301,11,KEY.toString(),input());
        assertEquals(202,response.status());
        assertFalse(response.body().has("links"));
        verify(repository,never()).updateAppointment(any(),any(),any());
    }

    @Test void auditFailureRollsBackBeforeIndependentFailureEvent() throws Exception {
        failSuccess = true;
        var receipt = service.cancel(301,11,KEY,input());
        assertEquals("FAILED",receipt.businessOutcome());
        var order = inOrder(connection,repository);
        order.verify(connection).commit();
        order.verify(repository).updateAppointment(eq(connection),eq(original),any());
        order.verify(connection).rollback();
        order.verify(connection).commit();
        assertEquals(List.of("STARTED","FAILED"),audits.stream().map(row -> row.get(10)).toList());
    }

    @Test void unknownCommitRecoversByOriginalKeyWithoutRepeatingWrite() throws Exception {
        loseCommit = true;
        var count = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (loseCommit && count.incrementAndGet()==2) throw new SQLException("lost acknowledgment","08006");
            return null;
        }).when(connection).commit();
        var unknown = service.cancel(301,11,KEY,input());
        assertEquals("UNKNOWN",unknown.businessOutcome());
        stored = after();
        assertEquals("SUCCEEDED",service.recover(301,11,KEY,input()).businessOutcome());
        assertEquals("REQUEST_PAYLOAD_CONFLICT",service.cancel(301,11,KEY,input().put("cancellationVerification","different")).reason());
        verify(repository,times(1)).updateAppointment(any(),any(),any());
    }

    private void rejects(String code, ObjectNode request) {
        assertEquals(code,assertThrows(Rejection.class, () -> apply(request)).getMessage());
    }

    private cn.adc.shared.audit.AuditDocuments.Change apply(ObjectNode request) throws SQLException {
        var scope = new ResourceLockCoordinator().scope(connection);
        scope.command(AppointmentCancellationService.reference(ACTOR,KEY));
        return service.apply(connection,scope,AppointmentCancellationService.command(301,11,KEY,request),ACTOR);
    }

    private AppointmentRepository.Appointment after() throws SQLException {
        var after = ArgumentCaptor.forClass(AppointmentRepository.Appointment.class);
        verify(repository).updateAppointment(eq(connection),eq(original),after.capture());
        return after.getValue();
    }

    private ObjectNode input() {
        return JSON.createObjectNode().put("expectedAppointmentToken",AppointmentReadService.token(original))
                .put("cancellationVerification","submitted");
    }

    private PreparedStatement statement(String sql) throws SQLException {
        var statement = mock(PreparedStatement.class);
        var parameters = new HashMap<Integer,String>();
        doAnswer(call -> { parameters.put(call.getArgument(0),call.getArgument(1)); return null; })
                .when(statement).setString(anyInt(),nullable(String.class));
        if (sql.contains("41001")) locks.add("L1");
        else if (sql.contains("advisory_xact_lock")) locks.add("L3");
        else if (sql.contains("dentist") && sql.contains("FOR UPDATE")) locks.add("L4");
        else if (sql.contains("reception_schedule_version") && sql.contains("FOR UPDATE")) locks.add("L5");
        else if (sql.contains("appointment") && sql.contains("FOR UPDATE")) locks.add("L6");
        if (sql.startsWith("INSERT INTO adc.operation_audit_event")) {
            var sequence = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> { sequence.set(call.getArgument(1)); return null; }).when(statement).setInt(eq(3),anyInt());
            when(statement.executeUpdate()).thenAnswer(call -> {
                if (failSuccess && "SUCCESS".equals(parameters.get(10))) throw new SQLException("collision","23505");
                parameters.put(3,Integer.toString(sequence.get()));
                audits.add(parameters);
                return 1;
            });
        }
        when(statement.executeQuery()).thenAnswer(call -> {
            var row = mock(ResultSet.class);
            when(row.next()).thenReturn(true);
            if (sql.contains("activation_status")) {
                when(row.getLong(1)).thenReturn(11L);
                when(row.getLong(2)).thenReturn(21L);
                when(row.getObject(3,OffsetDateTime.class)).thenReturn(START.atOffset(ZoneOffset.UTC));
                when(row.getObject(4,OffsetDateTime.class)).thenReturn(START.plusSeconds(3600).atOffset(ZoneOffset.UTC));
                when(row.getString(5)).thenReturn("SUPERSEDED");
            } else if (sql.startsWith("SELECT COALESCE")) {
                when(row.getInt(1)).thenReturn((int) audits.stream().filter(a -> Objects.equals(a.get(2),parameters.get(1))).count());
            } else if (sql.startsWith("SELECT audit_event_id")) {
                var rows = audits.stream().filter(a -> Objects.equals(a.get(2),parameters.get(1))).toList().iterator();
                var current = new java.util.concurrent.atomic.AtomicReference<Map<Integer,String>>();
                when(row.next()).thenAnswer(next -> {
                    if (!rows.hasNext()) return false;
                    current.set(rows.next());
                    return true;
                });
                when(row.getInt(2)).thenAnswer(column -> Integer.parseInt(current.get().get(3)));
                when(row.getString(anyInt())).thenAnswer(column -> current.get().get(switch(column.<Integer>getArgument(0)) {
                    case 1 -> 1;
                    case 3 -> 6;
                    case 4 -> 5;
                    case 5 -> 10;
                    case 6 -> 12;
                    default -> throw new AssertionError("unexpected column");
                }));
            }
            return row;
        });
        return statement;
    }
}
