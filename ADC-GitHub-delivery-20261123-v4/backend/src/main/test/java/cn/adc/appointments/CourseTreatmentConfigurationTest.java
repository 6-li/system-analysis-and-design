package cn.adc.appointments;

import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CourseTreatmentConfigurationTest {
    @Test void doctorLoadsTheConsentedScopeThroughTheRealTreatmentService() throws Exception {
        var source=mock(javax.sql.DataSource.class);
        var c=mock(java.sql.Connection.class);
        when(source.getConnection()).thenReturn(c);
        when(c.createStatement()).thenReturn(mock(java.sql.Statement.class));
        var scope=mock(java.sql.PreparedStatement.class);
        var allowed=mock(java.sql.ResultSet.class);
        when(c.prepareStatement(contains("v.actual_dentist_id=?"))).thenReturn(scope);
        when(scope.executeQuery()).thenReturn(allowed);
        when(allowed.next()).thenReturn(true);
        var query=mock(java.sql.PreparedStatement.class);
        var row=mock(java.sql.ResultSet.class);
        when(c.prepareStatement(contains("FROM adc.treatment_consent_result WHERE visit_id=?"))).thenReturn(query);
        when(query.executeQuery()).thenReturn(row);
        when(row.next()).thenReturn(true,false);
        when(row.getLong(1)).thenReturn(71002L);
        when(row.getString(2)).thenReturn("SYNTHETIC treatment");
        when(row.getString(3)).thenReturn("同意");
        var session=new MockHttpSession();
        session.setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC dentist","SYNTHETIC-CSRF",
                        new SourceStamp("SYNTHETIC","course-v1","course", Instant.now(),true)));
        var access=CourseTreatmentConfiguration.treatmentAccess(81001,79001,new JdbcTransactions(source));
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new VisitTreatmentController(access))
                .build().perform(get("/api/v1/staff/visits/42/treatment").session(session)
                        .header("X-CSRF-Token","SYNTHETIC-CSRF").param("patientId","88001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.consents[0].consentResultId").value("71002"))
                .andExpect(jsonPath("$.consents[0].proposedTreatment").value("SYNTHETIC treatment"))
                .andExpect(jsonPath("$.consents[0].consentResult").value("同意"));
        verify(scope,times(2)).setLong(3,79001L);
        verify(scope,times(2)).setLong(4,88001L);
        verify(c).commit();
    }
}
