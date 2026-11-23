package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CoursePatientIntakeTest {
    @Test void matchesOnlyTheVerifiedSyntheticRequestWithinTheReceptionClinic() throws Exception {
        var c = mock(Connection.class, withSettings().mockMaker(MockMakers.PROXY));
        var query = mock(PreparedStatement.class, withSettings().mockMaker(MockMakers.PROXY));
        var rows = mock(ResultSet.class, withSettings().mockMaker(MockMakers.PROXY));
        when(c.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        String request = "00000000-0000-4000-8000-000000000101";
        var input = AuditDocuments.JSON.createObjectNode().put("identityInformation", "SYNTHETIC patient");
        input.putObject("source").put("requestId", request).put("identityMatchBasis", "SYNTHETIC classroom identity");
        assertThrows(SecurityException.class, () ->
                CoursePatientIntakeConfiguration.verifyFixtureSource(c, "SYNTHETIC dentist", 81001, null, input));
        verifyNoInteractions(c);
        CoursePatientIntakeConfiguration.verifyFixtureSource(c, "SYNTHETIC reception", 81001, null, input);
        verify(query).setObject(1, UUID.fromString(request));
        verify(query).setString(2, "81001");
        verify(query).setString(3, "SYNTHETIC patient");
        assertThrows(SecurityException.class, () ->
                CoursePatientIntakeConfiguration.verifyFixtureSource(c, "SYNTHETIC reception", 81002, null, input));
    }
}
