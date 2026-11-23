package cn.adc.scheduling;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static cn.adc.scheduling.ImpactAssessmentService.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ImpactAssessmentTest {
    private static final Instant START = Instant.parse("2026-11-14T01:00:00Z");
    private final ImpactAssessmentService service = new ImpactAssessmentService();
    private final Schedule historical = schedule(10, 1, 1, 0, 3600);
    private final Appointment appointment = new Appointment(50, historical, window(0, 1800));

    @Test void fullCoverageRetainsHistoricalReferenceAndExcludesTheReplacedVersion() {
        assertEquals(Assessment.SUPPORTED, service.assess(appointment,
                schedule(10, 2, 1, 0, 1800), List.of(historical, schedule(11, 1, 2, 1800, 3600))));
        assertEquals(1, appointment.historical().version());
    }

    @Test void onlyTheStartFitsOrClinicDiffers() {
        assertEquals(Assessment.REQUIRES_PROCESSING,
                service.assess(appointment, schedule(10, 2, 1, 0, 1799), List.of(historical)));
        assertEquals(Assessment.REQUIRES_PROCESSING,
                service.assess(appointment, schedule(10, 2, 2, 0, 1800), List.of(historical)));
    }

    @Test void otherIdsCannotTakeOverAndCrossClinicSameDentistConflicts() {
        assertEquals(Assessment.UNAFFECTED,
                service.assess(appointment, schedule(11, 1, 1, 0, 1800), List.of(historical)));
        assertEquals(Assessment.REQUIRES_PROCESSING, service.assess(appointment,
                schedule(10, 2, 1, 0, 1800), List.of(schedule(11, 1, 2, 1799, 3600))));
        assertEquals(Assessment.REQUIRES_PROCESSING, service.assess(appointment,
                schedule(10, 2, 1, 0, 1800), List.of(new Schedule(11, 1, 2, 7,
                new Window(START.plusSeconds(1800).minusNanos(1000), START.plusSeconds(3600))))));
    }

    @Test void persistedProjectionUsesHistoricalCompositeKeyAndAuthorizedClinicOnly() throws Exception {
        var connection = mock(Connection.class);
        var query = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            assertTrue(sql.contains("(a.schedule_id,a.schedule_version_no)=(v.schedule_id,v.schedule_version_no)"));
            assertTrue(sql.contains("v.clinic_id=?"));
            return query;
        });
        when(query.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getLong(1)).thenReturn(50L);
        when(rows.getLong(2)).thenReturn(10L);
        when(rows.getInt(3)).thenReturn(1);
        when(rows.getLong(4)).thenReturn(1L);
        when(rows.getLong(5)).thenReturn(7L);
        when(rows.getObject(6, java.time.OffsetDateTime.class)).thenReturn(START.atOffset(ZoneOffset.UTC));
        when(rows.getObject(7, java.time.OffsetDateTime.class)).thenReturn(START.plusSeconds(3600).atOffset(ZoneOffset.UTC));
        when(rows.getObject(8, java.time.OffsetDateTime.class)).thenReturn(START.atOffset(ZoneOffset.UTC));
        when(rows.getObject(9, java.time.OffsetDateTime.class)).thenReturn(START.plusSeconds(1800).atOffset(ZoneOffset.UTC));
        assertEquals(List.of(new Impact(50, 10, 1, Assessment.SUPPORTED)),
                service.forAuthorizedClinic(connection, 1, schedule(10, 2, 1, 0, 1800), List.of(historical)));
        verify(query).setLong(1, 10);
        verify(query).setLong(2, 1);
        verify(query, never()).executeUpdate();
    }

    private static Schedule schedule(long id, int version, long clinic, long start, long end) {
        return new Schedule(id, version, clinic, 7, window(start, end));
    }

    private static Window window(long start, long end) {
        return new Window(START.plusSeconds(start), START.plusSeconds(end));
    }
}
