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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TreatmentInvoiceServiceTest {
    @Test void currentClinicalFactsProduceInvoiceAndRecoverablePriceEvidence() throws Exception {
        var c = mock(Connection.class);
        var scope = mock(PreparedStatement.class);
        var lock = mock(PreparedStatement.class);
        var existing = mock(PreparedStatement.class);
        var facts = mock(PreparedStatement.class);
        var invoice = mock(PreparedStatement.class);
        var version = mock(PreparedStatement.class);
        var line = mock(PreparedStatement.class);
        var eligible = mock(ResultSet.class);
        var absent = mock(ResultSet.class);
        var clinical = mock(ResultSet.class);
        when(c.prepareStatement(contains("a.appointment_status='已到诊'"))).thenReturn(scope);
        when(c.prepareStatement(contains("pg_advisory_xact_lock"))).thenReturn(lock);
        when(c.prepareStatement(contains("LEFT JOIN adc.treatment_invoice_version"))).thenReturn(existing);
        when(c.prepareStatement(contains("v.is_current AND v.implementation_result='已实施'"))).thenReturn(facts);
        when(c.prepareStatement(contains("INSERT INTO adc.treatment_invoice ("))).thenReturn(invoice);
        when(c.prepareStatement(contains("INSERT INTO adc.treatment_invoice_version"))).thenReturn(version);
        when(c.prepareStatement(contains("INSERT INTO adc.treatment_invoice_item"))).thenReturn(line);
        when(scope.executeQuery()).thenReturn(eligible);
        when(eligible.next()).thenReturn(true);
        when(existing.executeQuery()).thenReturn(absent);
        when(facts.executeQuery()).thenReturn(clinical);
        when(clinical.next()).thenReturn(true, false);
        when(clinical.getLong(1)).thenReturn(701L);
        when(clinical.getInt(2)).thenReturn(1);
        when(clinical.getString(3)).thenReturn("SYNTHETIC treatment");
        when(clinical.getString(4)).thenReturn("次");
        when(clinical.getBigDecimal(5)).thenReturn(new BigDecimal("1.250"));
        when(invoice.executeUpdate()).thenReturn(1);
        when(version.executeUpdate()).thenReturn(1);
        when(line.executeUpdate()).thenReturn(1);
        var auth = new StaffAuthorization(() -> Optional.of("SYNTHETIC billing"),
                () -> Map.of("SYNTHETIC billing", new StaffAuthorization.Grant("fixture", Set.of("BILLING_STAFF"),
                        Set.of("UC10"), Set.of(11L), false)));
        var target = new TreatmentInvoiceService.Target(11, 41);
        var service = new TreatmentInvoiceService(new JdbcTransactions(mock(DataSource.class)), auth,
                (conn, actualTarget, fact) -> {
                    assertEquals(target, actualTarget);
                    assertEquals(new TreatmentInvoiceService.Fact(701, 1, "SYNTHETIC treatment", "次", new BigDecimal("1.250")), fact);
                    return Optional.of(new TreatmentInvoiceService.Price(new BigDecimal("26.66"),
                            "SYNTHETIC-PRICE-v1", "SYNTHETIC clinic 11 and unit 次"));
                }, conn -> 801L, conn -> 811L, conn -> 501L, Clock.systemUTC());
        var change = service.generate(c, target);
        assertEquals("33.33", change.after().path("total").asText());
        assertEquals("待核对确认", change.after().path("status").asText());
        assertEquals("701", change.after().path("lines").get(0).path("clinicalRecordId").asText());
        var order = inOrder(scope, lock, existing, facts, invoice, version, line);
        order.verify(scope).executeQuery();
        order.verify(lock).execute();
        order.verify(scope).executeQuery();
        order.verify(existing).executeQuery();
        order.verify(facts).executeQuery();
        order.verify(invoice).setLong(2, 41);
        order.verify(invoice).executeUpdate();
        order.verify(version).setBigDecimal(2, new BigDecimal("33.33"));
        order.verify(version).executeUpdate();
        order.verify(line).setLong(4, 701);
        order.verify(line).setInt(5, 1);
        order.verify(line).setString(eq(11), contains("SYNTHETIC-PRICE-v1"));
        order.verify(line).executeUpdate();

        var endpoint = mock(TreatmentInvoiceService.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var key = UUID.randomUUID();
        when(endpoint.generate(target, key)).thenReturn(new CommandRecovery.Receipt(
                "SYNTHETIC reference", "SUCCEEDED", CommandRecovery.Persistence.CONFIRMED, change.after(), false, "501", null));
        var mvc = MockMvcBuilders.standaloneSetup(new TreatmentInvoiceController(
                (req, csrf) -> new TreatmentInvoiceController.Scope(11, endpoint))).build();
        mvc.perform(post("/api/v1/staff/visits/41/invoice").header("X-CSRF-Token", "SYNTHETIC").header("Idempotency-Key", key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invoice.invoiceId").value("801"))
                .andExpect(jsonPath("$.invoice.lines[0].amount").value("33.33"));
    }
}
