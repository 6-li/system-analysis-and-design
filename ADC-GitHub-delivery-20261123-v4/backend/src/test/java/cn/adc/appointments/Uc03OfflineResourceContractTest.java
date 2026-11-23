package cn.adc.appointments;

import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Approved resource and storage contract; no actual UC03 writer or database. */
class Uc03OfflineResourceContractTest {
    @Test void u03r09ActualLockPrimitivesAcquireBothResourceSetsInCanonicalOrder() throws Exception {
        var oldRef = new Version(87001, 2);
        var newRef = new Version(87002, 3);
        var acquisitions = new ArrayList<String>();
        Connection connection = mock(Connection.class, withSettings().mockMaker(MockMakers.PROXY));
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            var statement = mock(PreparedStatement.class, withSettings().mockMaker(MockMakers.PROXY));
            var row = mock(ResultSet.class, withSettings().mockMaker(MockMakers.PROXY));
            var keys = new TreeMap<Integer, Number>();
            doAnswer(call -> { keys.put(call.getArgument(0), call.getArgument(1)); return null; })
                    .when(statement).setLong(anyInt(), anyLong());
            doAnswer(call -> { keys.put(call.getArgument(0), call.getArgument(1)); return null; })
                    .when(statement).setInt(anyInt(), anyInt());
            when(statement.execute()).thenAnswer(call -> { acquisitions.add(sql + " " + keys); return true; });
            when(row.next()).thenReturn(true);
            when(statement.executeQuery()).thenAnswer(call -> { acquisitions.add(sql + " " + keys); return row; });
            return statement;
        });
        var locks = new ResourceLockCoordinator().scope(connection);
        String reference = "SYNTHETIC-UC03-request";
        locks.command(reference);
        locks.catalog(false);
        locks.schedules(List.of(newRef.scheduleId(), oldRef.scheduleId(), oldRef.scheduleId()));
        locks.dentists(List.of(79002L, 79001L, 79001L));
        locks.versions(List.of(newRef, oldRef, oldRef));
        locks.appointments(List.of(901L));

        var keys = List.of(ResourceLockCoordinator.key("schedule:87001"), ResourceLockCoordinator.key("schedule:87002"))
                .stream().sorted().toList();
        assertEquals(List.of(
                "SELECT pg_advisory_xact_lock(41001,?) " + Map.of(1, (int)ResourceLockCoordinator.key(reference)),
                "SELECT pg_advisory_xact_lock_shared(41002,1) {}",
                "SELECT pg_advisory_xact_lock(?) {1=" + keys.get(0) + "}",
                "SELECT pg_advisory_xact_lock(?) {1=" + keys.get(1) + "}",
                "SELECT dentist_id FROM adc.dentist WHERE dentist_id=? FOR UPDATE {1=79001}",
                "SELECT dentist_id FROM adc.dentist WHERE dentist_id=? FOR UPDATE {1=79002}",
                "SELECT schedule_id FROM adc.reception_schedule_version WHERE schedule_id=? AND schedule_version_no=? FOR UPDATE {1=87001, 2=2}",
                "SELECT schedule_id FROM adc.reception_schedule_version WHERE schedule_id=? AND schedule_version_no=? FOR UPDATE {1=87002, 2=3}",
                "SELECT appointment_id FROM adc.appointment WHERE appointment_id=? FOR UPDATE {1=901}"), acquisitions);
        assertThrows(IllegalStateException.class, () -> locks.dentists(List.of(79003L)));
        verify(connection, times(2)).prepareStatement(eq("SELECT dentist_id FROM adc.dentist WHERE dentist_id=? FOR UPDATE"));
    }

    @Test void u03r10ProtectedDdlLinksHistoryToTheOriginalAppointmentAndBothVersions() throws Exception {
        var file = Path.of("src/main/resources/db/p0/V001__original_p0_tables.sql");
        if (!Files.isRegularFile(file)) file = Path.of("backend").resolve(file);
        String sql = Files.readString(file);
        String marker = "CREATE TABLE adc.appointment_change_history (";
        assertTrue(sql.contains(marker));
        var table = sql.substring(sql.indexOf(marker), sql.indexOf(");", sql.indexOf(marker)));
        for (String constraint : List.of(
                "PRIMARY KEY(appointment_change_id)",
                "FOREIGN KEY(appointment_id) REFERENCES adc.appointment(appointment_id)",
                "FOREIGN KEY(old_schedule_id,old_schedule_version_no) REFERENCES adc.reception_schedule_version(schedule_id,schedule_version_no)",
                "FOREIGN KEY(new_schedule_id,new_schedule_version_no) REFERENCES adc.reception_schedule_version(schedule_id,schedule_version_no)",
                "old_starts_at timestamptz(6) NOT NULL", "new_ends_at timestamptz(6) NOT NULL",
                "old_treatment_request TEXT NOT NULL", "new_treatment_request TEXT NOT NULL",
                "old_reminder_basis TEXT NOT NULL", "new_reminder_basis TEXT NOT NULL",
                "manual_confirmation_basis TEXT NOT NULL", "change_request_reference VARCHAR(200)")) {
            assertTrue(table.contains(constraint), constraint);
        }
    }
}
