package cn.adc.appointments;

import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CourseDentistScheduleTest {
    @Test void readsOnlyTheBoundDentistAndClinicWithoutWritingOrExposingPatientDetails() throws Exception {
        var source = fake(DataSource.class);
        var c = fake(Connection.class);
        var query = fake(PreparedStatement.class);
        var row = fake(ResultSet.class);
        when(source.getConnection()).thenReturn(c);
        when(c.createStatement()).thenReturn(fake(Statement.class));
        when(c.prepareStatement(anyString())).thenAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertTrue(call.<String>getArgument(0).contains("v.activation_status='EFFECTIVE'"));
            return query;
        });
        when(query.executeQuery()).thenReturn(row);
        when(row.next()).thenReturn(true, false);
        when(row.getLong(1)).thenReturn(87001L);
        when(row.getInt(2)).thenReturn(2);
        when(row.getObject(3, OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-12-01T09:00:00+08:00"));
        when(row.getObject(4, OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-12-01T12:00:00+08:00"));
        when(row.getLong(5)).thenReturn(2L);
        var mvc = MockMvcBuilders.standaloneSetup(new CourseDentistSchedule(new JdbcTransactions(source), 81001, 79001)).build();
        mvc.perform(get("/api/v1/staff/my-schedule").session(session("reception"))).andExpect(status().isForbidden());
        verifyNoInteractions(source);
        mvc.perform(get("/api/v1/staff/my-schedule").session(session("dentist")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].versionNo").value(2))
                .andExpect(jsonPath("$.items[0].appointmentCount").value(2))
                .andExpect(jsonPath("$.items[0].patientId").doesNotExist());
        verify(query).setLong(1, 79001);
        verify(query).setLong(2, 81001);
        verify(query, never()).executeUpdate();
    }

    private static MockHttpSession session(String role) {
        var session = new MockHttpSession();
        session.setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC " + role, "SYNTHETIC",
                        new SourceStamp("SYNTHETIC", "course", "fixture", Instant.now(), true)));
        return session;
    }
    private static <T> T fake(Class<T> type) { return mock(type, withSettings().mockMaker(MockMakers.PROXY)); }
}
