package cn.adc.quarterly;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffBindingAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Import({QuarterlyPreviewController.class, QuarterlyIssueController.class,
        QuarterlyCalculationController.class, QuarterlyFinancialReviewController.class})
@ConditionalOnProperty(name = "adc.uc17.enabled", havingValue = "true")
public class ClassroomQuarterlyConfiguration {
    private final AtomicLong ids = new AtomicLong(84000);
    private final Clock clock = Clock.systemUTC();
    private static final Instant QUARTER_START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant QUARTER_END = Instant.parse("2027-01-01T00:00:00Z");

    @Bean QuarterlyPreviewController.Access quarterlyPreview(JdbcTransactions tx, StaffBindingAdapter bindings) {
        return new QuarterlyPreviewController.StaffAccess(tx, bindings, (c, scope) -> {
            if (!"2026-Q4".equals(scope.quarter()) || !Set.of(81001L).equals(scope.clinics()))
                throw new IllegalArgumentException("CLASSROOM_QUARTER_SCOPE");
            var facts = new java.util.ArrayList<QuarterlyPreviewService.Entry>();
            var corrections = new java.util.ArrayList<QuarterlyPreviewService.AppliedCorrection>();
            var issues = new java.util.ArrayList<QuarterlyPreviewService.Issue>();
            var complete = EnumSet.allOf(QuarterlyPreviewService.Metric.class);
            long clinic = scope.clinics().iterator().next();
            try {
                for (var expense : loadExpenses(scope.quarter(), clinic)) {
                    facts.add("expense-1".equals(expense.id()) ? applyExpenseCorrection(c, expense, corrections) : expense);
                }
            } catch (IllegalArgumentException | java.io.IOException e) {
                complete.remove(QuarterlyPreviewService.Metric.EXPENSE);
                issues.add(new QuarterlyPreviewService.Issue(clinic, "运营费用清单缺失或无法核实"));
            }
            facts.addAll(consumptionFacts(c, scope.clinics().iterator().next()));
            facts.addAll(billingFacts(c, scope.clinics().iterator().next()));
            int expenseIssueCount = issues.size();
            facts.addAll(appointmentFacts(c, clinic, issues));
            if (issues.size() != expenseIssueCount) complete.remove(QuarterlyPreviewService.Metric.APPOINTMENT);
            int appointmentIssueCount = issues.size();
            facts.addAll(performanceFacts(c, clinic, issues));
            if (issues.size() != appointmentIssueCount) complete.remove(QuarterlyPreviewService.Metric.PERFORMANCE);
            return new QuarterlyPreviewService.Manifest(scope, "SYNTHETIC-classroom-v7",
                    QUARTER_START, QUARTER_END,
                    "SYNTHETIC accrual basis; confirmed current invoice by confirmation time; "
                            + "verified demo receipt by receipt time; appointments by slot start; expenses exclude consumption; "
                            + "single-clinic performance uses each confirmed invoice line once for the actual treating dentist",
                    complete, facts, issues, corrections);
        }, c -> ids.incrementAndGet(), c -> ids.incrementAndGet(), c -> ids.incrementAndGet(), clock);
    }

    private static List<QuarterlyPreviewService.Entry> performanceFacts(
            Connection c, long clinic, List<QuarterlyPreviewService.Issue> issues) throws SQLException {
        var facts = new java.util.ArrayList<QuarterlyPreviewService.Entry>();
        try (var sql = c.prepareStatement("""
                SELECT item.invoice_item_id, b.invoice_version_no, b.confirmed_at, item.line_amount,
                       v.actual_dentist_id,
                       COALESCE(r.visit_id=v.visit_id AND rv.responsible_dentist_id=v.actual_dentist_id
                         AND rv.implementation_result='已实施' AND btrim(v.arrival_verification)<>'',false)
                FROM adc.treatment_invoice i
                JOIN adc.visit v ON v.visit_id=i.visit_id
                JOIN adc.treatment_invoice_version b ON b.invoice_id=i.invoice_id AND b.is_current
                LEFT JOIN adc.treatment_invoice_item item ON item.invoice_id=b.invoice_id
                    AND item.invoice_version_no=b.invoice_version_no
                LEFT JOIN adc.clinical_record r ON r.clinical_record_id=item.clinical_record_id
                LEFT JOIN adc.clinical_record_version rv ON rv.clinical_record_id=item.clinical_record_id
                    AND rv.record_version_no=item.record_version_no
                WHERE v.clinic_id=? AND b.confirmation_information IS NOT NULL
                  AND b.confirmed_at>=? AND b.confirmed_at<?
                ORDER BY item.invoice_item_id
                """)) {
            sql.setLong(1, clinic);
            sql.setObject(2, QUARTER_START.atOffset(ZoneOffset.UTC));
            sql.setObject(3, QUARTER_END.atOffset(ZoneOffset.UTC));
            try (var rows = sql.executeQuery()) {
                while (rows.next()) {
                    if (!rows.getBoolean(6)) {
                        issues.add(new QuarterlyPreviewService.Issue(clinic, "已确认账单缺少可核实的诊疗业绩归属"));
                        continue;
                    }
                    facts.add(new QuarterlyPreviewService.Entry("treatment_invoice_item/" + rows.getLong(1),
                            rows.getString(2), clinic, rows.getObject(3, OffsetDateTime.class).toInstant(),
                            QuarterlyPreviewService.Metric.PERFORMANCE, rows.getBigDecimal(4), rows.getLong(5), null));
                }
            }
        }
        return facts;
    }

