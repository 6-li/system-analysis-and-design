package cn.adc.appointments;

import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import java.time.Instant;
import java.time.OffsetDateTime;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CourseConsentConfigurationTest {
    @Test void doctorReadsExistingCommunicationThroughActualCourseControllerAndService() throws Exception {
        var source = mock(javax.sql.DataSource.class);
        var c = mock(java.sql.Connection.class);
        when(source.getConnection()).thenReturn(c);
        when(c.createStatement()).thenReturn(mock(java.sql.Statement.class));
        var scope = mock(java.sql.PreparedStatement.class);
        var allowed = mock(java.sql.ResultSet.class);
        when(c.prepareStatement(contains("v.actual_dentist_id=?"))).thenReturn(scope);
        when(scope.executeQuery()).thenReturn(allowed);
        when(allowed.next()).thenReturn(true);
        var query = mock(java.sql.PreparedStatement.class);
        var row = mock(java.sql.ResultSet.class);
        when(c.prepareStatement(contains("FROM adc.treatment_consent_result WHERE visit_id=?"))).thenReturn(query);
        when(query.executeQuery()).thenReturn(row);
        when(row.next()).thenReturn(true,false);
        when(row.getLong(1)).thenReturn(71002L);
        when(row.getString(2)).thenReturn("SYNTHETIC patient");
        when(row.getString(4)).thenReturn("SYNTHETIC treatment");
        when(row.getString(5)).thenReturn("同意");
        when(row.getString(7)).thenReturn("SYNTHETIC dentist");
        when(row.getObject(8,OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-11-19T01:00:00Z"));
        when(row.getLong(9)).thenReturn(79001L);
        var session = new MockHttpSession();
        session.setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC dentist","SYNTHETIC-CSRF",
                        new SourceStamp("SYNTHETIC","course-v1","course", Instant.now(),true)));
        var access = CourseConsentConfiguration.consentAccess(81001,79001,new JdbcTransactions(source));
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new VisitConsentController(access))
                .build().perform(get("/api/v1/staff/visits/42/consent").session(session)
                        .header("X-CSRF-Token","SYNTHETIC-CSRF").param("patientId","88001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].consentResultId").value("71002"))
                .andExpect(jsonPath("$.entries[0].consentResult").value("同意"))
                .andExpect(jsonPath("$.entries[0].dentistId").value("79001"));
        verify(scope,times(2)).setLong(3,79001L);
        verify(scope,times(2)).setLong(4,88001L);
        verify(c).commit();
    }
}
