package cn.adc.appointments;

import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VisitTreatmentServiceTest {
    private static final VisitTreatmentService.Context VISIT = new VisitTreatmentService.Context(11, 31, 41);
    private static final VisitTreatmentService.Input INPUT = new VisitTreatmentService.Input(
            400, true, "SYNTHETIC treatment", "次", new BigDecimal("1.250"), null, null);

    @Test void appendsFirstBillableVersionAfterVerifyingTheConsent() throws Exception {
        var connection = mock(Connection.class);
        var visit = mock(PreparedStatement.class);
        var consent = mock(PreparedStatement.class);
        var eligible = mock(ResultSet.class);
        var agreed = mock(ResultSet.class);
        var record = mock(PreparedStatement.class);
        var version = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("v.actual_dentist_id=?"))).thenReturn(visit);
        when(connection.prepareStatement(contains("WHERE consent_result_id=? AND visit_id=?"))).thenReturn(consent);
        when(connection.prepareStatement(contains("INSERT INTO adc.clinical_record ("))).thenReturn(record);
        when(connection.prepareStatement(contains("INSERT INTO adc.clinical_record_version"))).thenReturn(version);
        when(visit.executeQuery()).thenReturn(eligible);
        when(eligible.next()).thenReturn(true);
        when(consent.executeQuery()).thenReturn(agreed);
        when(agreed.next()).thenReturn(true);
        when(agreed.getString(1)).thenReturn(INPUT.project());
        when(agreed.getString(2)).thenReturn("同意");
        when(record.executeUpdate()).thenReturn(1);
        when(version.executeUpdate()).thenReturn(1);
        var staff = new StaffAuthorization(() -> Optional.of("SYNTHETIC dentist"),
                () -> Map.of("SYNTHETIC dentist", new StaffAuthorization.Grant("fixture", Set.of("DENTIST"),
                        Set.of("UC09"), Set.of(11L), false)));
        var service = new VisitTreatmentService(new JdbcTransactions(mock(DataSource.class)), staff,
                (actor, clinic) -> 21L, c -> 701L, c -> 501L, Clock.systemUTC());
        VisitTreatmentService.validate(INPUT);
        var change = service.append(connection, VISIT, INPUT);
        assertEquals(701, change.after().path("clinicalRecordId").asLong());
        assertEquals(1, change.after().path("recordVersionNo").asInt());
        var writes = inOrder(visit, consent, record, version);
        writes.verify(visit).executeQuery();
        writes.verify(consent).setLong(1, 400);
        writes.verify(consent).setLong(2, 41);
        writes.verify(consent).executeQuery();
        writes.verify(record).setLong(2, 41);
        writes.verify(record).executeUpdate();
        writes.verify(version).setLong(2, 400);
        writes.verify(version).setLong(3, 21);
        writes.verify(version).setString(4, INPUT.project());
        writes.verify(version).setString(5, INPUT.unit());
        writes.verify(version).setBigDecimal(6, INPUT.quantity());
        writes.verify(version).setString(7, "已实施");
        writes.verify(version).executeUpdate();
    }

    @Test void workspaceSelectsVisitConsentAndReturnsSavedBillingReference() throws Exception {
        var service = mock(VisitTreatmentService.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(service.read(VISIT)).thenReturn(List.of(new VisitTreatmentService.Consent(400, INPUT.project(), "同意")));
        var key = UUID.randomUUID();
        var result = JSON.createObjectNode().put("clinicalRecordId", 701).put("recordVersionNo", 1).put("visitId", 41);
        when(service.save(VISIT, key, INPUT)).thenReturn(new CommandRecovery.Receipt(
                "SYNTHETIC request", "SUCCEEDED", CommandRecovery.Persistence.CONFIRMED, result, false, "501", null));
        var mvc = MockMvcBuilders.standaloneSetup(new VisitTreatmentController(
                (request, csrf) -> new VisitTreatmentController.Scope(11, service))).build();
        String route = "/api/v1/staff/visits/41/treatment";
        mvc.perform(get(route).param("patientId", "31").header("X-CSRF-Token", "SYNTHETIC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.consents[0].consentResultId").value("400"));
        mvc.perform(post(route).header("X-CSRF-Token", "SYNTHETIC").header("Idempotency-Key", key)
                        .contentType("application/json").content("""
                        {"patientId":"31","consentResultId":"400","performed":true,"project":"SYNTHETIC treatment",
                         "unit":"次","quantity":"1.250","specialRequirements":null,"nonPerformanceReason":null}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clinicalRecordId").value("701"))
                .andExpect(jsonPath("$.recordVersionNo").value(1));
        verify(service).save(VISIT, key, INPUT);
    }
}
