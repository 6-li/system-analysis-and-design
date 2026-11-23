package cn.adc.appointments;

import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CourseHealthConfigurationTest {
    @Test void courseDoctorReadsHealthThroughTheRealControllerAccessAndService() throws Exception {
        var source = mock(javax.sql.DataSource.class);
        var connection = mock(java.sql.Connection.class);
        var local = mock(java.sql.Statement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(local);
        var scope = mock(java.sql.PreparedStatement.class);
        var eligible = mock(java.sql.ResultSet.class);
        when(connection.prepareStatement(contains("v.actual_dentist_id=?"))).thenReturn(scope);
        when(scope.executeQuery()).thenReturn(eligible);
        when(eligible.next()).thenReturn(true);
        var current = mock(java.sql.PreparedStatement.class);
        var snapshot = mock(java.sql.ResultSet.class);
        when(connection.prepareStatement(contains("ORDER BY h.changed_at"))).thenReturn(current);
        when(current.executeQuery()).thenReturn(snapshot);
        when(snapshot.next()).thenReturn(true);
        when(snapshot.getLong(1)).thenReturn(71000L);
        when(snapshot.getString(2)).thenReturn("SYNTHETIC verified history");
        when(snapshot.getString(3)).thenReturn("SYNTHETIC verified allergy");
        var previous = mock(java.sql.PreparedStatement.class);
        var treatments = mock(java.sql.ResultSet.class);
        when(connection.prepareStatement(contains("JOIN adc.visit current_visit"))).thenReturn(previous);
        when(previous.executeQuery()).thenReturn(treatments);
        when(treatments.next()).thenReturn(false);
        var access = CourseHealthConfiguration.healthAccess(81001,79001,new cn.adc.shared.db.JdbcTransactions(source));
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC dentist", "SYNTHETIC-CSRF",
                        new SourceStamp("SYNTHETIC", "course-v1", "course", Instant.now(), true)));
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new VisitHealthController(access))
                .build().perform(get("/api/v1/staff/visits/42/health").session(session)
                        .header("X-CSRF-Token","SYNTHETIC-CSRF").param("patientId","88001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snapshot.id").value("71000"))
                .andExpect(jsonPath("$.snapshot.medicalHistory").value("SYNTHETIC verified history"))
                .andExpect(jsonPath("$.priorTreatments").isEmpty());
        verify(scope, times(2)).setLong(3,79001L);
        verify(scope, times(2)).setLong(4,88001L);
        verify(connection).commit();
    }
    @Test void courseDoctorSessionOpensTheExistingHealthServiceWithoutConnectingToADatabase() {
        var env = new MockEnvironment().withProperty("adc.course.clinic-id", "81001")
                .withProperty("adc.course.dentist-id", "79001")
                .withProperty("adc.course.database", "adc_course_validation")
                .withProperty("adc.course.database-port", "55432")
                .withProperty("ADC_COURSE_DB_HEALTH_PASSWORD", "SYNTHETIC");
        var access = new CourseHealthConfiguration().courseHealthAccess(env);
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC dentist", "SYNTHETIC-CSRF",
                        new SourceStamp("SYNTHETIC", "course-v1", "course", Instant.now(), true)));
        var scope = access.open(request, "SYNTHETIC-CSRF");
        assertEquals(81001, scope.clinic());
        assertNotNull(scope.service());
    }
}
