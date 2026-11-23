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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static cn.adc.quarterly.QuarterlyPreviewService.AppointmentResult.*;
import static cn.adc.quarterly.QuarterlyPreviewService.Metric.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ADC_CLASSROOM_QUARTERLY", matches = "true")
class QuarterlyPreviewIsolatedPgIT {
    @Test void storesSyntheticQuarterSnapshotWithClinicScopeAndFrozenSources() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var at = Instant.parse("2026-11-01T00:00:00Z");
        var clock = Clock.fixed(Instant.parse("2027-01-10T00:00:00Z"), ZoneOffset.UTC);
        long first = 81001L;
        long second = 81002L;
        var target = new QuarterlyPreviewService.Target("2026-Q4", Set.of(first, second));
        var manifest = new QuarterlyPreviewService.Manifest(target, "SYNTHETIC-MANIFEST-v1",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"),
                "SYNTHETIC explicit boundaries and attribution; operating expense excludes consumption",
                EnumSet.allOf(QuarterlyPreviewService.Metric.class), List.of(
                    new QuarterlyPreviewService.Entry("invoice-1", "1", first, at, RECEIVABLE, new BigDecimal("45.00"), null, null),
                    new QuarterlyPreviewService.Entry("payment-1", "1", first, at, RECEIVED, new BigDecimal("33.33"), null, null),
                    new QuarterlyPreviewService.Entry("expense-1", "1", second, at, EXPENSE, new BigDecimal("9.00"), null, null),
                    new QuarterlyPreviewService.Entry("consumption-1", "1", second, at, CONSUMPTION, new BigDecimal("5.75"), null, null),
                    new QuarterlyPreviewService.Entry("appointment-1", "1", first, at, APPOINTMENT, null, null, ATTENDED),
                    new QuarterlyPreviewService.Entry("appointment-2", "1", second, at, APPOINTMENT, null, null, CONFIRMED_NO_SHOW),
                    new QuarterlyPreviewService.Entry("visit-1", "1", first, at, PERFORMANCE, new BigDecimal("8.00"), 79001L, null),
                    new QuarterlyPreviewService.Entry("visit-2", "1", second, at, PERFORMANCE, new BigDecimal("4.00"), 79001L, null)
                ), List.of());
        String actor = "SYNTHETIC headquarters operations";
        var grant = new StaffAuthorization.Grant("TEST", Set.of("HQ_OPERATIONS"), Set.of("UC17"), target.clinics(), false);
        var auditIds = new AtomicLong(78100);
        var service = new QuarterlyPreviewService(new JdbcTransactions(db),
                new StaffAuthorization(() -> Optional.of(actor), () -> Map.of(actor, grant)),
                (connection, scope) -> { assertEquals(target, scope); return manifest; },
                c -> 17001L, c -> 17101L, c -> auditIds.incrementAndGet(), clock);
        var result = service.generate(target, UUID.randomUUID());
        assertEquals("SUCCEEDED", result.businessOutcome());
        assertNotNull(result.successEventId());
        assertEquals("17001", result.result().path("statisticId").asText());
        assertEquals("12.00", result.result().path("performance").get(0).path("amount").asText());
        try (var c = db.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("""
                SELECT receivable_amount, received_amount, operating_expense, consumption_cost,
                       due_appointment_count, confirmed_no_show_count, no_show_rate,
                       source_basis, source_manifest_reference,
                       (SELECT count(*) FROM adc.quarterly_statistic_clinic WHERE statistic_id=17001),
                       (SELECT count(*) FROM adc.operation_audit_event WHERE uc_code='UC17' AND outcome='SUCCESS')
                FROM adc.quarterly_operating_statistic WHERE statistic_id=17001
                """)) {
            assertTrue(rows.next());
            assertEquals(new BigDecimal("45.00"), rows.getBigDecimal(1));
            assertEquals(new BigDecimal("33.33"), rows.getBigDecimal(2));
            assertEquals(new BigDecimal("9.00"), rows.getBigDecimal(3));
            assertEquals(new BigDecimal("5.75"), rows.getBigDecimal(4));
            assertEquals(2, rows.getInt(5));
            assertEquals(1, rows.getInt(6));
            assertEquals(new BigDecimal("0.500000"), rows.getBigDecimal(7));
            assertTrue(rows.getString(8).contains("payment-1"));
            assertEquals("quarterly_operating_statistic/17001/source_basis", rows.getString(9));
            assertEquals(2, rows.getInt(10));
            assertEquals(1, rows.getInt(11));
        }
    }
}
