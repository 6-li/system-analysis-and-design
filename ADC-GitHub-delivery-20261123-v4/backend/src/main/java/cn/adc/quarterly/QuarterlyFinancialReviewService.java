package cn.adc.quarterly;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore.EventIdAllocator;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static cn.adc.quarterly.QuarterlyCalculationService.money;
import static cn.adc.shared.audit.AuditDocuments.JSON;

final class QuarterlyFinancialReviewService implements CommandRecovery.SuccessVerifier {
    static final String UC = "UC20";
    static final String OP = "QUARTER_FINANCIAL_PASS";
    private final JdbcTransactions tx;
    private final StaffAuthorization auth;
    private final EventIdAllocator ids;
    private final Clock clock;
    private final CommandRecovery commands;

    QuarterlyFinancialReviewService(JdbcTransactions tx, StaffAuthorization auth, EventIdAllocator ids,
                                    EventIdAllocator auditIds, Clock clock) {
        this.tx = tx;
        this.auth = auth;
        this.ids = java.util.Objects.requireNonNull(ids);
        this.clock = clock;
        this.commands = new UcAuditService(tx, auth, this, clock, java.util.Objects.requireNonNull(auditIds)).commands();
    }

    ObjectNode read(long id, int version, Set<Long> clinics) {
        auth.require(UC, clinics);
        return tx.execute(c -> {
            var result = snapshot(c, id, version, clinics);
            auth.require(UC, clinics);
            return result.put("basisHash", AuditDocuments.hash(result));
        });
    }

    private static ObjectNode snapshot(Connection c, long id, int version, Set<Long> clinics) throws SQLException {
        QuarterlyCalculationService.lock(c, id);
        var current = QuarterlyCalculationService.current(c, id);
        QuarterlyCalculationService.requireScope(c, current.statistic(), clinics);
        if (current.version() != version) throw new Rejection("CALCULATION_VERSION_STALE");
        var frozen = QuarterlyCalculationService.frozen(c, current.statistic());
        try (var sql = c.prepareStatement("""
                SELECT calculation_basis,net_profit,incentive_pool_amount,generated_by,report_status
                FROM adc.quarterly_operating_calculation_version WHERE calculation_id=? AND calculation_version_no=?
                """)) {
            sql.setLong(1, id);
            sql.setInt(2, version);
            try (var row = sql.executeQuery()) {
                if (!row.next() || !QuarterlyCalculationService.READY.equals(row.getString(5))
                        || row.getBigDecimal(2) == null || row.getBigDecimal(3) == null) throw new Rejection("CALCULATION_NOT_REVIEWABLE");
                var basis = AuditDocuments.parse(row.getString(1));
                var manifest = AuditDocuments.parse(frozen.source());
                if (!"达到汇总要求".equals(frozen.readiness())
                        || frozen.version() == null || frozen.manifestReference() == null
                        || !java.util.Objects.equals(frozen.quarter(), basis.path("quarter").asText(null))
                        || !Long.toString(current.statistic()).equals(basis.path("statisticId").asText())
                        || !java.util.Objects.equals(frozen.version(), basis.path("sourceVersion").asText(null))
                        || !java.util.Objects.equals(frozen.manifestReference(), basis.path("manifestReference").asText(null))
                        || !frozen.version().equals(manifest.path("version").asText())
                        || !manifest.path("facts").isArray()
                        || !AuditDocuments.hash(manifest).equals(basis.path("manifestHash").asText())) {
                    throw new Rejection("CALCULATION_SOURCE_MISMATCH");
                }
                for (var fact : manifest.path("facts")) {
                    if (!fact.path("clinic").isIntegralNumber() || !clinics.contains(fact.path("clinic").asLong())) {
                        throw new Rejection("CALCULATION_SOURCE_MISMATCH");
                    }
                }
                var result = QuarterlyCalculationService.reference(current.statistic(), id, version)
                        .put("netProfit", money(row.getBigDecimal(2))).put("incentivePool", money(row.getBigDecimal(3)))
                        .put("generatedBy", row.getString(4));
                result.set("basis", basis);
                result.set("source", manifest);
                var scope = result.putArray("clinics");
                clinics.stream().sorted().forEach(clinic -> scope.add(Long.toString(clinic)));
                var performance = result.putArray("performance");
                try (var details = c.prepareStatement("""
                        SELECT dentist_id,performance_amount,attribution_basis FROM adc.quarterly_dentist_performance_calc
                        WHERE calculation_id=? AND calculation_version_no=? ORDER BY dentist_id
                        """)) {
                    details.setLong(1, id);
                    details.setInt(2, version);
                    try (var rows = details.executeQuery()) {
                        while (rows.next()) performance.addObject().put("dentistId", Long.toString(rows.getLong(1)))
                                .put("amount", money(rows.getBigDecimal(2))).put("attributionBasis", rows.getString(3));
                    }
                }
                return result;
            }
        }
    }

