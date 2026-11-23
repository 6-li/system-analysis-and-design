package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PatientDirectoryQueryTest {
    @Test void onlyVerifiedIdentityCanLookUpMinimalPatientSummary() throws Exception {
        var db = mock(DataSource.class);
        var c = mock(Connection.class);
        var query = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(db.getConnection()).thenReturn(c);
        when(c.createStatement()).thenReturn(mock(Statement.class));
        when(c.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getLong(1)).thenReturn(9007199254740993L);
        when(rows.getString(2)).thenReturn("SYNTHETIC Patient");
        var auth = new StaffAuthorization(() -> Optional.of("TEST staff"), () -> Map.of("TEST staff",
                new StaffAuthorization.Grant("test", Set.of("BOOKING_RECEPTION"), Set.of("UC01"), Set.of(1L), false)));
        var service = new PatientDirectoryQuery(new JdbcTransactions(db), auth,
                (connection, ref, actor, clinic) -> "SYNTHETIC verified".equals(ref)
                        ? Optional.of("SYNTHETIC internal identity") : Optional.empty());
        var request = JSON.createObjectNode().put("clinicId", "1").put("verifiedIdentityContext", "untrusted");
        assertThrows(SecurityException.class, () -> service.query(request));
        verify(c, never()).prepareStatement(anyString());
        request.put("clinicId", "2").put("verifiedIdentityContext", "SYNTHETIC verified");
        assertThrows(SecurityException.class, () -> service.query(request));
        verify(c, never()).prepareStatement(anyString());
        request.put("clinicId", "1");
        var result = service.query(request).path("items").get(0);
        assertEquals(Set.of("patientId", "displayName"), result.propertyNames());
        assertEquals("9007199254740993", result.path("patientId").asText());
        verify(query).setString(1, "SYNTHETIC internal identity");
        when(rows.next()).thenReturn(true, true);
        assertThrows(IllegalArgumentException.class, () -> service.query(request));
    }
}
