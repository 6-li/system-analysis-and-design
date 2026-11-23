package cn.adc.quarterly;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore.EventIdAllocator;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

final class QuarterlyCalculationService implements CommandRecovery.SuccessVerifier {
    private static final String UC = "UC19";
    static final String OPERATION = "QUARTER_CALCULATE";
    static final String PENDING = "待核实";
    static final String READY = "待复核";

    enum Revenue { RECEIVABLE, RECEIVED }
    record Target(long statistic, Long calculation, Integer expectedVersion, Set<Long> clinics) {
        Target { clinics = Set.copyOf(clinics); }
    }
    record Frozen(String quarter, String readiness, String basis, String version, String source, String manifestReference,
                  BigDecimal receivable, BigDecimal received, BigDecimal expense, BigDecimal consumption) {}
    record Policy(String reference, Revenue revenue, boolean expensesIncludeConsumption, boolean reusePerformance) {}
    interface Policies {
        Optional<Policy> resolve(Connection c, Target target, Frozen frozen) throws SQLException;
    }
    record Amounts(BigDecimal net, BigDecimal pool, String pending) {}

    private final JdbcTransactions tx;
    private final StaffAuthorization auth;
    private final Policies policies;
    private final EventIdAllocator ids;
    private final Clock clock;
    private final CommandRecovery commands;

    QuarterlyCalculationService(JdbcTransactions tx, StaffAuthorization auth, Policies policies,
                                EventIdAllocator ids, EventIdAllocator auditIds, Clock clock) {
        this.tx = tx;
        this.auth = auth;
        this.policies = policies;
        this.ids = ids;
        this.clock = clock;
        this.commands = new UcAuditService(tx, auth, this, clock, java.util.Objects.requireNonNull(auditIds)).commands();
    }

    CommandRecovery.Receipt calculate(Target target, UUID key, String pendingNotes) {
        if ((target.calculation() == null) != (target.expectedVersion() == null)) throw new Rejection("CALCULATION_VERSION_REQUIRED");
        var payload = JSON.createObjectNode().put("statisticId", target.statistic()).put("calculationId", target.calculation())
                .put("expectedVersion", target.expectedVersion()).put("pendingNotes", pendingNotes);
        return commands.execute(new CommandRecovery.Command(OPERATION, UC, key, payload, target.clinics()),
                (c, locks) -> calculate(c, target, pendingNotes));
    }

