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

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

final class QuarterlyIssueService implements CommandRecovery.SuccessVerifier {
    static final String CORRECT = "QUARTER_ISSUE_CORRECT";
    static final String REVIEW = "QUARTER_ISSUE_REVIEW";
    private static final String UC = "UC18";
    record Target(long statistic, long issue, Set<Long> clinics) {
        Target { clinics = Set.copyOf(clinics); }
    }
    record Issue(Long clinic, String original, String previous, String status, String sourceVersion, Long history,
                 java.time.OffsetDateTime lastCorrectionAt) {}
    record Correction(String value, String basis, String attribution, String verifiedPrevious) {}
    record Check(boolean ready, String conclusion, String sourceBasis) {}
    interface Sources {
        Correction verify(Connection c, Target target, Issue issue, String evidence, String proposed) throws SQLException;
        Check recheck(Connection c, Target target, Issue issue, long history) throws SQLException;
        default boolean responsibleForCrossClinicIssue(Connection c, Target target, Issue issue) throws SQLException {
            return false;
        }
    }

    private final JdbcTransactions tx;
    private final StaffAuthorization auth;
    private final Sources sources;
    private final EventIdAllocator historyIds;
    private final Clock clock;
    private final CommandRecovery commands;

    QuarterlyIssueService(JdbcTransactions tx, StaffAuthorization auth, Sources sources, EventIdAllocator historyIds,
                          EventIdAllocator auditIds, Clock clock) {
        this.tx = tx;
        this.auth = auth;
        this.sources = sources;
        this.historyIds = historyIds;
        this.clock = clock;
        this.commands = new UcAuditService(tx, auth, this, clock, auditIds).commands();
    }

    JsonNode read(Target target, boolean correction) {
        auth.require(correction ? "UC18_CORRECTION" : UC, target.clinics());
        return tx.execute(c -> {
            var issue = load(c, target, false);
            authorize(c, target, issue, correction);
            var result = reference(target).put("original", issue.original()).put("current", issue.previous())
                    .put("status", issue.status()).put("sourceVersion", issue.sourceVersion());
            result.put("historyId", issue.history() == null ? null : issue.history().toString());
            return result;
        });
    }

    CommandRecovery.Receipt execute(Target target, UUID key, boolean correction, Long expectedHistory, String evidence, String value) {
        if (correction ? missing(evidence) || missing(value) : expectedHistory == null) {
            throw new Rejection("QUARTER_ISSUE_INPUT_REQUIRED");
        }
        var payload = JSON.createObjectNode().put("statisticId", target.statistic()).put("issueId", target.issue())
                .put("expectedHistory", expectedHistory).put("evidence", evidence).put("proposed", value);
        var command = new CommandRecovery.Command(correction ? CORRECT : REVIEW, UC, key, payload, target.clinics());
        String request = commands.reference(command);
        return commands.execute(command, (c, locks) -> write(c, target, correction, expectedHistory, evidence, value, request));
    }

