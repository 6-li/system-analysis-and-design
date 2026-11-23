package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseMiniBookingContextTest {
    @Test void resolvesTheExactAssociatedRequestAndRetainsItsStableReference() throws Exception {
        var connection = fake(Connection.class);
        var query = fake(PreparedStatement.class);
        var rows = fake(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("{\"type\":\"NEW\"}"); // legacy request without a patient selection
        var id = UUID.fromString("00000000-0000-4000-8000-000000000101");
        String reference = CourseMiniBookingContext.PREFIX + id + ":1";
        var start = Instant.parse("2026-11-08T10:30:00Z");
        var scheme = new InitialBookingReview.AcceptedScheme(100000, new Version(87001, 2),
                81001, 79001, start, start.plusSeconds(1620), "SYNTHETIC treatment", new Basis(85001, 1, 27));
        var contexts = new CourseMiniBookingContext();
        assertEquals(new JdbcMiniBookingSource.Context(id, 1), contexts.require(connection, reference, scheme));
        verify(query).setObject(1, id);
        verify(query).setInt(2, 1);
        verify(query).setLong(3, 100000);
        verify(query).setString(4, "81001");
        assertEquals(id, contexts.originalRequest(connection, reference));
        when(rows.next()).thenReturn(false);
        assertThrows(cn.adc.shared.audit.CommandRecovery.Rejection.class,
                () -> contexts.require(connection, reference, scheme));
        assertThrows(cn.adc.shared.audit.CommandRecovery.Rejection.class,
                () -> CourseMiniBookingContext.parse(CourseMiniBookingContext.PREFIX + id + ":01"));
    }

    private static <T> T fake(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.PROXY));
    }
}