    private static List<QuarterlyPreviewService.Entry> appointmentFacts(
            Connection c, long clinic, List<QuarterlyPreviewService.Issue> issues) throws SQLException {
        var facts = new java.util.ArrayList<QuarterlyPreviewService.Entry>();
        try (var sql = c.prepareStatement("""
                SELECT a.appointment_id, a.operated_at, a.starts_at, a.appointment_status,
                       EXISTS (SELECT 1 FROM adc.visit v WHERE v.appointment_id=a.appointment_id
                           AND v.clinic_id=s.clinic_id AND btrim(v.arrival_verification)<>'') AS attended,
                       COALESCE(btrim(a.no_show_verification)<>'',false) AS no_show,
                       COALESCE(btrim(a.cancellation_verification)<>'',false) AS cancelled
                FROM adc.appointment a
                JOIN adc.reception_schedule_version s USING (schedule_id, schedule_version_no)
                WHERE s.clinic_id=? AND a.starts_at>=? AND a.starts_at<?
                ORDER BY a.appointment_id
                """)) {
            sql.setLong(1, clinic);
            sql.setObject(2, QUARTER_START.atOffset(ZoneOffset.UTC));
            sql.setObject(3, QUARTER_END.atOffset(ZoneOffset.UTC));
            try (var rows = sql.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong(1);
                    String state = rows.getString(4);
                    boolean attended = rows.getBoolean(5);
                    boolean noShow = rows.getBoolean(6);
                    boolean cancelled = rows.getBoolean(7);
                    var result = switch (state) {
                        case "已到诊" -> attended && !noShow && !cancelled
                                ? QuarterlyPreviewService.AppointmentResult.ATTENDED : null;
                        case "已确认未到诊" -> noShow && !attended && !cancelled
                                ? QuarterlyPreviewService.AppointmentResult.CONFIRMED_NO_SHOW : null;
                        case "已取消" -> cancelled && !attended && !noShow
                                ? QuarterlyPreviewService.AppointmentResult.CANCELLED : null;
                        default -> QuarterlyPreviewService.AppointmentResult.UNCONFIRMED;
                    };
                    if (result == null) {
                        issues.add(new QuarterlyPreviewService.Issue(clinic, "预约 " + id + " 状态与核实依据不一致"));
                        continue;
                    }
                    facts.add(new QuarterlyPreviewService.Entry("appointment/" + id,
                            rows.getObject(2, OffsetDateTime.class).toInstant().toString(), clinic,
                            rows.getObject(3, OffsetDateTime.class).toInstant(),
                            QuarterlyPreviewService.Metric.APPOINTMENT, null, null, result));
                }
            }
        }
        return facts;
    }

    private static List<QuarterlyPreviewService.Entry> billingFacts(Connection c, long clinic) throws SQLException {
        var facts = new java.util.ArrayList<QuarterlyPreviewService.Entry>();
        try (var sql = c.prepareStatement("""
                SELECT 'RECEIVABLE' AS metric, 'treatment_invoice/' || i.invoice_id AS fact_id,
                       b.invoice_version_no::text AS version, b.confirmed_at AS event_time, b.total_amount AS amount
                FROM adc.treatment_invoice i
                JOIN adc.visit v ON v.visit_id=i.visit_id
                JOIN adc.treatment_invoice_version b ON b.invoice_id=i.invoice_id AND b.is_current
                WHERE v.clinic_id=? AND b.confirmation_information IS NOT NULL
                  AND b.confirmed_at>=? AND b.confirmed_at<?
                UNION ALL
                SELECT 'RECEIVED', 'payment_transaction/' || p.payment_id, p.invoice_version_no::text,
                       p.received_at, p.received_amount
                FROM adc.payment_transaction p JOIN adc.treatment_invoice i ON i.invoice_id=p.invoice_id
                JOIN adc.visit v ON v.visit_id=i.visit_id
                WHERE v.clinic_id=? AND p.local_settlement_status='已结算' AND p.transaction_result='模拟成功'
                  AND p.received_at>=? AND p.received_at<?
                ORDER BY metric, fact_id
                """)) {
            for (int first : new int[]{1, 4}) {
                sql.setLong(first, clinic);
                sql.setObject(first + 1, QUARTER_START.atOffset(ZoneOffset.UTC));
                sql.setObject(first + 2, QUARTER_END.atOffset(ZoneOffset.UTC));
            }
            try (var rows = sql.executeQuery()) {
                while (rows.next()) {
                    facts.add(new QuarterlyPreviewService.Entry(rows.getString(2), rows.getString(3),
                            clinic, rows.getObject(4, OffsetDateTime.class).toInstant(),
                            QuarterlyPreviewService.Metric.valueOf(rows.getString(1)), rows.getBigDecimal(5), null, null));
                }
            }
        }
        return facts;
    }

    private static List<QuarterlyPreviewService.Entry> consumptionFacts(Connection c, long clinic) throws SQLException {
        var facts = new java.util.ArrayList<QuarterlyPreviewService.Entry>();
        try (var sql = c.prepareStatement("""
                SELECT x.consumption_id, x.occurred_at, x.consumption_cost
                FROM adc.supply_consumption x JOIN adc.medical_supply_stock s ON s.stock_id=x.stock_id
                WHERE s.clinic_id=? AND x.occurred_at>=? AND x.occurred_at<?
                ORDER BY x.consumption_id
                """)) {
            sql.setLong(1, clinic);
            sql.setObject(2, QUARTER_START.atOffset(ZoneOffset.UTC));
            sql.setObject(3, QUARTER_END.atOffset(ZoneOffset.UTC));
            try (var rows = sql.executeQuery()) {
                while (rows.next()) {
                    facts.add(new QuarterlyPreviewService.Entry("supply_consumption/" + rows.getLong(1), "immutable",
                            clinic, rows.getObject(2, OffsetDateTime.class).toInstant(),
                            QuarterlyPreviewService.Metric.CONSUMPTION, rows.getBigDecimal(3), null, null));
                }
            }
        }
        return facts;
    }

    private static List<QuarterlyPreviewService.Entry> loadExpenses(String quarter, long clinic)
            throws java.io.IOException {
        try (var stream = ClassroomQuarterlyConfiguration.class.getResourceAsStream("/p0/classroom-expenses.json")) {
            if (stream == null) throw new java.io.IOException("Classroom expense manifest unavailable");
            var manifest = AuditDocuments.parse(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            AuditDocuments.fields(manifest, "classification", "manifestVersion", "quarter", "clinicId",
                    "includesConsumption", "expenses");
            String manifestVersion = AuditDocuments.text(manifest, "manifestVersion");
            if (!"SYNTHETIC".equals(AuditDocuments.text(manifest, "classification"))
                    || !quarter.equals(AuditDocuments.text(manifest, "quarter"))
                    || !manifest.path("clinicId").isIntegralNumber() || manifest.path("clinicId").asLong() != clinic
                    || !manifest.path("includesConsumption").isBoolean() || manifest.path("includesConsumption").asBoolean()
                    || !manifest.path("expenses").isArray() || manifestVersion.isBlank()) {
                throw new IllegalArgumentException("CLASSROOM_EXPENSE_MANIFEST_UNVERIFIED");
            }
            var seen = new java.util.HashSet<String>();
            var facts = new java.util.ArrayList<QuarterlyPreviewService.Entry>();
            for (var row : manifest.path("expenses")) {
                AuditDocuments.fields(row, "id", "version", "occurredAt", "amount", "category", "verifiedBasis");
                String id = AuditDocuments.text(row, "id");
                String version = AuditDocuments.text(row, "version");
                String category = AuditDocuments.text(row, "category");
                String basis = AuditDocuments.text(row, "verifiedBasis");
                String amount = AuditDocuments.text(row, "amount");
                Instant date = cn.adc.shared.time.StrictInputPolicy.instant(AuditDocuments.text(row, "occurredAt"));
                if (id.isBlank() || version.isBlank() || category.isBlank() || basis.isBlank() || !seen.add(id)
                        || !amount.matches("[0-9]{1,16}\\.[0-9]{2}") || date.isBefore(QUARTER_START) || !date.isBefore(QUARTER_END)) {
                    throw new IllegalArgumentException("CLASSROOM_EXPENSE_ENTRY_UNVERIFIED");
                }
                String proof = AuditDocuments.JSON.createObjectNode().put("manifestVersion", manifestVersion)
                        .put("category", category).put("verifiedBasis", basis).put("includesConsumption", false).toString();
                facts.add(new QuarterlyPreviewService.Entry(id, version, clinic, date,
                        QuarterlyPreviewService.Metric.EXPENSE, new BigDecimal(amount), null, null, proof));
            }
            return facts;
        }
    }

    private static QuarterlyPreviewService.Entry applyExpenseCorrection(
            Connection c, QuarterlyPreviewService.Entry expense,
            List<QuarterlyPreviewService.AppliedCorrection> corrections) throws SQLException {
        try (var sql = c.prepareStatement("""
                SELECT h.correction_history_id, h.corrected_value, h.resulting_statistic_id
                FROM adc.quarterly_issue_correction_history h
                JOIN adc.quarterly_data_issue i ON i.issue_id=h.issue_id
                WHERE i.issue_id=18002 AND i.statistic_id=18001 AND i.clinic_id=81001
                  AND i.correction_status='复核通过' AND h.correction_status='复核通过'
                  AND h.checked_at IS NOT NULL AND h.corrected_value=i.corrected_value
                ORDER BY h.corrected_at DESC, h.correction_history_id DESC LIMIT 1
                """); var rows = sql.executeQuery()) {
            if (!rows.next()) return expense;
            long history = rows.getLong(1);
            String value = rows.getString(2);
            String version = "reviewed/" + history;
            if (rows.getObject(3) == null) {
                corrections.add(new QuarterlyPreviewService.AppliedCorrection(
                        history, value, expense.id(), expense.version(), version, expense.clinic()));
            }
            var proof = (tools.jackson.databind.node.ObjectNode) AuditDocuments.parse(expense.sourceEvidence());
            proof.put("correctionHistoryId", history).put("originalAmount", expense.amount().toPlainString());
            return new QuarterlyPreviewService.Entry(expense.id(), version, expense.clinic(), expense.occurredAt(),
                    expense.metric(), new BigDecimal(value), null, null, proof.toString());
        }
    }

    @Bean QuarterlyIssueController.Access quarterlyIssue(JdbcTransactions tx, StaffBindingAdapter bindings) {
        var source = new QuarterlyIssueService.Sources() {
            @Override public QuarterlyIssueService.Correction verify(Connection c, QuarterlyIssueService.Target target,
                    QuarterlyIssueService.Issue issue, String evidence, String proposed) {
                if (target.issue() != 18002 || target.statistic() != 18001
                        || !"SYNTHETIC proof".equals(evidence) || !"8.50".equals(proposed)) return null;
                return new QuarterlyIssueService.Correction(proposed, evidence, null, null);
            }

            @Override public QuarterlyIssueService.Check recheck(Connection c, QuarterlyIssueService.Target target,
                    QuarterlyIssueService.Issue issue, long history) {
                return target.issue() == 18002 && target.statistic() == 18001 && "8.50".equals(issue.previous())
                        ? new QuarterlyIssueService.Check(true, "SYNTHETIC classroom check", "SYNTHETIC source v1") : null;
            }
        };
        return new QuarterlyIssueController.StaffAccess(tx, bindings, source,
                c -> ids.incrementAndGet(), c -> ids.incrementAndGet(), clock);
    }

    @Bean QuarterlyCalculationController.Access quarterlyCalculation(JdbcTransactions tx, StaffBindingAdapter bindings) {
        return new QuarterlyCalculationController.StaffAccess(tx, bindings, (c, scope, frozen) ->
                Set.of("SYNTHETIC-classroom-v1", "SYNTHETIC-classroom-v2", "SYNTHETIC-classroom-v3",
                        "SYNTHETIC-classroom-v4", "SYNTHETIC-classroom-v5", "SYNTHETIC-classroom-v6",
                        "SYNTHETIC-classroom-v7").contains(frozen.version())
                        ? Optional.of(new QuarterlyCalculationService.Policy("SYNTHETIC accrual",
                                QuarterlyCalculationService.Revenue.RECEIVABLE, false, true))
                        : Optional.empty(), c -> ids.incrementAndGet(), c -> ids.incrementAndGet(), clock);
    }

    @Bean QuarterlyFinancialReviewController.Access quarterlyFinancialReview(JdbcTransactions tx, StaffBindingAdapter bindings) {
        return new QuarterlyFinancialReviewController.StaffAccess(tx, bindings, c -> ids.incrementAndGet(),
                c -> ids.incrementAndGet(), clock);
    }
}