    AuditDocuments.Change write(Connection c, Target target, boolean correction, Long expectedHistory,
                                String evidence, String value, String request) throws SQLException {
        var issue = load(c, target, true);
        String actor = authorize(c, target, issue, correction);
        if (!java.util.Objects.equals(expectedHistory, issue.history())) throw new Rejection("QUARTER_ISSUE_STALE");
        var before = reference(target).put("current", issue.previous()).put("status", issue.status());
        var at = StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC);
        long history;
        String status;
        String result;
        if (correction) {
            if (issue.lastCorrectionAt() != null && !at.isAfter(issue.lastCorrectionAt())) {
                throw new Rejection("QUARTER_CORRECTION_CLOCK_UNVERIFIED");
            }
            var details = sources.verify(c, target, issue, evidence, value);
            if (details == null || !value.equals(details.value()) || missing(details.basis())
                    || issue.history() != null && !java.util.Objects.equals(details.verifiedPrevious(), issue.previous())) {
                throw new Rejection("QUARTER_CORRECTION_UNVERIFIED");
            }
            history = historyIds.next(c);
            if (history <= 0) throw new SQLException("QUARTER_HISTORY_ID_UNAVAILABLE", "22003");
            status = "待复核";
            result = null;
            try (var sql = c.prepareStatement("""
                    INSERT INTO adc.quarterly_issue_correction_history
                        (correction_history_id,issue_id,previous_value,corrected_value,correction_basis,business_attribution,
                         corrected_by,corrected_at,correction_status,correction_request_reference)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """)) {
                sql.setLong(1, history);
                sql.setLong(2, target.issue());
                sql.setString(3, details.verifiedPrevious());
                sql.setString(4, details.value());
                sql.setString(5, details.basis());
                sql.setString(6, details.attribution());
                sql.setString(7, actor);
                sql.setObject(8, at);
                sql.setString(9, status);
                sql.setString(10, request);
                written(sql.executeUpdate());
            }
            try (var sql = c.prepareStatement("""
                    UPDATE adc.quarterly_data_issue SET corrected_value=?,correction_basis=?,business_attribution=?,
                           corrected_by=?,correction_status=?,consistency_check_result=NULL
                    WHERE issue_id=? AND statistic_id=?
                    """)) {
                sql.setString(1, details.value());
                sql.setString(2, details.basis());
                sql.setString(3, details.attribution());
                sql.setString(4, actor);
                sql.setString(5, status);
                sql.setLong(6, target.issue());
                sql.setLong(7, target.statistic());
                written(sql.executeUpdate());
            }
        } else {
            if (issue.history() == null || !"待复核".equals(issue.status())) throw new Rejection("QUARTER_CORRECTION_NOT_PENDING");
            history = issue.history();
            var check = sources.recheck(c, target, issue, history);
            if (check == null || missing(check.conclusion()) || missing(check.sourceBasis())) {
                throw new Rejection("QUARTER_CONSISTENCY_CHECK_UNAVAILABLE");
            }
            result = check.conclusion();
            status = check.ready() ? "复核通过" : "待核实";
            try (var sql = c.prepareStatement("""
                    UPDATE adc.quarterly_issue_correction_history
                    SET consistency_check_result=?,check_basis=?,checked_at=?,correction_status=?
                    WHERE correction_history_id=? AND issue_id=? AND checked_at IS NULL
                    """)) {
                sql.setString(1, result);
                sql.setString(2, check.sourceBasis());
                sql.setObject(3, at);
                sql.setString(4, status);
                sql.setLong(5, history);
                sql.setLong(6, target.issue());
                written(sql.executeUpdate());
            }
            try (var sql = c.prepareStatement("""
                    UPDATE adc.quarterly_data_issue SET correction_status=?,consistency_check_result=?
                    WHERE issue_id=? AND statistic_id=?
                    """)) {
                sql.setString(1, status);
                sql.setString(2, result);
                sql.setLong(3, target.issue());
                sql.setLong(4, target.statistic());
                written(sql.executeUpdate());
            }
        }
        var ref = reference(target).put("historyId", Long.toString(history));
        var saved = ref.deepCopy().put("status", status).put("current", correction ? value : issue.previous())
                .put("consistencyResult", result);
        return new AuditDocuments.Change(ref, before, saved, JSON.createObjectNode().put("operation", correction ? CORRECT : REVIEW));
    }

    private String authorize(Connection c, Target target, Issue issue, boolean correction) throws SQLException {
        var actor = auth.require(correction ? "UC18_CORRECTION" : UC, target.clinics()).actor();
        var members = new HashSet<Long>();
        try (var sql = c.prepareStatement("SELECT clinic_id FROM adc.quarterly_statistic_clinic WHERE statistic_id=?")) {
            sql.setLong(1, target.statistic());
            try (var rows = sql.executeQuery()) {
                while (rows.next()) members.add(rows.getLong(1));
            }
        }
        boolean allowed = correction
                ? target.clinics().size() == 1 && members.containsAll(target.clinics())
                    && (issue.clinic() == null ? sources.responsibleForCrossClinicIssue(c, target, issue)
                                              : target.clinics().contains(issue.clinic()))
                : members.equals(target.clinics());
        if (!allowed) throw new SecurityException("QUARTER_ISSUE_SCOPE_DENIED");
        return actor;
    }

    private static Issue load(Connection c, Target target, boolean lock) throws SQLException {
        String base = """
                SELECT i.clinic_id,i.original_data,i.corrected_value,i.correction_status,s.source_data_version,
                       latest.correction_history_id,latest.corrected_at
                FROM adc.quarterly_data_issue i JOIN adc.quarterly_operating_statistic s ON s.statistic_id=i.statistic_id
                LEFT JOIN LATERAL (
                    SELECT h.correction_history_id,h.corrected_at FROM adc.quarterly_issue_correction_history h
                    WHERE h.issue_id=i.issue_id ORDER BY h.corrected_at DESC,h.correction_history_id DESC LIMIT 1
                ) latest ON true
                WHERE i.issue_id=? AND i.statistic_id=?
                """;
        // Under READ COMMITTED obtain the issue lock before taking the snapshot that selects the latest history.
        if (lock) try (var sql = c.prepareStatement("SELECT issue_id FROM adc.quarterly_data_issue WHERE issue_id=? AND statistic_id=? FOR UPDATE")) {
            sql.setLong(1, target.issue());
            sql.setLong(2, target.statistic());
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new Rejection("QUARTER_ISSUE_NOT_FOUND");
            }
        }
        try (var sql = c.prepareStatement(base)) {
            sql.setLong(1, target.issue());
            sql.setLong(2, target.statistic());
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new Rejection("QUARTER_ISSUE_NOT_FOUND");
                return new Issue(row.getObject(1, Long.class), row.getString(2), row.getString(3),
                        row.getString(4), row.getString(5), row.getObject(6, Long.class),
                        row.getObject(7, java.time.OffsetDateTime.class));
            }
        }
    }

    private static tools.jackson.databind.node.ObjectNode reference(Target target) {
        return JSON.createObjectNode().put("statisticId", Long.toString(target.statistic())).put("issueId", Long.toString(target.issue()));
    }

    private static boolean missing(String s) { return s == null || s.isBlank(); }
    private static void written(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("QUARTER_ISSUE_WRITE_UNCONFIRMED", "P0001");
    }

    @Override public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
        if (!UC.equals(command.ucCode()) || !Set.of(CORRECT, REVIEW).contains(command.operation())) return false;
        var input = command.payload();
        var target = new Target(input.path("statisticId").asLong(), input.path("issueId").asLong(), command.clinics());
        boolean correction = CORRECT.equals(command.operation());
        var issue = load(c, target, false);
        authorize(c, target, issue, correction);
        var result = saved.path("after");
        try (var sql = c.prepareStatement("""
                SELECT corrected_value,correction_request_reference,consistency_check_result,checked_at
                FROM adc.quarterly_issue_correction_history WHERE correction_history_id=? AND issue_id=?
                """)) {
            sql.setLong(1, saved.path("businessReference").path("historyId").asLong());
            sql.setLong(2, target.issue());
            try (var row = sql.executeQuery()) {
                return row.next() && java.util.Objects.equals(row.getString(1), result.path("current").asText())
                        && (correction ? commands.reference(command).equals(row.getString(2))
                            : row.getObject(4) != null && java.util.Objects.equals(row.getString(3), result.path("consistencyResult").asText()));
            }
        }
    }

    @Override public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        return true;
    }
}
