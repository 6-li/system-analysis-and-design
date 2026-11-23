package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class JdbcMiniRequestIntakeStoreTest {
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement request = mock(PreparedStatement.class);
    private final PreparedStatement audit = mock(PreparedStatement.class);

    @org.junit.jupiter.api.BeforeEach
    void fixture() throws Exception {
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        when(connection.prepareStatement(startsWith("INSERT INTO adc_mini.preliminary_request"))).thenReturn(request);
        when(connection.prepareStatement(startsWith("INSERT INTO adc_mini.operation_audit_event"))).thenReturn(audit);
    }

    @Test void commitsReceiptAndAuditInOrder() throws Exception {
        when(request.executeUpdate()).thenReturn(1);
        when(audit.executeUpdate()).thenReturn(1);
        var receipt = store().saveOnce("TEST-account", "TEST-key", "TEST-hash", JSON.createObjectNode());
        assertEquals("TEST-hash", receipt.payloadHash());
        assertDoesNotThrow(() -> java.util.UUID.fromString(receipt.requestId()));
        var order = inOrder(request, audit, connection);
        order.verify(request).executeUpdate();
        order.verify(audit).executeUpdate();
        order.verify(connection).commit();
    }

    @Test void courseHttpIntakeUsesAuthenticatedAccountAndCommitsRequestWithAudit() throws Exception {
        when(request.executeUpdate()).thenReturn(1);
        when(audit.executeUpdate()).thenReturn(1);
        var sessions = new MiniRequestSession(code -> java.util.Optional.empty(), new MiniRequestSession.Sessions() {
            @Override public String issue(String account) { throw new AssertionError(); }
            @Override public java.util.Optional<String> activeAccount(String token) {
                return "SYNTHETIC-token".equals(token) ? java.util.Optional.of("SYNTHETIC-account") : java.util.Optional.empty();
            }
        });
        var intake = CourseMiniIntakeConfiguration.intake(sessions, new JdbcTransactions(source), Clock.systemUTC());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new MiniIntakeController(sessions, intake)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/mini/requests")
                .header("Authorization", "Bearer SYNTHETIC-token")
                .header("Idempotency-Key", "0123456789abcdef0123456789abcdef")
                .contentType("application/json").content("""
                        {"type":"NEW","actingMode":"SELF","identity":{"patientIdentification":"SYNTHETIC",
                         "applicantInformation":"SYNTHETIC","contactInformation":"synthetic@example.invalid"},
                         "intendedClinicId":"81001","preferred":{"date":"2026-11-18"},
                         "treatmentNeed":"SYNTHETIC","bookingIntent":{"visitType":"INITIAL"}}
                        """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.receiptStatus").value("CONFIRMED_RECEIVED"));
        verify(request).setString(2, "SYNTHETIC-account");
        var order = inOrder(request, audit, connection);
        order.verify(request).executeUpdate();
        order.verify(audit).executeUpdate();
        order.verify(connection).commit();
    }

    @Test void auditFailureCannotCommitTheRequest() throws Exception {
        when(request.executeUpdate()).thenReturn(1);
        when(audit.executeUpdate()).thenThrow(new SQLException("synthetic", "23514"));
        assertThrows(JdbcTransactions.Failure.class,
                () -> store().saveOnce("TEST-account", "TEST-key", "TEST-hash", JSON.createObjectNode()));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test void duplicateReadsTheOriginalReceiptScopedToTheAccount() throws Exception {
        var select = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(startsWith("SELECT r.payload_hash"))).thenReturn(select);
        when(select.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getString(1)).thenReturn("old-hash");
        when(rows.getString(2)).thenReturn("old-id");
        assertEquals(new MiniNewRequestIntake.Receipt("old-hash", "old-id"),
                store().saveOnce("TEST-account", "TEST-key", "new-hash", JSON.createObjectNode()));
        verify(select).setString(1, "TEST-account");
        verify(select).setString(2, "TEST-key");
        verifyNoInteractions(audit);
    }

    private JdbcMiniRequestIntakeStore store() {
        return new JdbcMiniRequestIntakeStore(new JdbcTransactions(source), Clock.systemUTC());
    }
}