    CommandRecovery.Receipt approve(long id, int version, Set<Long> clinics, String hash, String notes, UUID key) {
        if (!hash.matches("[0-9a-f]{64}") || notes.isBlank() || notes.length() > 2000)
            throw new IllegalArgumentException("INVALID_FINANCIAL_REVIEW");
        var input = JSON.createObjectNode().put("calculationId", id).put("version", version).put("basisHash", hash).put("notes", notes);
        return commands.execute(new CommandRecovery.Command(OP, UC, key, input, clinics),
                (c, locks) -> approve(c, id, version, clinics, hash, notes));
    }

    AuditDocuments.Change approve(Connection c, long id, int version, Set<Long> clinics, String hash, String notes) throws SQLException {
        var actor = auth.require(UC, clinics).actor();
        var snapshot = snapshot(c, id, version, clinics);
        if (!hash.equals(AuditDocuments.hash(snapshot))) throw new Rejection("REVIEW_BASIS_STALE");
        if (actor.equals(snapshot.path("generatedBy").asText())) throw new SecurityException("REVIEWER_NOT_INDEPENDENT");
        if (!actor.equals(auth.require(UC, clinics).actor())) throw new SecurityException("REVIEW_IDENTITY_CHANGED");
        long reviewId = ids.next(c);
        if (reviewId <= 0) throw new SQLException("REVIEW_ID_UNAVAILABLE", "22003");
        var at = StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC);
        try (var sql = c.prepareStatement("""
                INSERT INTO adc.financial_review
                  (review_id,calculation_id,calculation_version_no,review_result,review_notes,reviewed_by,source_basis,reviewed_at)
                VALUES (?,?,?,'通过',?,?,?,?)
                """)) {
            sql.setLong(1, reviewId);
            sql.setLong(2, id);
            sql.setInt(3, version);
            sql.setString(4, notes);
            sql.setString(5, actor);
            sql.setString(6, snapshot.toString());
            sql.setObject(7, at);
            if (sql.executeUpdate() != 1) throw new SQLException("REVIEW_WRITE_UNCONFIRMED", "P0001");
        }
        var ref = QuarterlyCalculationService.reference(snapshot.path("statisticId").asLong(), id, version)
                .put("reviewId", Long.toString(reviewId));
        var result = ref.deepCopy().put("result", "通过").put("reviewedBy", actor).put("reviewedAt", at.toString());
        return new AuditDocuments.Change(ref, null, result, JSON.createObjectNode().put("basisHash", hash));
    }

    @Override public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
        if (!UC.equals(command.ucCode()) || !OP.equals(command.operation())) return false;
        var actor = auth.require(UC, command.clinics()).actor();
        var reference = saved.path("businessReference");
        QuarterlyCalculationService.requireScope(c, reference.path("statisticId").asLong(), command.clinics());
        try (var sql = c.prepareStatement("""
                SELECT reviewed_by,review_notes,source_basis FROM adc.financial_review
                WHERE review_id=? AND calculation_id=? AND calculation_version_no=? AND review_result='通过'
                """)) {
            sql.setLong(1, reference.path("reviewId").asLong());
            sql.setLong(2, command.payload().path("calculationId").asLong());
            sql.setInt(3, command.payload().path("version").asInt());
            try (var row = sql.executeQuery()) {
                return row.next() && actor.equals(row.getString(1)) && command.payload().path("notes").asText().equals(row.getString(2))
                        && command.payload().path("basisHash").asText().equals(AuditDocuments.hash(AuditDocuments.parse(row.getString(3))));
            }
        }
    }

    @Override public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        return true;
    }
}
