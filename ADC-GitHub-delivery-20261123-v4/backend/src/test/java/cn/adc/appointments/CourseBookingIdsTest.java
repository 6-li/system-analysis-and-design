package cn.adc.appointments;

import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CourseBookingIdsTest {
    @Test
    void allocatesIndependentPositiveIdsAndFailsClosed() throws Exception {
        var connection = fake(Connection.class);
        var appointment = fake(PreparedStatement.class);
        var audit = fake(PreparedStatement.class);
        var appointmentRows = fake(ResultSet.class);
        var auditRows = fake(ResultSet.class);
        when(connection.prepareStatement("SELECT nextval('adc.course_appointment_id_seq'::regclass)"))
                .thenReturn(appointment);
        when(connection.prepareStatement("SELECT nextval('adc.course_uc_audit_id_seq'::regclass)")).thenReturn(audit);
        when(appointment.executeQuery()).thenReturn(appointmentRows);
        when(audit.executeQuery()).thenReturn(auditRows);
        when(appointmentRows.next()).thenReturn(true, false, true);
        when(appointmentRows.getLong(1)).thenReturn(100000L, 0L);
        when(auditRows.next()).thenReturn(true, false);
        when(auditRows.getLong(1)).thenReturn(100021L);
        assertEquals(100000L, CourseBookingIds.appointment(connection));
        assertEquals(100021L, CourseBookingIds.audit(connection));
        assertEquals("22003", assertThrows(SQLException.class, () -> CourseBookingIds.appointment(connection)).getSQLState());
        when(connection.getAutoCommit()).thenReturn(true);
        assertEquals("25000", assertThrows(SQLException.class, () -> CourseBookingIds.audit(connection)).getSQLState());
        verify(connection, never()).commit();
    }

    private static <T> T fake(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.PROXY));
    }
}
