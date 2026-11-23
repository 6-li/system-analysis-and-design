package cn.adc.quarterly;

import cn.adc.appointments.T028IsolatedPgIT.IsolatedData;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ADC_CLASSROOM_QUARTERLY_ISSUE", matches = "true")
class QuarterlyIssueIsolatedPgIT {
    @Test void preservesOriginalIssueAndPersistsClinicCorrectionAndHeadquartersReview() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var tx = new JdbcTransactions(db);
        var clock = Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC);
        var auditIds = new AtomicLong(79100);
        var clinic = new QuarterlyIssueService.Target(18001, 18002, Set.of(81001L));
        var headquarters = new QuarterlyIssueService.Target(18001, 18002, Set.of(81001L, 81002L));
        var sources = new QuarterlyIssueService.Sources() {
            @Override public QuarterlyIssueService.Correction verify(Connection c, QuarterlyIssueService.Target target,
                    QuarterlyIssueService.Issue issue, String evidence, String proposed) {
                assertEquals(clinic, target);
                assertEquals("SYNTHETIC source amount missing", issue.original());
                return new QuarterlyIssueService.Correction(proposed, evidence, null, null);
            }

            @Override public QuarterlyIssueService.Check recheck(Connection c, QuarterlyIssueService.Target target,
                    QuarterlyIssueService.Issue issue, long history) {
                assertEquals(headquarters, target);
                assertEquals(18003, history);
                assertEquals("SYNTHETIC verified value", issue.previous());
                return new QuarterlyIssueService.Check(true, "SYNTHETIC check passed", "SYNTHETIC source v1");
            }
        };
        var manager = new QuarterlyIssueService(tx, staff("CLINIC_MANAGER", "UC18_CORRECTION", clinic.clinics()),
                sources, c -> 18003L, c -> auditIds.incrementAndGet(), clock);
        var hq = new QuarterlyIssueService(tx, staff("HQ_OPERATIONS", "UC18", headquarters.clinics()),
                sources, c -> 18003L, c -> auditIds.incrementAndGet(), clock);
        var correction = manager.execute(clinic, UUID.randomUUID(), true, null,
                "SYNTHETIC proof", "SYNTHETIC verified value");
        assertEquals("SUCCEEDED", correction.businessOutcome());
        long history = correction.result().path("historyId").asLong();
        assertEquals(18003, history);
        var review = hq.execute(headquarters, UUID.randomUUID(), false, history, null, null);
        assertEquals("SUCCEEDED", review.businessOutcome());
        assertEquals("复核通过", review.result().path("status").asText());
        var read = hq.read(headquarters, false);
        assertEquals("SYNTHETIC source amount missing", read.path("original").asText());
        assertEquals("SYNTHETIC verified value", read.path("current").asText());
        try (var c = db.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("""
                SELECT h.previous_value, h.corrected_value, h.correction_basis, h.check_basis, h.checked_at,
                       h.resulting_statistic_id, s.source_basis,
                       (SELECT count(*) FROM adc.operation_audit_event WHERE uc_code='UC18' AND outcome='SUCCESS')
                FROM adc.quarterly_issue_correction_history h
                JOIN adc.quarterly_data_issue i ON i.issue_id=h.issue_id
                JOIN adc.quarterly_operating_statistic s ON s.statistic_id=i.statistic_id
                WHERE h.correction_history_id=18003 AND i.issue_id=18002
                """)) {
            assertTrue(rows.next());
            assertNull(rows.getString(1));
            assertEquals("SYNTHETIC verified value", rows.getString(2));
            assertEquals("SYNTHETIC proof", rows.getString(3));
            assertEquals("SYNTHETIC source v1", rows.getString(4));
            assertNotNull(rows.getObject(5));
            assertNull(rows.getObject(6));
            assertTrue(rows.getString(7).contains("\"amount\":null"));
            assertEquals(2, rows.getInt(8));
            assertFalse(rows.next());
        }
        if ("true".equals(System.getenv("ADC_CLASSROOM_QUARTERLY_CORRECTED"))) {
            verifyNewSnapshot(db, tx, clock, auditIds, headquarters.clinics(), history);
        }
    }

    private void verifyNewSnapshot(IsolatedData db, JdbcTransactions tx, Clock clock, AtomicLong auditIds,
                                   Set<Long> clinics, long history) throws Exception {
        var target = new QuarterlyPreviewService.Target("2026-Q4", clinics);
        var manifest = new QuarterlyPreviewService.Manifest(target, "SYNTHETIC v2",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"),
                "SYNTHETIC verified after correction", EnumSet.allOf(QuarterlyPreviewService.Metric.class),
                List.of(new QuarterlyPreviewService.Entry("expense-1", "2", 81001,
                        Instant.parse("2026-11-01T00:00:00Z"), QuarterlyPreviewService.Metric.EXPENSE,
                        new BigDecimal("9.00"), null, null),
                        new QuarterlyPreviewService.Entry("invoice-1", "1", 81001,
                                Instant.parse("2026-11-01T00:00:00Z"), QuarterlyPreviewService.Metric.RECEIVABLE,
                                new BigDecimal("45.00"), null, null)),
                List.of(), List.of(new QuarterlyPreviewService.AppliedCorrection(
                        history, "SYNTHETIC verified value", "expense-1", "1", "2", 81001)));
        var preview = new QuarterlyPreviewService(tx, staff("HQ_OPERATIONS", "UC17", clinics),
                (c, scope) -> manifest, c -> 18004L, c -> 18005L, c -> auditIds.incrementAndGet(), clock);
        var key = UUID.randomUUID();
        var saved = preview.generate(target, key);
        assertEquals("SUCCEEDED", saved.businessOutcome());
        long newId = saved.result().path("statisticId").asLong();
        assertEquals(18004L, newId);
        assertEquals("18004", preview.generate(target, key).result().path("statisticId").asText());
        var calculation = new QuarterlyCalculationService(tx, staff("HQ_OPERATIONS", "UC19", clinics),
                (c, scope, frozen) -> Optional.of(new QuarterlyCalculationService.Policy("SYNTHETIC accrual",
                        QuarterlyCalculationService.Revenue.RECEIVABLE, false, true)),
                c -> 18006L, c -> auditIds.incrementAndGet(), clock);
        var calculated = calculation.calculate(new QuarterlyCalculationService.Target(newId, null, null, clinics),
                UUID.randomUUID(), null);
        assertEquals("SUCCEEDED", calculated.businessOutcome());
        assertEquals("36.00", calculated.result().path("netProfit").asText());
        try (var c = db.getConnection(); var sql = c.createStatement(); var rows = sql.executeQuery("""
                SELECT old.operating_expense, fresh.operating_expense, fresh.source_basis,
                       (SELECT count(*) FROM adc.operation_audit_event WHERE uc_code='UC17' AND outcome='SUCCESS')
                FROM adc.quarterly_issue_correction_history h
                JOIN adc.quarterly_data_issue i ON i.issue_id=h.issue_id
                JOIN adc.quarterly_operating_statistic old ON old.statistic_id=i.statistic_id
                JOIN adc.quarterly_operating_statistic fresh ON fresh.statistic_id=h.resulting_statistic_id
                WHERE h.correction_history_id=18003 AND old.statistic_id=18001 AND fresh.statistic_id=18004
                """)) {
            assertTrue(rows.next());
            assertNull(rows.getBigDecimal(1));
            assertEquals(new BigDecimal("9.00"), rows.getBigDecimal(2));
            assertTrue(rows.getString(3).contains("appliedCorrections"));
            assertEquals(1, rows.getInt(4));
        }
    }

    private static StaffAuthorization staff(String role, String action, Set<Long> clinics) {
        var actor = "SYNTHETIC " + role;
        var grant = new StaffAuthorization.Grant("TEST", Set.of(role), Set.of(action), clinics, false);
        return new StaffAuthorization(() -> Optional.of(actor), () -> Map.of(actor, grant));
    }
}
