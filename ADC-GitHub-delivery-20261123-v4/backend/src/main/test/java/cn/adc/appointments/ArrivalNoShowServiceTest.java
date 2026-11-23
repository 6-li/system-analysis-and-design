package cn.adc.appointments;

import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

import static cn.adc.appointments.ArrivalNoShowService.Action.*;
import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArrivalNoShowServiceTest {
    private static final String ACTOR = "SYNTHETIC-clinic-reception";
    private static final UUID KEY = UUID.fromString("3b616b8a-f874-4f70-a25e-8ec04839679d");
    private static final Instant END = Instant.parse("2026-11-12T09:27:00.123456Z");
    private static final Version VERSION = new Version(801,1);
    private final Connection c = mock(Connection.class);
    private final AppointmentRepository repo = mock(AppointmentRepository.class,withSettings().mockMaker(MockMakers.INLINE));
    private final AppointmentRepository.Appointment original = row("待到诊",null);
    private Instant now = END;
    private boolean allowed = true;
    private boolean verified = true;
    private ArrivalNoShowService service;
    private JdbcTransactions transactions;

    @BeforeEach void setup() throws Exception {
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.createStatement()).thenReturn(mock(Statement.class));
        transactions = new JdbcTransactions(ds);
        when(repo.findOrigin(c,301,11)).thenReturn(Optional.of(VERSION));
        when(repo.findAppointment(c,301,11)).thenReturn(Optional.of(original));
        when(c.prepareStatement(anyString())).thenAnswer(call -> statement(call.getArgument(0)));
        var auth = new StaffAuthorization(() -> Optional.of(ACTOR), () -> allowed
                ? Map.of(ACTOR,new StaffAuthorization.Grant("v1",Set.of("CLINIC_RECEPTION"),Set.of("UC05","UC06"),Set.of(11L),false))
                : Map.of());
        var clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        };
        service = new ArrivalNoShowService(transactions,auth,repo,
                (connection, appointment, finding) -> verified
                        ? Optional.of(new ArrivalNoShowService.Verified("SYNTHETIC verified",null)) : Optional.empty(),
                connection -> 902,connection -> 999,clock);
    }

    @Test void confirmedArrivalCreatesExactlyOneVisitAndPreservesOriginalAppointment() throws Exception {
        var change = apply(ARRIVAL,"ARRIVAL_VERIFIED");
        var after = ArgumentCaptor.forClass(AppointmentRepository.Appointment.class);
        var visit = ArgumentCaptor.forClass(AppointmentRepository.Visit.class);
        var order = inOrder(repo);
        order.verify(repo).updateAppointment(eq(c),eq(original),after.capture());
        order.verify(repo).insertVisit(eq(c),eq(after.getValue()),visit.capture());
        assertEquals("已到诊",after.getValue().status());
        assertEquals(original.terms(),after.getValue().terms());
        assertEquals(301,visit.getValue().appointmentId());
        assertNull(visit.getValue().arrivedAt());
        assertEquals("902",change.after().path("visitId").asText());
    }

    @Test void pendingNeverCreatesVisitOrLooksLikeConfirmedAttendance() throws Exception {
        var change = apply(ARRIVAL,"PENDING_VERIFICATION");
        var after = ArgumentCaptor.forClass(AppointmentRepository.Appointment.class);
        verify(repo).updateAppointment(eq(c),eq(original),after.capture());
        assertEquals("待核实",after.getValue().status());
        assertNull(after.getValue().noShow());
        verify(repo,never()).insertVisit(any(),any(),any());
        var receipt = new CommandRecovery.Receipt("reference","SUCCEEDED",CommandRecovery.Persistence.CONFIRMED,
                change.after(),false,"999",null);
        var response = AppointmentController.attendanceResponse(ARRIVAL,receipt);
        assertEquals("PERSISTED_INCOMPLETE",response.body().path("status").asText());
        assertFalse(response.body().path("links").has("visitId"));
    }

    @Test void noShowRequiresHumanVerificationAndAllowsExactEnd() throws Exception {
        now = END.minusNanos(1000);
        rejects("APPOINTMENT_NOT_ENDED",NO_SHOW,"NO_SHOW_VERIFIED");
        now = END;
        verified = false;
        rejects("ATTENDANCE_VERIFICATION_REQUIRED",NO_SHOW,"NO_SHOW_VERIFIED");
        verified = true;
        apply(NO_SHOW,"NO_SHOW_VERIFIED");
        var after = ArgumentCaptor.forClass(AppointmentRepository.Appointment.class);
        verify(repo,times(1)).updateAppointment(eq(c),eq(original),after.capture());
        assertEquals("已确认未到诊",after.getValue().status());
        assertNotNull(after.getValue().noShow());
        verify(repo,never()).insertVisit(any(),any(),any());
    }

    @Test void oppositeVisitCancellationAndMissingRoleNeverWrite() throws Exception {
        when(repo.findVisit(c,original)).thenReturn(Optional.of(new AppointmentRepository.Visit(903,301,11,21,END,
                "opposite",ACTOR,null)));
        rejects("ATTENDANCE_FACT_CONFLICT",NO_SHOW,"NO_SHOW_VERIFIED");
        when(repo.findAppointment(c,301,11)).thenReturn(Optional.of(row("已取消","cancelled")));
        rejects("ATTENDANCE_FACT_CONFLICT",ARRIVAL,"ARRIVAL_VERIFIED");
        allowed = false;
        assertThrows(SecurityException.class, () -> apply(ARRIVAL,"ARRIVAL_VERIFIED"));
        verify(repo,never()).updateAppointment(any(),any(),any());
    }

    @Test void recoveryRequiresTheExactPersistedVisit() throws Exception {
        var change = apply(ARRIVAL,"ARRIVAL_VERIFIED");
        var after = ArgumentCaptor.forClass(AppointmentRepository.Appointment.class);
        var visit = ArgumentCaptor.forClass(AppointmentRepository.Visit.class);
        verify(repo).insertVisit(eq(c),after.capture(),visit.capture());
        when(repo.findAppointment(c,301,11)).thenReturn(Optional.of(after.getValue()));
        var saved = JSON.createObjectNode();
        saved.set("after",change.after());
        saved.set("businessReference",change.businessReference());
        saved.set("verification",change.verification());
        var command = ArrivalNoShowService.command(ARRIVAL,301,11,KEY,input("ARRIVAL_VERIFIED"));
        assertFalse(service.matchesCommitted(c,command,saved));
        when(repo.findVisit(c,after.getValue())).thenReturn(Optional.of(visit.getValue()));
        assertTrue(service.matchesCommitted(c,command,saved));
    }

    @Test void visitWriteFailureRollsBackTheAppointmentUpdate() throws Exception {
        doThrow(new SQLException("visit conflict","23505")).when(repo).insertVisit(eq(c),any(),any());
        var failure = assertThrows(JdbcTransactions.Failure.class,
                () -> transactions.execute(connection -> apply(ARRIVAL,"ARRIVAL_VERIFIED")));
        assertEquals("23505",failure.sqlState());
        var order = inOrder(repo,c);
        order.verify(repo).updateAppointment(eq(c),eq(original),any());
        order.verify(repo).insertVisit(eq(c),any(),any());
        order.verify(c).rollback();
        verify(c,never()).commit();
    }

    private void rejects(String reason, ArrivalNoShowService.Action action,String finding) {
        assertEquals(reason,assertThrows(Rejection.class, () -> apply(action,finding)).getMessage());
    }

    private cn.adc.shared.audit.AuditDocuments.Change apply(ArrivalNoShowService.Action action,String finding) throws SQLException {
        var scope = new ResourceLockCoordinator().scope(c);
        scope.command(ArrivalNoShowService.reference(action,ACTOR,KEY));
        return service.apply(c,scope,action,ArrivalNoShowService.command(action,301,11,KEY,input(finding)),ACTOR);
    }

    private tools.jackson.databind.node.ObjectNode input(String finding) {
        var node = JSON.createObjectNode().put("finding",finding).put("verificationBasis","SYNTHETIC human evidence")
                .put("expectedAppointmentToken",AppointmentReadService.token(original));
        if ("ARRIVAL_VERIFIED".equals(finding)) node.put("actualClinicId","11").put("actualDentistId","21")
                .put("verifiedIdentityReference","SYNTHETIC identity");
        return node;
    }

    private static AppointmentRepository.Appointment row(String status,String cancellation) {
        return new AppointmentRepository.Appointment(301,201,
                new AppointmentRepository.Terms(VERSION,END.minusSeconds(1620),END,"original","reminder"),
                "acceptance",status,null,cancellation,ACTOR,END.minusSeconds(3600),null,null);
    }

    private PreparedStatement statement(String sql) throws SQLException {
        var p = mock(PreparedStatement.class);
        var row = mock(ResultSet.class);
        when(p.executeQuery()).thenReturn(row);
        when(row.next()).thenReturn(true);
        if (sql.contains("activation_status")) {
            when(row.getLong(1)).thenReturn(11L);
            when(row.getLong(2)).thenReturn(21L);
            when(row.getObject(3,OffsetDateTime.class)).thenReturn(END.minusSeconds(1620).atOffset(ZoneOffset.UTC));
            when(row.getObject(4,OffsetDateTime.class)).thenReturn(END.atOffset(ZoneOffset.UTC));
            when(row.getString(5)).thenReturn("EFFECTIVE");
        }
        return p;
    }
}
