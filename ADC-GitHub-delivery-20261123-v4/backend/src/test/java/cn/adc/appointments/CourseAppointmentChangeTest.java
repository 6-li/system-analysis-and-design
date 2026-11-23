package cn.adc.appointments;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.Set;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseAppointmentChangeTest {
    @Test void onlyReceptionAndConfiguredClinic(){
        var request=new MockHttpServletRequest();
        var stamp=new SourceStamp("SYNTHETIC test","v1","test",Instant.now(),true);
        request.getSession().setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,new StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC reception","TEST-csrf",stamp));
        assertDoesNotThrow(()->CourseAppointmentChange.authorization(request).require("UC03",Set.of(81001L)));
        assertThrows(SecurityException.class,()->CourseAppointmentChange.authorization(request).require("UC03",Set.of(81002L)));
        request.getSession().setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,new StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC dentist","TEST-csrf",stamp));
        assertThrows(SecurityException.class,()->CourseAppointmentChange.authorization(request).require("UC03",Set.of(81001L)));
    }
    @Test void sourceChecksBothOriginalAppointmentAndPatient()throws Exception{
        var c=mock(Connection.class);var p=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(p);when(p.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false);when(r.getLong(1)).thenReturn(88001L);when(r.getString(2)).thenReturn("99001");
        String source=CourseMiniBookingContext.PREFIX+"00000000-0000-0000-0000-000000000001:0";
        assertDoesNotThrow(()->CourseChangeSource.require(c,source,88001,99001L));
        reset(r);when(r.next()).thenReturn(true);when(r.getLong(1)).thenReturn(88002L);
        assertThrows(cn.adc.shared.audit.CommandRecovery.Rejection.class,()->CourseChangeSource.require(c,source,88001,99001L));
        verify(c,atLeastOnce()).prepareStatement(argThat(sql->sql.contains("patient_account_binding")&&sql.contains("NOT a.disabled")&&sql.contains("s.clinic_id=81001")));
    }
    @Test void changeIdsRequireTransaction()throws Exception{
        var c=mock(Connection.class);when(c.getAutoCommit()).thenReturn(true);
        assertThrows(SQLException.class,()->CourseBookingIds.change(c));verify(c,never()).prepareStatement(anyString());
    }
    @Test void visitBlockerReadsOnlyAppointmentReference()throws Exception{
        var c=mock(Connection.class);var p=mock(PreparedStatement.class);var r=mock(ResultSet.class);
        when(c.prepareStatement("SELECT 1 FROM adc.visit WHERE appointment_id=?")).thenReturn(p);
        when(p.executeQuery()).thenReturn(r);when(r.next()).thenReturn(true,false);
        var appointment=new AppointmentRepository.Appointment(99001,88001,null,null,null,null,null,null,null,null,null);
        assertTrue(new AppointmentRepository().hasVisit(c,appointment));
        assertFalse(new AppointmentRepository().hasVisit(c,appointment));verify(p,times(2)).setLong(1,99001L);
    }
}
