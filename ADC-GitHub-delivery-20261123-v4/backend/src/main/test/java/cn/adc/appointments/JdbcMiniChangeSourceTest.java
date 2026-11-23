package cn.adc.appointments;

import cn.adc.shared.audit.CommandRecovery.Rejection;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JdbcMiniChangeSourceTest {
    @Test void rejectsWhenReferenceResolvesToDifferentRequestAfterLock() throws Exception {
        var c = mock(Connection.class);
        var query = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        var original = new AppointmentRepository.Appointment(301, 201, null, null, "待到诊",
                null, null, null, null, null, null);
        var context = new JdbcMiniChangeSource.Context(UUID.randomUUID(), 2);
        var contexts = mock(JdbcMiniChangeSource.Contexts.class);
        when(contexts.require(c, "source", original, null, "accepted")).thenReturn(context);
        when(contexts.originalRequest(c, "source")).thenReturn(UUID.randomUUID());
        var source = new JdbcMiniChangeSource(contexts, Clock.systemUTC());

        assertEquals("MINI_CHANGE_SOURCE_STALE", assertThrows(Rejection.class,
                () -> source.requireCurrent(c, "source", original, null, "accepted")).getMessage());
        verify(query).setObject(1, context.requestId());
        verify(query).setInt(2, 2);
        verify(query).setLong(3, 201);
        verify(query).setString(4, "301");
        var order = inOrder(query, contexts);
        order.verify(contexts).require(c, "source", original, null, "accepted");
        order.verify(query).executeQuery();
        order.verify(contexts).require(c, "source", original, null, "accepted");
    }
}
