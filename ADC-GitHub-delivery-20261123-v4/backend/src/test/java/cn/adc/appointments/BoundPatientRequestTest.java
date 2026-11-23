package cn.adc.appointments;
import cn.adc.shared.audit.AuditDocuments;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BoundPatientRequestTest {
    @Test void verifiedAccountReusesOnlyItsBoundFileAndRejectsDifferentIdentity()throws Exception{
        var c=mock(Connection.class);var q=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(q);when(q.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true);when(r.getLong(1)).thenReturn(71004L);when(r.getString(2)).thenReturn("TEST-identity");
        var input=AuditDocuments.JSON.createObjectNode();var identity=input.putObject("identity").put("patientIdentification","TEST-identity");
        String account=PatientAccounts.principal(UUID.randomUUID());
        assertEquals(71004L,BoundPatientRequest.resolve(c,account,input));
        identity.put("patientIdentification","TEST-someone-else");
        assertThrows(SecurityException.class,()->BoundPatientRequest.resolve(c,account,input));
        when(r.next()).thenReturn(false);assertNull(BoundPatientRequest.resolve(c,account,input));
        assertThrows(SecurityException.class,()->BoundPatientRequest.resolve(c,"SYNTHETIC-course-mini",input));
    }
}
