package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VisitHealthServiceTest {
    @Test void healthWorkspaceReadsPriorTreatmentAndReturnsCommittedChangeId() throws Exception {
        var service = mock(VisitHealthService.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var target = new VisitHealthService.Context(11, 31, 41);
        var key = UUID.randomUUID();
        when(service.read(target)).thenReturn(new VisitHealthService.Details(
                new VisitHealthService.Snapshot(400L, "SYNTHETIC history", null),
                List.of(new VisitHealthService.Treatment(40, 51, 2, "SYNTHETIC treatment", "已实施"))));
        var update = new VisitHealthService.Update(400L, "SYNTHETIC history", "SYNTHETIC allergy", "SYNTHETIC verification");
        var result = cn.adc.shared.audit.AuditDocuments.JSON.createObjectNode()
                .put("healthChangeId", 401).put("visitId", 41);
        when(service.save(target, key, update)).thenReturn(new cn.adc.shared.audit.CommandRecovery.Receipt(
                "SYNTHETIC request", "SUCCEEDED", cn.adc.shared.audit.CommandRecovery.Persistence.CONFIRMED,
                result, false, "501", null));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new VisitHealthController((request, csrf) -> new VisitHealthController.Scope(11, service)))
                .build();
        String route = "/api/v1/staff/visits/41/health";
        mvc.perform(get(route).param("patientId", "31").header("X-CSRF-Token", "SYNTHETIC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snapshot.id").value("400"))
                .andExpect(jsonPath("$.priorTreatments[0].treatment").value("SYNTHETIC treatment"));
        mvc.perform(post(route).header("X-CSRF-Token", "SYNTHETIC").header("Idempotency-Key", key)
                        .contentType("application/json").content("""
                        {"patientId":"31","expectedSnapshotId":"400","medicalHistory":"SYNTHETIC history",
                         "allergyInformation":"SYNTHETIC allergy","changeContent":"SYNTHETIC verification"}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.healthChangeId").value("401"));
        verify(service).save(target, key, update);
    }

    @Test void treatingDentistAppendsVerifiedSnapshotForAttendedVisit() throws Exception {
        var db = mock(DataSource.class);
        var connection = mock(Connection.class);
        var scope = mock(PreparedStatement.class);
        var lock = mock(PreparedStatement.class);
        var latest = mock(PreparedStatement.class);
        var insert = mock(PreparedStatement.class);
        var eligible = mock(ResultSet.class);
        var prior = mock(ResultSet.class);
        when(connection.prepareStatement(contains("v.actual_dentist_id=?"))).thenReturn(scope);
        when(connection.prepareStatement(contains("pg_advisory_xact_lock"))).thenReturn(lock);
        when(connection.prepareStatement(contains("ORDER BY h.changed_at"))).thenReturn(latest);
        when(connection.prepareStatement(contains("INSERT INTO adc.patient_health_change"))).thenReturn(insert);
        when(scope.executeQuery()).thenReturn(eligible);
        when(eligible.next()).thenReturn(true);
        when(latest.executeQuery()).thenReturn(prior);
        when(prior.next()).thenReturn(true);
        when(prior.getLong(1)).thenReturn(400L);
        when(prior.getString(2)).thenReturn("SYNTHETIC prior verified history");
        when(insert.executeUpdate()).thenReturn(1);
        var staff = new StaffAuthorization(() -> Optional.of("SYNTHETIC dentist"),
                () -> Map.of("SYNTHETIC dentist", new StaffAuthorization.Grant("fixture", Set.of("DENTIST"),
                        Set.of("UC07"), Set.of(11L), false)));
        var clock = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
        var service = new VisitHealthService(new JdbcTransactions(db), staff, (actor, clinic) -> 21L,
                c -> 401L, c -> 501L, clock);
        var change = service.append(connection, new VisitHealthService.Context(11, 31, 41),
                new VisitHealthService.Update(400, "SYNTHETIC updated history", null, "SYNTHETIC verified change"));
        assertEquals(401, change.after().path("healthChangeId").asLong());
        assertEquals(41, change.after().path("visitId").asLong());
        var order = inOrder(scope, lock, latest, insert);
        order.verify(scope).executeQuery();
        order.verify(lock).execute();
        order.verify(scope).executeQuery();
        order.verify(latest).executeQuery();
        order.verify(insert).setLong(2, 41);
        order.verify(insert).setString(3, "SYNTHETIC updated history");
        order.verify(insert).setString(4, null);
        order.verify(insert).setLong(6, 21);
        order.verify(insert).executeUpdate();
    }
}
