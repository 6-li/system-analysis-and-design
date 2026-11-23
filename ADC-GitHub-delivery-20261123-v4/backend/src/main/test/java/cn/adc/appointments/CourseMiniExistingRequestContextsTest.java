package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseMiniExistingRequestContextsTest {
    @Test void existingRequestVersionZeroMapsToTheOriginalAppointmentWithoutInventingAnAcceptance()throws Exception{
        var c=mock(Connection.class);var p=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(p);when(p.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true,false,true,false);
        var appointment=mock(AppointmentRepository.Appointment.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(appointment.id()).thenReturn(901L);when(appointment.patientId()).thenReturn(88001L);
        var id=UUID.fromString("00000000-0000-4000-8000-000000000101");String ref=CourseMiniBookingContext.PREFIX+id+":0";
        var contexts=new CourseMiniExistingRequestContexts("SYNTHETIC classroom mini account");
        assertEquals(new JdbcMiniChangeSource.Context(id,0),contexts.require(c,ref,appointment,null,"SYNTHETIC separately verified acceptance"));
        assertEquals(new JdbcMiniCancellationSource.Context(id,0),contexts.require(c,ref,appointment,"SYNTHETIC separately verified cancellation"));
        verify(p).setString(5,"CHANGE");verify(p).setString(5,"CANCEL");verify(p,times(2)).setString(7,"901");
    }
}
