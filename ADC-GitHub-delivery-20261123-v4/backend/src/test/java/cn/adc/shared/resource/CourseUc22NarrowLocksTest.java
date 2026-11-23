package cn.adc.shared.resource;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CourseUc22NarrowLocksTest {
    @Test void courseUc22UsesNarrowFunctionsWithoutDirectDentistOrAppointmentLocks()throws Exception{
        var c=mock(Connection.class);var q=mock(PreparedStatement.class);when(c.prepareStatement(anyString())).thenReturn(q);
        var locks=new ResourceLockCoordinator(true).scope(c);locks.command("TEST");locks.schedules(List.of(10L));
        locks.dentists(List.of(2L,1L,2L));locks.appointments(List.of(3L));locks.roots(List.of(4L));
        verify(c,times(2)).prepareStatement("SELECT adc_uc21_lock.lock_dentist(?)");
        verify(c).prepareStatement("SELECT adc_uc21_lock.lock_appointment(?)");verify(c).prepareStatement("SELECT adc_uc21_lock.lock_resolution(?)");
        verify(c,never()).prepareStatement(contains("FROM adc.dentist"));verify(c,never()).prepareStatement(contains("FROM adc.appointment "));
        assertThrows(IllegalStateException.class,()->locks.dentists(List.of(1L)));
    }
    @Test void defaultLockModeAndTransactionRequirementRemainUnchanged()throws Exception{
        var c=mock(Connection.class);var q=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(q);when(q.executeQuery()).thenReturn(r);when(r.next()).thenReturn(true);
        var locks=new ResourceLockCoordinator().scope(c);locks.command("TEST");locks.dentists(List.of(1L));
        verify(c).prepareStatement("SELECT dentist_id FROM adc.dentist WHERE dentist_id=? FOR UPDATE");
        when(c.getAutoCommit()).thenReturn(true);assertThrows(IllegalArgumentException.class,()->new ResourceLockCoordinator(true).scope(c));
    }
}
