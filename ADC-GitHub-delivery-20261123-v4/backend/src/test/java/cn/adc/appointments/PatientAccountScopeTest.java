package cn.adc.appointments;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PatientAccountScopeTest {
    @Test void onlyExplicitBindingGrantsOneSelfPatientAndNoSharedHistory() throws Exception {
        var c=mock(Connection.class);var q=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(q);when(q.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true);when(r.getLong(1)).thenReturn(71004L);
        var scope=new JdbcPatientAccountScope();String account=PatientAccounts.principal(UUID.randomUUID());
        assertTrue(scope.mayRead(c,account,71004));assertFalse(scope.mayRead(c,account,71005));
        assertEquals(MiniAppointmentReadController.Authorization.Kind.SELF,scope.authorizations(c,account).get(0).kind());
        verify(q,atLeastOnce()).setObject(1,JdbcPatientAccountScope.accountId(account));
        clearInvocations(c);
        assertTrue(scope.authorizations(c,"SYNTHETIC-course-mini").isEmpty());
        assertTrue(scope.authorizations(c,"ACCOUNT:invalid").isEmpty());verifyNoInteractions(c);
    }
    @Test void missingBindingDeniesAndInvalidReceptionCannotInsert() throws Exception {
        var c=mock(Connection.class);var q=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(q);when(q.executeQuery()).thenReturn(r);
        assertTrue(new JdbcPatientAccountScope().authorizations(c,PatientAccounts.principal(UUID.randomUUID())).isEmpty());
        clearInvocations(c);
        assertThrows(SecurityException.class,()->new PatientAccountBinding().bind(c,UUID.randomUUID(),1,81001,71004,"SYNTHETIC dentist","TEST-verified"));
        assertThrows(SecurityException.class,()->new PatientAccountBinding().bind(c,UUID.randomUUID(),1,81001,71004,"SYNTHETIC reception"," "));
        verifyNoInteractions(c);
    }
    @Test void requestDirectoryRejectsSharedPrincipalBeforeQueryAndKeepsOwnerInSql() throws Exception {
        var c=mock(Connection.class);var service=new PatientAccountRequests();
        assertThrows(SecurityException.class,()->service.list(c,"SYNTHETIC-course-mini",null));verifyNoInteractions(c);
        var q=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(q);when(q.executeQuery()).thenReturn(r);
        String account=PatientAccounts.principal(UUID.randomUUID());
        assertEquals(0,service.list(c,account,null).path("items").size());
        verify(q).setString(1,account);verify(c).prepareStatement(contains("r.applicant_account=? AND NOT a.disabled"));
    }
}
