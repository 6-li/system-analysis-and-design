package cn.adc.inventory;

import cn.adc.appointments.T028IsolatedPgIT.IsolatedData;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class StockInquiryIsolatedPgIT {
    @Test void readsClinicStockAndPersistsTheMatchingQueryAudit() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var clock = Clock.systemUTC();
        var actor = "SYNTHETIC stock manager";
        var stamp = new SourceStamp("SYNTHETIC", "uc13-fixture", "isolated", clock.instant(), true);
        var bindings = new StaffBindingAdapter(() -> List.of(new StaffBindingAdapter.Entry(actor,
                new StaffAuthorization.Grant(stamp.version(), Set.of("CLINIC_MANAGER"), Set.of("UC13"),
                        Set.of(81001L), false), stamp)));
        var auditIds = new AtomicLong(73100);
        var mvc = standaloneSetup(new StockInquiryController(new StockInquiryController.StaffAccess(
                new JdbcTransactions(db), bindings, c -> auditIds.incrementAndGet(), clock))).build();
        var session = new MockHttpSession();
        session.setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession(actor, "SYNTHETIC-CSRF", stamp));
        mvc.perform(post("/api/v1/staff/clinics/81001/stock-inquiry").session(session)
                        .header("X-CSRF-Token", "SYNTHETIC-CSRF").header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content("{\"supplyId\":\"101\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock.items.length()").value(1))
                .andExpect(jsonPath("$.stock.items[0].stockId").value("701"))
                .andExpect(jsonPath("$.stock.items[0].onHand").value("4.000"))
                .andExpect(jsonPath("$.stock.items[0].unit").value("盒"))
                .andExpect(jsonPath("$.stock.items[0].existingWarning").value("SYNTHETIC expiry warning"));
        try (var c = db.getConnection(); var statement = c.createStatement(); var rows = statement.executeQuery("""
                SELECT evidence::jsonb #>> '{businessResultSnapshot,after,clinicId}',
                       evidence::jsonb #>> '{businessResultSnapshot,after,items,0,onHand}',
                       has_table_privilege('adc.medical_supply_stock', 'UPDATE')
                FROM adc.operation_audit_event
                WHERE uc_code='UC13' AND operation_name='STOCK_INQUIRY' AND outcome='SUCCESS'
                """)) {
            assertTrue(rows.next());
            assertEquals("81001", rows.getString(1));
            assertEquals("4.000", rows.getString(2));
            assertFalse(rows.getBoolean(3));
            assertFalse(rows.next());
        }
    }
}
