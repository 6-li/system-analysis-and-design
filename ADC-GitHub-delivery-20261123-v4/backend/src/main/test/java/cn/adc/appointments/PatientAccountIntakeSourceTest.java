package cn.adc.appointments;
import cn.adc.shared.audit.AuditDocuments;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PatientAccountIntakeSourceTest {
    @Test void accountModeRequiresEnabledStoredOwnerAndUnverifiedSourceCannotBind() throws Exception {
        var c=mock(Connection.class);var q=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(q);when(q.executeQuery()).thenReturn(r);when(r.next()).thenReturn(true);
        var input=AuditDocuments.JSON.createObjectNode().put("identityInformation","TEST-id");
        input.putObject("source").put("requestId",UUID.randomUUID().toString()).put("identityMatchBasis","TEST-verified");
        CoursePatientIntakeConfiguration.verifyFixtureSource(c,"SYNTHETIC reception",81001,null,input,true);
        verify(c).prepareStatement(contains("AND NOT a.disabled"));
        clearInvocations(c);when(r.next()).thenReturn(false);
        assertThrows(SecurityException.class,()->new PatientAccountBinding().bind(c,UUID.randomUUID(),1,81001,71004,"SYNTHETIC reception","TEST-verified"));
        verify(q,never()).executeUpdate();
    }
}
