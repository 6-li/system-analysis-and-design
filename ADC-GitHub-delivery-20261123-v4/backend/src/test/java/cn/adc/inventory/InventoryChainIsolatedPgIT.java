package cn.adc.inventory;

import cn.adc.appointments.T028IsolatedPgIT.IsolatedData;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ADC_CLASSROOM_INVENTORY_CHAIN", matches = "true")
class InventoryChainIsolatedPgIT {
    @Test void purchaseReceiptConsumptionAndInquiryShareOnePersistedStock() throws Exception {
        long clinic = 81001;
        long supply = 101;
        var db = new IsolatedData("runtime", 0);
        var tx = new JdbcTransactions(db);
        var occurred = Instant.parse("2026-11-17T00:00:00Z");
        var clock = Clock.fixed(occurred.plusSeconds(60), ZoneOffset.UTC);
        var procurement = staff("SYNTHETIC procurement", "PROCUREMENT_STAFF", clinic, "UC14", "UC15");
        var manager = staff("SYNTHETIC manager", "CLINIC_MANAGER", clinic, "UC13", "UC16");
        var auditIds = new AtomicLong(77100);
        var quantity = new BigDecimal("6.000");
        var purchase = new PurchaseRequirementService(tx, procurement, c -> 801L,
                (c, ref) -> "TEST-DEMAND".equals(ref) ? Optional.of(new PurchaseRequirementService.Confirmation(
                        clinic, supply, quantity, ref, "SYNTHETIC manager", "SYNTHETIC demand")) : Optional.empty(),
                c -> auditIds.incrementAndGet(), clock);
        var ordered = purchase.register(clinic, UUID.randomUUID(),
                new PurchaseRequirementService.Input(supply, quantity, "TEST-DEMAND"));
        assertEquals("SUCCEEDED", ordered.businessOutcome());
        long requirement = ordered.result().path("requirementId").asLong();
        assertTrue(requirement > 0);

        var receipt = new SupplyReceiptService(tx, procurement, (c, ref) -> "TEST-DELIVERY".equals(ref)
                ? Optional.of(new SupplyReceiptService.Delivery(1001, clinic, requirement, ref, null))
                : Optional.empty(), c -> auditIds.incrementAndGet(), clock);
        var received = receipt.save(new SupplyReceiptService.Target(clinic, requirement), UUID.randomUUID(),
                new SupplyReceiptService.Input("TEST-DELIVERY", true, "SYNTHETIC accepted", null,
                        quantity, new BigDecimal("12.40"), LocalDate.parse("2027-04-05")));
        assertEquals("SUCCEEDED", received.businessOutcome());
        assertEquals("10", received.result().path("stockBalanceAfter").asText());
        long stock = received.result().path("stockId").asLong();

        var used = new BigDecimal("2.500");
        var target = new SupplyConsumptionService.Target(clinic, supply);
        var consumption = new SupplyConsumptionService(tx, manager, (c, ref) -> "TEST-COST".equals(ref)
                ? Optional.of(new SupplyConsumptionService.Evidence(1101, target, used, occurred,
                        new BigDecimal("5.20"), "SYNTHETIC actual cost", "SYNTHETIC manager", ref))
                : Optional.empty(), c -> auditIds.incrementAndGet(), clock);
        var consumed = consumption.save(target, UUID.randomUUID(),
                new SupplyConsumptionService.Input("TEST-COST", used, occurred));
        assertEquals("SUCCEEDED", consumed.businessOutcome());
        assertEquals(stock, consumed.result().path("stockId").asLong());
        var inquiry = new StockInquiryService(tx, manager, c -> auditIds.incrementAndGet(), clock);
        var queried = inquiry.query(clinic, supply, UUID.randomUUID());
        assertEquals("SUCCEEDED", queried.businessOutcome());
        var items = queried.result().path("items");
        assertEquals(1, items.size());
        assertEquals(stock, items.get(0).path("stockId").asLong());
        assertEquals("7.500", items.get(0).path("onHand").asText());

        try (var c = db.getConnection(); var sql = c.prepareStatement("""
                SELECT r.purchase_requirement_id, r.stock_id, x.stock_id, s.on_hand_quantity,
                       (SELECT count(*) FROM adc.operation_audit_event
                        WHERE uc_code IN ('UC13','UC14','UC15','UC16') AND outcome='SUCCESS')
                FROM adc.supply_receipt r
                JOIN adc.medical_supply_stock s ON s.stock_id=r.stock_id
                JOIN adc.supply_consumption x ON x.stock_id=s.stock_id
                WHERE r.receipt_id=1001 AND x.consumption_id=1101 AND s.clinic_id=? AND s.supply_id=?
                """)) {
            sql.setLong(1, clinic);
            sql.setLong(2, supply);
            try (var rows = sql.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(requirement, rows.getLong(1));
                assertEquals(stock, rows.getLong(2));
                assertEquals(stock, rows.getLong(3));
                assertEquals(new BigDecimal("7.500"), rows.getBigDecimal(4));
                assertEquals(4, rows.getInt(5));
                assertFalse(rows.next());
            }
        }
    }

    private static StaffAuthorization staff(String actor, String role, long clinic, String... actions) {
        var grant = new StaffAuthorization.Grant("TEST", Set.of(role), Set.of(actions), Set.of(clinic), false);
        return new StaffAuthorization(() -> Optional.of(actor), () -> Map.of(actor, grant));
    }
}
