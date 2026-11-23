package cn.adc.appointments;

import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VisitConsentServiceTest {
    private static final VisitConsentService.Context VISIT = new VisitConsentService.Context(11, 31, 41);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-17T02:00:00Z");
    private static final VisitConsentService.Input INPUT = new VisitConsentService.Input(
            "SYNTHETIC patient and guardian", true, "SYNTHETIC guardian agreed",
            "SYNTHETIC treatment scope", "同意", null);

    @Test void verifiedDentistAppendsActualResultAndRecoversFromMatchingEvidence() throws Exception {
        var db = mock(DataSource.class);
        var connection = mock(Connection.class);
        var scope = mock(PreparedStatement.class);
        var eligible = mock(ResultSet.class);
        var insert = mock(PreparedStatement.class);
        var query = mock(PreparedStatement.class);
        var evidence = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(db.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        when(connection.prepareStatement(contains("v.actual_dentist_id=?"))).thenReturn(scope);
        when(connection.prepareStatement(contains("INSERT INTO adc.treatment_consent_result"))).thenReturn(insert);
        when(connection.prepareStatement(contains("ORDER BY recorded_at"))).thenReturn(query);
        when(connection.prepareStatement(contains("WHERE consent_result_id=? AND visit_id=?"))).thenReturn(evidence);
        when(scope.executeQuery()).thenReturn(eligible);
        when(eligible.next()).thenReturn(true);
        when(insert.executeUpdate()).thenReturn(1);
        when(query.executeQuery()).thenReturn(rows);
        when(evidence.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false, true);
        when(rows.getLong(1)).thenReturn(401L);
        when(rows.getString(2)).thenReturn(INPUT.party());
        when(rows.getString(3)).thenReturn(INPUT.guardianParticipation());
        when(rows.getString(4)).thenReturn(INPUT.treatment());
        when(rows.getString(5)).thenReturn(INPUT.outcome());
        when(rows.getString(7)).thenReturn("SYNTHETIC dentist");
        when(rows.getObject(8, OffsetDateTime.class)).thenReturn(AT);
        when(rows.getLong(9)).thenReturn(21L);
        var staff = new StaffAuthorization(() -> Optional.of("SYNTHETIC dentist"),
                () -> Map.of("SYNTHETIC dentist", new StaffAuthorization.Grant("fixture", Set.of("DENTIST"),
                        Set.of("UC08"), Set.of(11L), false)));
        var service = new VisitConsentService(new JdbcTransactions(db), staff, (actor, clinic) -> 21L,
                c -> 401L, c -> 501L, Clock.fixed(Instant.from(AT), ZoneOffset.UTC));
        VisitConsentService.validate(INPUT);
        var change = service.append(connection, VISIT, INPUT);
        assertEquals(401, change.after().path("consentResultId").asLong());
        assertEquals(INPUT.treatment(), service.read(VISIT).getFirst().treatment());
        var journal = JSON.createObjectNode();
        journal.set("after", change.after());
        journal.set("businessReference", change.businessReference());
        journal.set("verification", change.verification());
        var command = new CommandRecovery.Command("TREATMENT_CONSENT_RECORD", "UC08", UUID.randomUUID(),
                JSON.createObjectNode().put("patientId", 31).put("visitId", 41), Set.of(11L));
        assertTrue(service.matchesCommitted(connection, command, journal));
        var order = inOrder(scope, insert);
        order.verify(scope).setLong(1, 41);
        order.verify(scope).setLong(2, 11);
        order.verify(scope).setLong(3, 21);
        order.verify(scope).setLong(4, 31);
        order.verify(scope).executeQuery();
        order.verify(insert).setLong(1, 401);
        order.verify(insert).setLong(2, 41);
        order.verify(insert).setString(3, INPUT.party());
        order.verify(insert).setString(4, INPUT.guardianParticipation());
        order.verify(insert).setString(5, INPUT.treatment());
        order.verify(insert).setString(6, INPUT.outcome());
        order.verify(insert).setString(7, null);
        order.verify(insert).setString(8, "SYNTHETIC dentist");
        order.verify(insert).setObject(9, AT);
        order.verify(insert).setLong(10, 21);
        order.verify(insert).executeUpdate();
    }

    @Test void consentWorkspaceQueriesHistoryAndReturnsCommittedResult() throws Exception {
        var service = mock(VisitConsentService.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(service.read(VISIT)).thenReturn(List.of(new VisitConsentService.Entry(400, "SYNTHETIC patient",
                null, "SYNTHETIC earlier scope", "待确认", "SYNTHETIC review pending", "SYNTHETIC dentist", AT, 21)));
        var key = UUID.randomUUID();
        var receipt = JSON.createObjectNode().put("consentResultId", 401).put("visitId", 41);
        when(service.save(VISIT, key, INPUT)).thenReturn(new CommandRecovery.Receipt(
                "SYNTHETIC request", "SUCCEEDED", CommandRecovery.Persistence.CONFIRMED, receipt, false, "501", null));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new VisitConsentController((request, csrf) -> new VisitConsentController.Scope(11, service)))
                .build();
        String route = "/api/v1/staff/visits/41/consent";
        mvc.perform(get(route).param("patientId", "31").header("X-CSRF-Token", "SYNTHETIC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].consentResult").value("待确认"));
        mvc.perform(post(route).header("X-CSRF-Token", "SYNTHETIC").header("Idempotency-Key", key)
                        .contentType("application/json").content("""
                        {"patientId":"31","communicationParty":"SYNTHETIC patient and guardian","guardianRequired":true,
                         "guardianParticipation":"SYNTHETIC guardian agreed","proposedTreatment":"SYNTHETIC treatment scope",
                         "consentResult":"同意","comments":null}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.consentResultId").value("401"));
        verify(service).save(VISIT, key, INPUT);
    }
}
