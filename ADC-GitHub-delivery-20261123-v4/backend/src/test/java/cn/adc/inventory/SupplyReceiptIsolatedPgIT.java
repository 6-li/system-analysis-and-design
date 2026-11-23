package cn.adc.inventory;

import cn.adc.appointments.T028IsolatedPgIT.IsolatedData;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@EnabledIfEnvironmentVariable(named = "ADC_CLASSROOM_RECEIPT", matches = "true")
class SupplyReceiptIsolatedPgIT {
    @Test void acceptsDeliveryAndPersistsReceiptStockAndAudit() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var clock = Clock.systemUTC();
        String actor = "SYNTHETIC procurement";
        var stamp = new SourceStamp("SYNTHETIC", "uc15-fixture", "isolated", clock.instant(), true);
        var bindings = new StaffBindingAdapter(() -> List.of(new StaffBindingAdapter.Entry(actor,
                new StaffAuthorization.Grant(stamp.version(), Set.of("PROCUREMENT_STAFF"), Set.of("UC15"),
                        Set.of(81001L), false), stamp)));
        var ids = new AtomicLong(75100);
        var delivery = new SupplyReceiptService.Delivery(1001, 81001, 801,
                "SYNTHETIC-DELIVERY-UC15", "SYNTHETIC supplier slip");
        var access = new SupplyReceiptController.StaffAccess(new JdbcTransactions(db), bindings,
                (c, lookup) -> delivery.lookup().equals(lookup) ? Optional.of(delivery) : Optional.empty(),
                c -> ids.incrementAndGet(), clock);
        var mvc = standaloneSetup(new SupplyReceiptController(access)).build();
        var session = new MockHttpSession();
        session.setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession(actor, "SYNTHETIC-CSRF", stamp));
        mvc.perform(post("/api/v1/staff/clinics/81001/purchase-requirements/801/receipt").session(session)
                        .header("X-CSRF-Token", "SYNTHETIC-CSRF").header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content("""
                                {"deliveryLookup":"SYNTHETIC-DELIVERY-UC15","accepted":true,"result":"SYNTHETIC accepted",
                                 "discrepancy":null,"received":"6.000","cost":"12.40","expiry":"2027-04-05"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.receipt.receiptId").value("1001"))
                .andExpect(jsonPath("$.receipt.status").value("已入库"))
                .andExpect(jsonPath("$.receipt.stockBalanceAfter").value("10"));
        try (var c = db.getConnection(); var statement = c.createStatement(); var rows = statement.executeQuery("""
                SELECT r.purchase_requirement_id, r.received_quantity, r.purchase_cost, r.expiry_date,
                       s.on_hand_quantity,
                       (SELECT count(*) FROM adc.operation_audit_event
                        WHERE uc_code='UC15' AND operation_name='SUPPLY_RECEIPT_RECORD' AND outcome='SUCCESS'
                          AND evidence::jsonb #>> '{businessResultSnapshot,after,receiptId}'='1001')
                FROM adc.supply_receipt r JOIN adc.medical_supply_stock s ON s.stock_id=r.stock_id
                WHERE r.receipt_id=1001 AND s.clinic_id=81001
                """)) {
            assertTrue(rows.next());
            assertEquals(801L, rows.getLong(1));
            assertEquals(new BigDecimal("6.000"), rows.getBigDecimal(2));
            assertEquals(new BigDecimal("12.40"), rows.getBigDecimal(3));
            assertEquals("2027-04-05", rows.getString(4));
            assertEquals(new BigDecimal("10.000"), rows.getBigDecimal(5));
            assertEquals(1, rows.getInt(6));
        }
    }
}
