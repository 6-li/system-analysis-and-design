package cn.adc.quarterly;

import cn.adc.appointments.T028IsolatedPgIT.IsolatedData;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ADC_CLASSROOM_QUARTERLY_CALCULATION", matches = "true")
class QuarterlyCalculationIsolatedPgIT {
    @Test void persistsPendingFinancialReviewVersionFromFrozenPreview() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var clinics = Set.of(81001L);
        var actor = "SYNTHETIC headquarters operations";
        var grant = new StaffAuthorization.Grant("TEST", Set.of("HQ_OPERATIONS"), Set.of("UC19"), clinics, false);
        var auditIds = new AtomicLong(80100);
        var service = new QuarterlyCalculationService(new JdbcTransactions(db),
                new StaffAuthorization(() -> Optional.of(actor), () -> Map.of(actor, grant)),
                (c, target, frozen) -> {
                    assertEquals(19001L, target.statistic());
                    assertEquals("SYNTHETIC-v1", frozen.version());
                    return Optional.of(new QuarterlyCalculationService.Policy("SYNTHETIC accrual basis",
                            QuarterlyCalculationService.Revenue.RECEIVABLE, false, true));
                }, c -> 19002L, c -> auditIds.incrementAndGet(),
                Clock.fixed(Instant.parse("2027-01-02T00:00:00Z"), ZoneOffset.UTC));
        var result = service.calculate(new QuarterlyCalculationService.Target(19001, null, null, clinics),
                UUID.randomUUID(), null);
        assertEquals("SUCCEEDED", result.businessOutcome());
        assertNotNull(result.successEventId());
        assertEquals("19002", result.result().path("calculationId").asText());
        assertEquals("待复核", result.result().path("status").asText());
        try (var c = db.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("""
                SELECT v.statistic_id, v.net_profit, v.incentive_pool_amount, v.report_status,
                       v.calculation_basis, p.dentist_id, p.performance_amount, p.attribution_basis,
                       (SELECT count(*) FROM adc.operation_audit_event WHERE uc_code='UC19' AND outcome='SUCCESS')
                FROM adc.quarterly_operating_calculation_version v
                JOIN adc.quarterly_dentist_performance_calc p USING (calculation_id, calculation_version_no)
                WHERE v.calculation_id=19002 AND v.calculation_version_no=1
                """)) {
            assertTrue(rows.next());
            assertEquals(19001L, rows.getLong(1));
            assertEquals(new BigDecimal("30.25"), rows.getBigDecimal(2));
            assertEquals(new BigDecimal("3.03"), rows.getBigDecimal(3));
            assertEquals("待复核", rows.getString(4));
            assertTrue(rows.getString(5).contains("SYNTHETIC accrual basis"));
            assertEquals(79001L, rows.getLong(6));
            assertEquals(new BigDecimal("12.00"), rows.getBigDecimal(7));
            assertEquals("SYNTHETIC attribution", rows.getString(8));
            assertEquals(1, rows.getInt(9));
            assertFalse(rows.next());
        }
    }
}
