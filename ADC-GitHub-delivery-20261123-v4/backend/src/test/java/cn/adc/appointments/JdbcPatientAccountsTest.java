package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcPatientAccountsTest {
    @Test void credentialsUseParametersAndDuplicateDoesNotOverwriteExistingDigest() throws Exception {
        var source=mock(DataSource.class); var c=mock(Connection.class);
        var setup=mock(Statement.class); var q=mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(c); when(c.createStatement()).thenReturn(setup);
        when(c.prepareStatement(anyString())).thenReturn(q); when(q.executeUpdate()).thenReturn(0);
        UUID id=UUID.randomUUID();
        assertFalse(new JdbcPatientAccounts(new JdbcTransactions(source)).create(id,"test_user","TEST-digest"));
        verify(q).setObject(1,id); verify(q).setString(2,"test_user"); verify(q).setString(3,"TEST-digest");
        verify(c).prepareStatement(contains("ON CONFLICT (username) DO NOTHING")); verify(c).commit();
    }
    @Test void failedInsertRollsBackAndReportsFailureInsteadOfSuccessfulRegistration() throws Exception {
        var source=mock(DataSource.class); var c=mock(Connection.class);
        when(source.getConnection()).thenReturn(c); when(c.createStatement()).thenReturn(mock(Statement.class));
        var q=mock(PreparedStatement.class); when(c.prepareStatement(anyString())).thenReturn(q);
        when(q.executeUpdate()).thenThrow(new SQLException("TEST-only","42501"));
        assertThrows(JdbcTransactions.Failure.class,()->new JdbcPatientAccounts(new JdbcTransactions(source))
                .create(UUID.randomUUID(),"test_user","TEST-digest"));
        verify(c).rollback(); verify(c,never()).commit();
    }
}
