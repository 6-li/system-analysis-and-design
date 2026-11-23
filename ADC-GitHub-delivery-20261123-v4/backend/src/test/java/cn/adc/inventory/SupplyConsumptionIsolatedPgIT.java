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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@EnabledIfEnvironmentVariable(named = "ADC_CLASSROOM_CONSUMPTION", matches = "true")
class SupplyConsumptionIsolatedPgIT {
    @Test void persistsVerifiedConsumptionCostStockDeductionAndAudit() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var occurredAt = Instant.parse("2026-11-17T00:00:00Z");
        var clock = Clock.fixed(occurredAt.plusSeconds(60), ZoneOffset.UTC);
        var actor = "SYNTHETIC stock manager";
        var stamp = new SourceStamp("SYNTHETIC", "uc16-fixture", "isolated", clock.instant(), true);
        var bindings = new StaffBindingAdapter(() -> List.of(new StaffBindingAdapter.Entry(actor,
                new StaffAuthorization.Grant(stamp.version(), Set.of("CLINIC_MANAGER"), Set.of("UC16"),
                        Set.of(81001L), false), stamp)));
        var evidence = new SupplyConsumptionService.Evidence(1101, new SupplyConsumptionService.Target(81001, 101),
                new BigDecimal("2.500"), occurredAt, new BigDecimal("5.20"), "SYNTHETIC verified actual cost",
                actor, "SYNTHETIC usage UC16");
        var auditIds = new AtomicLong(76100);
        var access = new SupplyConsumptionController.StaffAccess(new JdbcTransactions(db), bindings,
                (c, ref) -> "SYNTHETIC-COST-UC16".equals(ref) ? Optional.of(evidence) : Optional.empty(),
                c -> auditIds.incrementAndGet(), clock);
        var mvc = standaloneSetup(new SupplyConsumptionController(access)).build();
        var session = new MockHttpSession();
        session.setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession(actor, "SYNTHETIC-CSRF", stamp));
        mvc.perform(post("/api/v1/staff/clinics/81001/supply-consumption").session(session)
                        .header("X-CSRF-Token", "SYNTHETIC-CSRF").header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content("""
                                {"supplyId":"101","quantity":"2.500","occurredAt":"2026-11-17T00:00:00Z",
                                 "proofReference":"SYNTHETIC-COST-UC16"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.consumption.consumptionId").value("1101"))
                .andExpect(jsonPath("$.consumption.stockBalanceAfter").value("1.5"));
        try (var c = db.getConnection(); var statement = c.createStatement(); var rows = statement.executeQuery("""
                SELECT x.consumed_quantity, x.consumption_cost, x.cost_basis::jsonb ->> 'pricingBasis',
                       x.verified_by, x.consumption_source_reference, s.on_hand_quantity,
                       (SELECT count(*) FROM adc.operation_audit_event
                        WHERE uc_code='UC16' AND operation_name='SUPPLY_CONSUMPTION_RECORD' AND outcome='SUCCESS'
                          AND evidence::jsonb #>> '{businessResultSnapshot,after,consumptionId}'='1101')
                FROM adc.supply_consumption x JOIN adc.medical_supply_stock s ON s.stock_id=x.stock_id
                WHERE x.consumption_id=1101 AND s.clinic_id=81001 AND s.supply_id=101
                """)) {
            assertTrue(rows.next());
            assertEquals(evidence.quantity(), rows.getBigDecimal(1));
            assertEquals(evidence.cost(), rows.getBigDecimal(2));
            assertEquals(evidence.pricingBasis(), rows.getString(3));
            assertEquals(actor, rows.getString(4));
            assertEquals(evidence.sourceReference(), rows.getString(5));
            assertEquals(new BigDecimal("1.500"), rows.getBigDecimal(6));
            assertEquals(1, rows.getInt(7));
        }
    }
}
