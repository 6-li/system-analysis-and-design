package cn.adc.appointments;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoursePatientAccountConfigurationTest {
    @Test void dedicatedDatabaseGuardRejectsOtherInstancesBeforeConnecting() {
        var env=new MockEnvironment().withProperty("adc.course.database","adc_other")
                .withProperty("adc.course.database-port","55432");
        assertThrows(IllegalArgumentException.class,()->new CoursePatientAccountConfiguration().patientAccountStore(env));
    }
    @Test void disabledAccountLosesExistingSessionWithoutGrantingSharedPrincipal() {
        var store=mock(PatientAccounts.Store.class); UUID id=UUID.randomUUID();
        when(store.enabled(id)).thenReturn(true,false);
        var sessions=new CoursePatientAccountConfiguration().patientAccountSessions(store);
        String token=sessions.issue(PatientAccounts.principal(id));
        assertTrue(sessions.activeAccount(token).isPresent());
        assertTrue(sessions.activeAccount(token).isEmpty());
        assertTrue(sessions.activeAccount(sessions.issue("SYNTHETIC-course-mini")).isEmpty());
    }
}
