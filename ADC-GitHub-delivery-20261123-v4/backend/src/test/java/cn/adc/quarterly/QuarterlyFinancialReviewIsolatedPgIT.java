package cn.adc.quarterly;

import cn.adc.appointments.T028IsolatedPgIT.IsolatedData;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class QuarterlyFinancialReviewIsolatedPgIT {
    @EnabledIfEnvironmentVariable(named = "ADC_CLASSROOM_QUARTERLY_REVIEW", matches = "true")
    @Test void independentlyApprovesAndExportsTheSamePersistedCalculationVersion() throws Exception {
        runFlow(false);
    }

    @EnabledIfEnvironmentVariable(named = "ADC_CLASSROOM_QUARTERLY_CHAIN", matches = "true")
    @Test void generatesPreviewCalculatesReviewsAndExportsInOneDatabase() throws Exception {
        runFlow(true);
    }

    private void runFlow(boolean generatePreview) throws Exception {
        var db = new IsolatedData("runtime", 0);
        var tx = new JdbcTransactions(db);
        var clinics = Set.of(81001L);
        var clock = Clock.fixed(Instant.parse("2027-01-02T00:00:00Z"), ZoneOffset.UTC);
        var auditIds = new AtomicLong(82100);
        long statistic = generatePreview ? generatePreview(tx, clinics, clock, auditIds) : 19001L;
        var operations = new QuarterlyCalculationService(tx,
                grant("SYNTHETIC operations", "HQ_OPERATIONS", "UC19", clinics),
                (c, target, frozen) -> Optional.of(new QuarterlyCalculationService.Policy("SYNTHETIC accrual basis",
                        QuarterlyCalculationService.Revenue.RECEIVABLE, false, true)),
                c -> 19002L, c -> auditIds.incrementAndGet(), clock);
        var calculated = operations.calculate(new QuarterlyCalculationService.Target(statistic, null, null, clinics),
                UUID.randomUUID(), null);
        assertEquals("SUCCEEDED", calculated.businessOutcome());
        long calculation = calculated.result().path("calculationId").asLong();
        int version = calculated.result().path("version").asInt();
        assertEquals(19002, calculation);
        assertEquals(1, version);

        var finance = new QuarterlyFinancialReviewService(tx,
                grant("SYNTHETIC finance", "HQ_FINANCE", "UC20", clinics),
                c -> 20001L, c -> auditIds.incrementAndGet(), clock);
        var view = finance.read(calculation, version, clinics);
        var approved = finance.approve(calculation, version, clinics, view.path("basisHash").asText(),
                "SYNTHETIC independent verification", UUID.randomUUID());
        assertEquals("SUCCEEDED", approved.businessOutcome());
        assertEquals("20001", approved.result().path("reviewId").asText());

        var exported = operations.export(calculation, version, clinics, UUID.randomUUID());
        assertEquals("SUCCEEDED", exported.businessOutcome());
        assertEquals(Long.toString(calculation), exported.result().path("calculationId").asText());
        assertEquals(version, exported.result().path("version").asInt());
        assertEquals("20001", exported.result().path("reviewId").asText());
        assertEquals("12.00", exported.result().path("performance").get(0).path("amount").asText());
        try (var c = db.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("""
                SELECT r.review_result, r.reviewed_by, r.source_basis, v.statistic_id,
                       (SELECT count(*) FROM adc.operation_audit_event WHERE outcome='SUCCESS'
                         AND operation_name IN ('QUARTER_FINANCIAL_PASS','QUARTER_REVIEWED_EXPORT'))
                FROM adc.financial_review r JOIN adc.quarterly_operating_calculation_version v
                    USING (calculation_id, calculation_version_no)
                WHERE r.review_id=20001 AND r.calculation_id=19002 AND r.calculation_version_no=1
                """)) {
            assertTrue(rows.next());
            assertEquals("通过", rows.getString(1));
            assertEquals("SYNTHETIC finance", rows.getString(2));
            assertTrue(rows.getString(3).contains("SYNTHETIC-v1"));
            assertEquals(statistic, rows.getLong(4));
            assertEquals(2, rows.getInt(5));
            assertFalse(rows.next());
        }
    }

    private long generatePreview(JdbcTransactions tx, Set<Long> clinics, Clock clock, AtomicLong auditIds) {
        var target = new QuarterlyPreviewService.Target("2026-Q4", clinics);
        var time = Instant.parse("2026-11-01T00:00:00Z");
        var manifest = new QuarterlyPreviewService.Manifest(target, "SYNTHETIC-v1",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"),
                "SYNTHETIC verified basis", EnumSet.allOf(QuarterlyPreviewService.Metric.class), List.of(
                    fact("invoice", time, QuarterlyPreviewService.Metric.RECEIVABLE, "45.00", null),
                    fact("payment", time, QuarterlyPreviewService.Metric.RECEIVED, "33.33", null),
                    fact("expense", time, QuarterlyPreviewService.Metric.EXPENSE, "9.00", null),
                    fact("usage", time, QuarterlyPreviewService.Metric.CONSUMPTION, "5.75", null),
                    fact("visit", time, QuarterlyPreviewService.Metric.PERFORMANCE, "12.00", 79001L)
                ), List.of());
        var preview = new QuarterlyPreviewService(tx, grant("SYNTHETIC operations", "HQ_OPERATIONS", "UC17", clinics),
                (c, scope) -> manifest, c -> 17001L, c -> 17101L, c -> auditIds.incrementAndGet(), clock);
        var saved = preview.generate(target, UUID.randomUUID());
        assertEquals("SUCCEEDED", saved.businessOutcome());
        assertNotNull(saved.successEventId());
        long statistic = saved.result().path("statisticId").asLong();
        assertEquals(17001, statistic);
        return statistic;
    }

    private static QuarterlyPreviewService.Entry fact(String id, Instant time, QuarterlyPreviewService.Metric metric,
                                                       String amount, Long dentist) {
        return new QuarterlyPreviewService.Entry(id, "1", 81001, time, metric, new BigDecimal(amount), dentist, null);
    }

    private static StaffAuthorization grant(String actor, String role, String action, Set<Long> clinics) {
        var binding = new StaffAuthorization.Grant("TEST", Set.of(role), Set.of(action), clinics, false);
        return new StaffAuthorization(() -> Optional.of(actor), () -> Map.of(actor, binding));
    }
}
