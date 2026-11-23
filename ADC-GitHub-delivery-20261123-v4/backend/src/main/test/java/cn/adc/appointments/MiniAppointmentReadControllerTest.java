package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MiniAppointmentReadControllerTest {
    @Test void authorizedVisitHistoryReturnsOnlyTheSummary() throws Exception {
        var database = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getLong(1)).thenReturn(51L);
        when(rows.getObject(2, java.time.OffsetDateTime.class))
                .thenReturn(java.time.OffsetDateTime.parse("2026-11-18T10:30:00+08:00"));
        when(rows.getLong(3)).thenReturn(81001L);
        when(rows.getString(4)).thenReturn("[\"SYNTHETIC 检查\"]");
        var sessions = mock(MiniRequestSession.Sessions.class);
        when(sessions.activeAccount("SYNTHETIC-token")).thenReturn(Optional.of("SYNTHETIC-account"));
        var scope = mock(MiniAppointmentReadController.PatientScope.class);
        when(scope.mayRead(connection, "SYNTHETIC-account", 100L)).thenReturn(true);
        when(scope.authorizations(connection, "SYNTHETIC-account")).thenReturn(java.util.List.of(
                new MiniAppointmentReadController.Authorization("SYNTHETIC-grant", 100L,
                        MiniAppointmentReadController.Authorization.Kind.GUARDIAN,
                        java.util.List.of(MiniAppointmentReadController.VISIT_HISTORY_READ), 1)));
        var controller = new MiniAppointmentReadController(new MiniRequestSession(code -> Optional.empty(), sessions),
                new JdbcTransactions(database), scope);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/v1/mini/visit-history").param("patientId", "100")
                        .header("Authorization", "Bearer SYNTHETIC-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].visitId").value("51"))
                .andExpect(jsonPath("$.items[0].clinicId").value("81001"))
                .andExpect(jsonPath("$.items[0].performedProjects[0]").value("SYNTHETIC 检查"));
        verify(statement).setLong(1, 100L);
        verify(scope, times(2)).authorizations(connection, "SYNTHETIC-account");
    }

    @Test void patientScopeMustBeVerifiedBeforeReadingAndAgainBeforeResponse() throws Exception {
        var database = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
        var session = new MiniRequestSession(code -> Optional.empty(), new MiniRequestSession.Sessions() {
            public String issue(String account) { throw new AssertionError(); }
            public Optional<String> activeAccount(String token) {
                return "SYNTHETIC-token".equals(token) ? Optional.of("SYNTHETIC-account") : Optional.empty();
            }
        });
        var allowed = new AtomicBoolean(true);
        var revokeOnRead = new AtomicBoolean();
        var controller = new MiniAppointmentReadController(session, new JdbcTransactions(database), (c, account, patient) ->
                patient == 100 && "SYNTHETIC-account".equals(account) &&
                        (revokeOnRead.get() ? allowed.getAndSet(false) : allowed.get()));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String path = "/api/v1/mini/appointments";
        mvc.perform(get(path).param("patientId", "100")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).param("patientId", "101").header("Authorization", "Bearer SYNTHETIC-token"))
                .andExpect(status().isForbidden());
        verify(connection, never()).prepareStatement(anyString());
        mvc.perform(get(path).param("patientId", "100").header("Authorization", "Bearer SYNTHETIC-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(0));
        revokeOnRead.set(true);
        mvc.perform(get(path).param("patientId", "100").header("Authorization", "Bearer SYNTHETIC-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.items").doesNotExist());
    }
}