    AuditDocuments.Change calculate(Connection c, Target target, String pendingNotes) throws SQLException {
        String actor = auth.require(UC, target.clinics()).actor();
        var source = frozen(c, target.statistic());
        requireScope(c, target.statistic(), target.clinics());
        long calculation;
        int version;
        if (target.calculation() == null) {
            calculation = ids.next(c);
            if (calculation <= 0) throw new SQLException("CALCULATION_ID_UNAVAILABLE", "22003");
            version = 1;
        } else {
            calculation = target.calculation();
            lock(c, calculation);
            var current = current(c, calculation);
            var prior = frozen(c, current.statistic());
            requireScope(c, current.statistic(), target.clinics());
            if (current.version() != target.expectedVersion() || !prior.quarter().equals(source.quarter())) {
                throw new Rejection("CALCULATION_VERSION_STALE");
            }
            version = Math.incrementExact(current.version());
        }

        Optional<Policy> policy = pendingNotes == null && "达到汇总要求".equals(source.readiness())
                ? policies.resolve(c, target, source) : Optional.empty();
        var amounts = pendingNotes == null ? amounts(source, policy.orElse(null))
                : new Amounts(null, null, pendingNotes);
        String status = amounts.pending() == null ? READY : PENDING;
        var basis = JSON.createObjectNode().put("quarter", source.quarter()).put("sourceVersion", source.version())
                .put("statisticId", Long.toString(target.statistic())).put("manifestReference", source.manifestReference())
                .put("manifestHash", AuditDocuments.hash(AuditDocuments.parse(source.source())))
                .put("receivable", money(source.receivable())).put("received", money(source.received()))
                .put("expense", money(source.expense())).put("consumption", money(source.consumption()));
        basis.set("statisticsBasis", AuditDocuments.parse(source.basis()));
        policy.filter(p -> p.revenue() != null).ifPresent(p -> basis.put("policyReference", p.reference()).put("revenueBasis", p.revenue().name())
                .put("expensesIncludeConsumption", p.expensesIncludeConsumption()));
        basis.put("incentiveRate", "0.10");

        auth.require(UC, target.clinics());
        try (var sql = c.prepareStatement("""
                INSERT INTO adc.quarterly_operating_calculation_version
                    (calculation_id,calculation_version_no,statistic_id,calculation_basis,net_profit,incentive_pool_amount,
                     report_status,pending_verification_notes,generated_by,generated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """)) {
            sql.setLong(1, calculation);
            sql.setInt(2, version);
            sql.setLong(3, target.statistic());
            sql.setString(4, basis.toString());
            sql.setBigDecimal(5, amounts.net());
            sql.setBigDecimal(6, amounts.pool());
            sql.setString(7, status);
            sql.setString(8, amounts.pending());
            sql.setString(9, actor);
            sql.setObject(10, StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC));
            if (sql.executeUpdate() != 1) throw new SQLException("CALCULATION_WRITE_UNCONFIRMED", "P0001");
        }
        if (READY.equals(status)) {
            try (var sql = c.prepareStatement("""
                    INSERT INTO adc.quarterly_dentist_performance_calc
                        (calculation_id,calculation_version_no,dentist_id,performance_amount,attribution_basis)
                    SELECT ?,?,dentist_id,performance_amount,attribution_basis
                    FROM adc.quarterly_dentist_performance_stat WHERE statistic_id=?
                    """)) {
                sql.setLong(1, calculation);
                sql.setInt(2, version);
                sql.setLong(3, target.statistic());
                sql.executeUpdate();
            }
        }
        var ref = reference(target.statistic(), calculation, version);
        var result = ref.deepCopy().put("status", status).put("netProfit", money(amounts.net()))
                .put("incentivePool", money(amounts.pool())).put("pendingNotes", amounts.pending());
        result.set("basis", basis);
        result.set("performance", QuarterlyRecordsService.performance(c,"calc",calculation,version));
        return new AuditDocuments.Change(ref, null, result, JSON.createObjectNode().put("basisHash", AuditDocuments.hash(basis)));
    }

    static Amounts amounts(Frozen source, Policy policy) {
        if (!"达到汇总要求".equals(source.readiness()) || source.receivable() == null || source.received() == null
                || source.expense() == null || source.consumption() == null) return new Amounts(null, null, "统计来源待核实");
        if (policy == null || policy.reference() == null || policy.reference().isBlank() || policy.revenue() == null
                || !policy.reusePerformance()) return new Amounts(null, null, "收入确认、费用包含关系或牙医业绩口径待核实");
        BigDecimal revenue = policy.revenue() == Revenue.RECEIVABLE ? source.receivable() : source.received();
        BigDecimal net = revenue.subtract(source.expense());
        if (!policy.expensesIncludeConsumption()) net = net.subtract(source.consumption());
        if (net.signum() < 0) return new Amounts(null, null, "负净利润激励处理政策待核实");
        net = net.setScale(2, RoundingMode.HALF_UP);
        if (net.precision() > 18) throw new Rejection("CALCULATION_AMOUNT_OUT_OF_RANGE");
        return new Amounts(net, net.movePointLeft(1).setScale(2, RoundingMode.HALF_UP), null);
    }

    static void lock(Connection c, long calculation) throws SQLException {
        try (var sql = c.prepareStatement("SELECT pg_advisory_xact_lock(41019,?)")) {
            sql.setInt(1, (int) ResourceLockCoordinator.key("quarter-calculation:" + calculation));
            sql.execute();
        }
    }

    record Current(int version, long statistic) {}

    static Current current(Connection c, long id) throws SQLException {
        try (var sql = c.prepareStatement("""
                SELECT calculation_version_no,statistic_id FROM adc.quarterly_operating_calculation_version
                WHERE calculation_id=? ORDER BY calculation_version_no DESC LIMIT 1
                """)) {
            sql.setLong(1, id);
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new Rejection("CALCULATION_NOT_FOUND");
                return new Current(row.getInt(1), row.getLong(2));
            }
        }
    }

    static Frozen frozen(Connection c, long statistic) throws SQLException {
        try (var sql = c.prepareStatement("""
                SELECT quarter_code,aggregation_readiness,accounting_basis,source_data_version,source_basis,
                       source_manifest_reference,receivable_amount,received_amount,operating_expense,consumption_cost
                FROM adc.quarterly_operating_statistic WHERE statistic_id=?
                """)) {
            sql.setLong(1, statistic);
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new Rejection("QUARTER_PREVIEW_NOT_FOUND");
                return new Frozen(row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getString(5),
                        row.getString(6), row.getBigDecimal(7), row.getBigDecimal(8), row.getBigDecimal(9), row.getBigDecimal(10));
            }
        }
    }

    static void requireScope(Connection c, long statistic, Set<Long> clinics) throws SQLException {
        try (var sql = c.prepareStatement("SELECT clinic_id FROM adc.quarterly_statistic_clinic WHERE statistic_id=?")) {
            sql.setLong(1, statistic);
            var scope = new HashSet<Long>();
            try (var rows = sql.executeQuery()) {
                while (rows.next()) scope.add(rows.getLong(1));
            }
            if (scope.isEmpty() || !scope.equals(clinics)) throw new SecurityException("CALCULATION_SCOPE_DENIED");
        }
    }

    static final String EXPORT = "QUARTER_REVIEWED_EXPORT";

    CommandRecovery.Receipt export(long calculation, int version, Set<Long> clinics, UUID key) {
        var payload = JSON.createObjectNode().put("calculationId", calculation).put("version", version);
        return commands.execute(new CommandRecovery.Command(EXPORT, UC, key, payload, clinics), (c, locks) -> {
            var report = report(c, calculation, version, clinics);
            return new AuditDocuments.Change(reference(report.path("statisticId").asLong(), calculation, version),
                    null, report, JSON.createObjectNode().put("reportHash", AuditDocuments.hash(report)));
        });
    }

    JsonNode report(Connection c, long calculation, int expected, Set<Long> clinics) throws SQLException {
        auth.require(UC, clinics);
        lock(c, calculation);
        var current = current(c, calculation);
        if (current.version() != expected) throw new Rejection("CALCULATION_VERSION_STALE");
        requireScope(c, current.statistic(), clinics);
        try (var sql = c.prepareStatement("""
                SELECT v.calculation_basis,v.net_profit,v.incentive_pool_amount,r.review_id,r.reviewed_by,
                       r.review_notes,r.source_basis,r.reviewed_at
                FROM adc.quarterly_operating_calculation_version v JOIN adc.financial_review r
                  ON r.calculation_id=v.calculation_id AND r.calculation_version_no=v.calculation_version_no
                WHERE v.calculation_id=? AND v.calculation_version_no=? AND r.review_result='通过'
                  AND v.report_status='待复核' AND v.net_profit IS NOT NULL AND v.incentive_pool_amount IS NOT NULL
                ORDER BY r.reviewed_at DESC,r.review_id DESC LIMIT 1
                """)) {
            sql.setLong(1, calculation);
            sql.setInt(2, expected);
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new Rejection("FINANCIAL_REVIEW_REQUIRED");
                var result = reference(current.statistic(), calculation, expected).put("netProfit", money(row.getBigDecimal(2)))
                        .put("incentivePool", money(row.getBigDecimal(3))).put("reviewId", Long.toString(row.getLong(4)))
                        .put("reviewedBy", row.getString(5)).put("reviewNotes", row.getString(6))
                        .put("reviewSource", row.getString(7)).put("reviewedAt", row.getString(8));
                result.set("basis", AuditDocuments.parse(row.getString(1)));
                var scope = result.putArray("clinics");
                clinics.stream().sorted().forEach(id -> scope.add(Long.toString(id)));
                var performance = result.putArray("performance");
                try (var details = c.prepareStatement("""
                        SELECT dentist_id,performance_amount,attribution_basis
                        FROM adc.quarterly_dentist_performance_calc
                        WHERE calculation_id=? AND calculation_version_no=? ORDER BY dentist_id
                        """)) {
                    details.setLong(1, calculation);
                    details.setInt(2, expected);
                    try (var rows = details.executeQuery()) {
                        while (rows.next()) performance.addObject().put("dentistId", Long.toString(rows.getLong(1)))
                                .put("amount", money(rows.getBigDecimal(2))).put("attributionBasis", rows.getString(3));
                    }
                }
                var reviewSource = AuditDocuments.parse(result.path("reviewSource").asText());
                for (var field : Set.of("calculationId", "version", "statisticId", "netProfit", "incentivePool",
                        "basis", "clinics", "performance")) {
                    if (!result.path(field).equals(reviewSource.path(field))) throw new Rejection("FINANCIAL_REVIEW_SOURCE_MISMATCH");
                }
                auth.require(UC, clinics);
                return result;
            }
        }
    }

    static tools.jackson.databind.node.ObjectNode reference(long statistic, long id, int version) {
        return JSON.createObjectNode().put("statisticId", Long.toString(statistic)).put("calculationId", Long.toString(id)).put("version", version);
    }

    static String money(BigDecimal n) { return n == null ? null : n.toPlainString(); }

    @Override public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
        if (!UC.equals(command.ucCode())) return false;
        if (EXPORT.equals(command.operation())) {
            try {
                var report = report(c, command.payload().path("calculationId").asLong(),
                        command.payload().path("version").asInt(), command.clinics());
                return AuditDocuments.hash(report).equals(saved.path("verification").path("reportHash").asText());
            } catch (Rejection e) { return false; }
        }
        if (!OPERATION.equals(command.operation())) return false;
        auth.require(UC, command.clinics());
        var ref = saved.path("businessReference");
        long statistic = command.payload().path("statisticId").asLong();
        requireScope(c, statistic, command.clinics());
        try (var sql = c.prepareStatement("""
                SELECT calculation_basis,net_profit,incentive_pool_amount,report_status,pending_verification_notes
                FROM adc.quarterly_operating_calculation_version WHERE calculation_id=? AND calculation_version_no=? AND statistic_id=?
                """)) {
            sql.setLong(1, ref.path("calculationId").asLong());
            sql.setInt(2, ref.path("version").asInt());
            sql.setLong(3, statistic);
            try (var row = sql.executeQuery()) {
                var result = saved.path("after");
                return row.next() && AuditDocuments.hash(AuditDocuments.parse(row.getString(1)))
                        .equals(saved.path("verification").path("basisHash").asText())
                        && java.util.Objects.equals(money(row.getBigDecimal(2)), result.path("netProfit").asText(null))
                        && java.util.Objects.equals(money(row.getBigDecimal(3)), result.path("incentivePool").asText(null))
                        && java.util.Objects.equals(row.getString(4), result.path("status").asText(null))
                        && java.util.Objects.equals(row.getString(5), result.path("pendingNotes").asText(null));
            }
        }
    }

    @Override public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        return true;
    }
}
