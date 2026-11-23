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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

final class QuarterlyPreviewService implements CommandRecovery.SuccessVerifier {
    private static final String UC = "UC17";
    static final String OPERATION = "QUARTERLY_PREVIEW_GENERATE";
    enum Metric { RECEIVABLE, RECEIVED, EXPENSE, CONSUMPTION, APPOINTMENT, PERFORMANCE }
    enum AppointmentResult { ATTENDED, CONFIRMED_NO_SHOW, CANCELLED, UNCONFIRMED }
    record Target(String quarter, Set<Long> clinics,boolean expenseReview) {
        Target(String quarter,Set<Long> clinics){this(quarter,clinics,false);}
        Target { clinics = Set.copyOf(clinics); }
    }
    record Entry(String id, String version, long clinic, Instant occurredAt, Metric metric,
                 BigDecimal amount, Long dentist, AppointmentResult appointmentResult, String sourceEvidence) {
        Entry(String id, String version, long clinic, Instant occurredAt, Metric metric,
              BigDecimal amount, Long dentist, AppointmentResult appointmentResult) {
            this(id, version, clinic, occurredAt, metric, amount, dentist, appointmentResult, null);
        }
    }
    record Issue(Long clinic, String reason) {}
    record AppliedCorrection(long historyId, String correctedValue, String factId, String previousVersion,
                             String appliedVersion, long clinic) {}
    record Manifest(Target target, String version, Instant start, Instant end, String rules, Set<Metric> complete,
                    List<Entry> entries, List<Issue> issues, List<AppliedCorrection> corrections) {
        Manifest {
            complete = Set.copyOf(complete);
            entries = List.copyOf(entries);
            issues = List.copyOf(issues);
            corrections = List.copyOf(corrections);
        }
        Manifest(Target target, String version, Instant start, Instant end, String rules, Set<Metric> complete,
                 List<Entry> entries, List<Issue> issues) {
            this(target, version, start, end, rules, complete, entries, issues, List.of());
        }
    }
    interface Sources {
        /** Resolve the verified scope, accounting rules and fact versions on the same connection. */
        Manifest resolve(Connection c, Target target) throws SQLException;
    }
    record Preview(ObjectNode values, ObjectNode source, ObjectNode basis, List<Issue> issues,
                   TreeMap<Long, BigDecimal> performance) {}

    private final StaffAuthorization auth;
    private final Sources sources;
    private final EventIdAllocator snapshots;
    private final EventIdAllocator issues;
    private final Clock clock;
    private final CommandRecovery commands;

    QuarterlyPreviewService(JdbcTransactions tx, StaffAuthorization auth, Sources sources, EventIdAllocator snapshots,
                            EventIdAllocator issues, EventIdAllocator auditIds, Clock clock) {
        this.auth = auth;
        this.sources = sources;
        this.snapshots = snapshots;
        this.issues = issues;
        this.clock = clock;
        this.commands = new UcAuditService(tx, auth, this, clock, auditIds).commands();
    }

    CommandRecovery.Receipt generate(Target target, UUID key) {
        var payload = JSON.createObjectNode().put("quarter", target.quarter());
        if(target.expenseReview())payload.put("expenseReview",true);
        return commands.execute(new CommandRecovery.Command(OPERATION, UC, key, payload, target.clinics()),
                (c, locks) -> generate(c, target));
    }

    AuditDocuments.Change generate(Connection c, Target target) throws SQLException {
        auth.require(UC, target.clinics());
        var manifest = sources.resolve(c, target);
        if (!target.equals(manifest.target()) || blank(manifest.version()) || manifest.version().length() > 200) {
            throw new Rejection("QUARTER_SOURCE_SCOPE_UNVERIFIED");
        }
        var now = StrictInputPolicy.eventTime(clock);
        var snapshot = summarize(manifest, now);
        if (!manifest.corrections().isEmpty()) {
            var tracked = snapshot.source().putArray("appliedCorrections");
            var histories = new HashSet<Long>();
            var facts = new HashSet<List<Object>>();
            for (var correction : manifest.corrections().stream()
                    .sorted(java.util.Comparator.comparingLong(AppliedCorrection::historyId)).toList()) {
                if (correction.historyId() <= 0 || blank(correction.correctedValue()) || blank(correction.factId())
                        || blank(correction.previousVersion()) || blank(correction.appliedVersion())
                        || correction.previousVersion().equals(correction.appliedVersion())
                        || !histories.add(correction.historyId())
                        || !facts.add(List.of(correction.clinic(), correction.factId()))
                        || !target.clinics().contains(correction.clinic())
                        || manifest.entries().stream().filter(e -> e.clinic() == correction.clinic()
                                && correction.factId().equals(e.id())).count() != 1
                        || java.util.stream.StreamSupport.stream(snapshot.source().path("facts").spliterator(), false)
                                .noneMatch(e -> e.path("clinic").asLong() == correction.clinic()
                                        && correction.factId().equals(e.path("id").asText())
                                        && correction.appliedVersion().equals(e.path("version").asText())
                                        && Set.of("纳入", "纳入应到诊", "单列取消")
                                                .contains(e.path("decision").asText()))) {
                    throw new Rejection("QUARTER_CORRECTION_SOURCE_UNVERIFIED");
                }
                tracked.addObject().put("historyId", correction.historyId()).put("clinic", correction.clinic())
                        .put("factId", correction.factId()).put("previousVersion", correction.previousVersion())
                        .put("appliedVersion", correction.appliedVersion());
            }
        }
        auth.require(UC, target.clinics());
        long id = next(snapshots, c);
        String manifestRef = "quarterly_operating_statistic/" + id + "/source_basis";
        try (var sql = c.prepareStatement("""
                INSERT INTO adc.quarterly_operating_statistic
                    (statistic_id,quarter_code,accounting_basis,source_data_version,source_basis,receivable_amount,
                     received_amount,operating_expense,consumption_cost,due_appointment_count,confirmed_no_show_count,
                     no_show_rate,aggregation_readiness,generated_at,cancelled_appointment_count,source_manifest_reference)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            sql.setLong(1, id);
            sql.setString(2, target.quarter());
            sql.setString(3, snapshot.basis().toString());
            sql.setString(4, manifest.version());
            sql.setString(5, snapshot.source().toString());
            var values = snapshot.values();
            sql.setBigDecimal(6, number(values, "receivable"));
            sql.setBigDecimal(7, number(values, "received"));
            sql.setBigDecimal(8, number(values, "expense"));
            sql.setBigDecimal(9, number(values, "consumption"));
            sql.setObject(10, count(values, "due"), java.sql.Types.INTEGER);
            sql.setObject(11, count(values, "noShow"), java.sql.Types.INTEGER);
            sql.setBigDecimal(12, number(values, "noShowRate"));
            sql.setString(13, values.path("readiness").asText());
            sql.setObject(14, now.atOffset(ZoneOffset.UTC));
            sql.setObject(15, count(values, "cancelled"), java.sql.Types.INTEGER);
            sql.setString(16, manifestRef);
            written(sql.executeUpdate());
        }
        try (var sql = c.prepareStatement("INSERT INTO adc.quarterly_statistic_clinic (statistic_id,clinic_id) VALUES (?,?)")) {
            for (long clinic : target.clinics().stream().sorted().toList()) {
                sql.setLong(1, id);
                sql.setLong(2, clinic);
                written(sql.executeUpdate());
            }
        }
        var warnings = JSON.createArrayNode();
        try (var sql = c.prepareStatement("""
                INSERT INTO adc.quarterly_data_issue (issue_id,statistic_id,clinic_id,original_data,correction_status)
                VALUES (?,?,?,?,'待核实')
                """)) {
            for (var issue : snapshot.issues()) {
                long issueId = next(issues, c);
                sql.setLong(1, issueId);
                sql.setLong(2, id);
                sql.setObject(3, issue.clinic(), java.sql.Types.BIGINT);
                sql.setString(4, issue.reason());
                written(sql.executeUpdate());
                warnings.addObject().put("issueId", Long.toString(issueId)).put("clinicId", issue.clinic()).put("reason", issue.reason());
            }
        }
        try (var sql = c.prepareStatement("""
                INSERT INTO adc.quarterly_dentist_performance_stat (statistic_id,dentist_id,performance_amount,attribution_basis)
                VALUES (?,?,?,?)
                """)) {
            for (var item : snapshot.performance().entrySet()) {
                sql.setLong(1, id);
                sql.setLong(2, item.getKey());
                sql.setBigDecimal(3, item.getValue());
                var attribution = JSON.createObjectNode().put("manifest", manifestRef).put("dentistId", item.getKey());
                sql.setString(4, attribution.toString());
                written(sql.executeUpdate());
            }
        }
        for (var correction : manifest.corrections().stream()
                .sorted(java.util.Comparator.comparingLong(AppliedCorrection::historyId)).toList()) {
            linkCorrection(c, id, correction);
        }
        var reference = JSON.createObjectNode().put("statisticId", Long.toString(id)).put("quarter", target.quarter());
        var result = snapshot.values().put("statisticId", Long.toString(id)).put("quarter", target.quarter())
                .put("sourceVersion", manifest.version()).put("manifestReference", manifestRef);
        result.set("basis", snapshot.basis());
        var resultClinics = result.putArray("clinics");
        target.clinics().stream().sorted().forEach(clinic -> resultClinics.add(Long.toString(clinic)));
        var clinicSummaries=result.putArray("clinicSummaries");
        for(long clinic:target.clinics().stream().sorted().toList()){
            var localTarget=new Target(target.quarter(),Set.of(clinic),target.expenseReview());
            var localManifest=new Manifest(localTarget,manifest.version(),manifest.start(),manifest.end(),manifest.rules(),manifest.complete(),
                manifest.entries().stream().filter(e->e.clinic()==clinic).toList(),manifest.issues().stream().filter(i->i.clinic()==null||i.clinic()==clinic).toList(),List.of());
            clinicSummaries.add(summarize(localManifest,clock.instant()).values().put("clinicId",Long.toString(clinic)));
        }
        result.set("issues", warnings);
        var dentists = result.putArray("performance");
        snapshot.performance().forEach((dentist, amount) -> dentists.addObject().put("dentistId", Long.toString(dentist))
                .put("amount", amount.toPlainString()));
        var proof = JSON.createObjectNode().put("manifestHash", AuditDocuments.hash(snapshot.source()));
        return new AuditDocuments.Change(reference, null, result, proof);
    }

    private static void linkCorrection(Connection c, long statistic, AppliedCorrection correction) throws SQLException {
        try (var lock = c.prepareStatement("""
                SELECT i.issue_id FROM adc.quarterly_data_issue i
                JOIN adc.quarterly_issue_correction_history h ON h.issue_id=i.issue_id
                WHERE h.correction_history_id=? FOR UPDATE OF i
                """)) {
            lock.setLong(1, correction.historyId());
            try (var row = lock.executeQuery()) {
                if (!row.next()) throw new Rejection("QUARTER_CORRECTION_LINK_STALE");
            }
        }
        try (var sql = c.prepareStatement("""
                UPDATE adc.quarterly_issue_correction_history h SET resulting_statistic_id=?
                FROM adc.quarterly_data_issue i
                JOIN adc.quarterly_operating_statistic old ON old.statistic_id=i.statistic_id
                JOIN adc.quarterly_operating_statistic fresh ON fresh.statistic_id=?
                WHERE h.correction_history_id=? AND i.issue_id=h.issue_id
                  AND h.resulting_statistic_id IS NULL AND h.correction_status='复核通过'
                  AND h.checked_at IS NOT NULL AND h.corrected_value=? AND i.correction_status='复核通过'
                  AND (i.clinic_id IS NULL OR i.clinic_id=?)
                  AND old.quarter_code=fresh.quarter_code AND old.statistic_id<>fresh.statistic_id
                  AND old.source_data_version<>fresh.source_data_version
                  AND NOT EXISTS (SELECT 1 FROM adc.quarterly_issue_correction_history newer
                    WHERE newer.issue_id=h.issue_id AND (newer.corrected_at,newer.correction_history_id)
                        > (h.corrected_at,h.correction_history_id))
                  AND (SELECT array_agg(clinic_id ORDER BY clinic_id) FROM adc.quarterly_statistic_clinic
                       WHERE statistic_id=old.statistic_id)
                    = (SELECT array_agg(clinic_id ORDER BY clinic_id) FROM adc.quarterly_statistic_clinic
                       WHERE statistic_id=fresh.statistic_id)
                  AND EXISTS (SELECT 1 FROM jsonb_array_elements(old.source_basis::jsonb -> 'facts') fact
                    WHERE fact->>'id'=? AND fact->>'version'=? AND fact->>'clinic'=?)
                """)) {
            sql.setLong(1, statistic);
            sql.setLong(2, statistic);
            sql.setLong(3, correction.historyId());
            sql.setString(4, correction.correctedValue());
            sql.setLong(5, correction.clinic());
            sql.setString(6, correction.factId());
            sql.setString(7, correction.previousVersion());
            sql.setString(8, Long.toString(correction.clinic()));
            if (sql.executeUpdate() != 1) throw new Rejection("QUARTER_CORRECTION_LINK_STALE");
        }
    }

    static Preview summarize(Manifest manifest, Instant now) {
        boolean bounded = manifest.start() != null && manifest.end() != null && manifest.start().isBefore(manifest.end())
                && !blank(manifest.rules());
        var unresolved = new java.util.ArrayList<Issue>();
        for (var issue : manifest.issues()) {
            if ((issue.clinic() != null && !manifest.target().clinics().contains(issue.clinic())) || blank(issue.reason())) {
                throw new Rejection("QUARTER_ISSUE_SCOPE_INVALID");
            }
            unresolved.add(issue);
        }
        if (!bounded) unresolved.add(new Issue(null, "季度边界或归集口径未核实"));
        var excluded = java.util.EnumSet.allOf(Metric.class);
        excluded.removeAll(manifest.complete());
        for (var metric : excluded) unresolved.add(new Issue(null, metric + " 来源未核实"));
        if (!bounded) excluded.addAll(java.util.EnumSet.allOf(Metric.class));
        var source = JSON.createObjectNode().put("version", manifest.version());
        var byMetric = new EnumMap<Metric, BigDecimal>(Metric.class);
        var performance = new TreeMap<Long, BigDecimal>();
        var seen = new EnumMap<Metric, Set<String>>(Metric.class);
        var rows = source.putArray("facts");
        int due = 0, noShow = 0, cancelled = 0;
        for (var fact : manifest.entries()) {
            if (!manifest.target().clinics().contains(fact.clinic())) throw new Rejection("QUARTER_SOURCE_OUTSIDE_SCOPE");
            if (fact.metric() == null) throw new Rejection("QUARTER_METRIC_MISSING");
            var row = rows.addObject().put("id", fact.id()).put("version", fact.version()).put("clinic", fact.clinic())
                    .put("metric", fact.metric().name()).put("dentistId", fact.dentist())
                    .put("at", fact.occurredAt() == null ? null : fact.occurredAt().toString())
                    .put("amount", fact.amount() == null ? null : fact.amount().toPlainString())
                    .put("appointmentResult", fact.appointmentResult() == null ? null : fact.appointmentResult().name());
            if (fact.sourceEvidence() != null) row.put("sourceEvidence", fact.sourceEvidence());
            if (excluded.contains(fact.metric())) {
                row.put("decision", "来源待核实");
                continue;
            }
            if (blank(fact.id()) || blank(fact.version()) || fact.occurredAt() == null) {
                excluded.add(fact.metric());
                unresolved.add(new Issue(fact.clinic(), fact.metric() + " 事实标识、版本或归期时间缺失"));
                row.put("decision", "事实待核实");
                continue;
            }
            if (fact.occurredAt().isBefore(manifest.start()) || !fact.occurredAt().isBefore(manifest.end())) {
                row.put("decision", "期外");
                continue;
            }
            if (!seen.computeIfAbsent(fact.metric(), ignored -> new HashSet<>()).add(fact.id())) {
                excluded.add(fact.metric());
                unresolved.add(new Issue(fact.clinic(), fact.metric() + " 来源存在重复事实 " + fact.id()));
                row.put("decision", "重复待核实");
                continue;
            }
            if (fact.metric() == Metric.APPOINTMENT) {
                if (fact.appointmentResult() == AppointmentResult.CANCELLED) {
                    cancelled++;
                    row.put("decision", "单列取消");
                } else if (fact.appointmentResult() == AppointmentResult.UNCONFIRMED || fact.occurredAt().isAfter(now)) {
                    row.put("decision", "预约未到期或结果未确认");
                } else if (fact.appointmentResult() == null) {
                    excluded.add(Metric.APPOINTMENT);
                    unresolved.add(new Issue(fact.clinic(), "预约结果依据缺失 " + fact.id()));
                    row.put("decision", "缺失");
                } else {
                    due++;
                    if (fact.appointmentResult() == AppointmentResult.CONFIRMED_NO_SHOW) noShow++;
                    row.put("decision", "纳入应到诊");
                }
            } else if (!isMoney(fact.amount()) || fact.metric() == Metric.PERFORMANCE && (fact.dentist() == null || fact.dentist() <= 0)) {
                excluded.add(fact.metric());
                unresolved.add(new Issue(fact.clinic(), fact.metric() + " 金额或实际牙医归属待核实 " + fact.id()));
                row.put("decision", "待核实");
            } else {
                if (fact.metric() == Metric.PERFORMANCE) performance.merge(fact.dentist(), fact.amount(), BigDecimal::add);
                else byMetric.merge(fact.metric(), fact.amount(), BigDecimal::add);
                row.put("decision", "纳入");
            }
        }
        if (excluded.contains(Metric.PERFORMANCE)) performance.clear();
        else if (performance.values().stream().anyMatch(total -> !isMoney(total))) {
            throw new Rejection("QUARTER_PERFORMANCE_OUT_OF_RANGE");
        }
        var zero = BigDecimal.ZERO.setScale(2);
        var values = JSON.createObjectNode();
        setMoney(values, "receivable", Metric.RECEIVABLE, excluded, byMetric, zero);
        setMoney(values, "received", Metric.RECEIVED, excluded, byMetric, zero);
        setMoney(values, "expense", Metric.EXPENSE, excluded, byMetric, zero);
        setMoney(values, "consumption", Metric.CONSUMPTION, excluded, byMetric, zero);
        if (excluded.contains(Metric.APPOINTMENT)) values.putNull("due").putNull("noShow").putNull("cancelled").putNull("noShowRate");
        else {
            values.put("due", due).put("noShow", noShow).put("cancelled", cancelled);
            values.put("noShowRate", due == 0 ? null
                    : BigDecimal.valueOf(noShow).divide(BigDecimal.valueOf(due), 6, RoundingMode.HALF_UP).toPlainString());
        }
        values.put("readiness", unresolved.isEmpty() ? "达到汇总要求" : "待核实").put("generatedAt", now.toString());
        var basis = JSON.createObjectNode().put("start", manifest.start() == null ? null : manifest.start().toString())
                .put("endExclusive", manifest.end() == null ? null : manifest.end().toString()).put("rules", manifest.rules());
        return new Preview(values, source, basis, List.copyOf(unresolved), performance);
    }

    private static void setMoney(ObjectNode body, String key, Metric metric, Set<Metric> excluded,
                                 EnumMap<Metric, BigDecimal> sums, BigDecimal zero) {
        BigDecimal total = sums.getOrDefault(metric, zero);
        if (!excluded.contains(metric) && !isMoney(total)) throw new Rejection("QUARTER_AMOUNT_OUT_OF_RANGE");
        body.put(key, excluded.contains(metric) ? null : total.setScale(2).toPlainString());
    }

    private static boolean isMoney(BigDecimal value) {
        return value != null && value.scale() >= 0 && value.scale() <= 2 && value.precision() - value.scale() <= 16;
    }

    private static BigDecimal number(JsonNode doc, String key) { return doc.path(key).isNull() ? null : new BigDecimal(doc.path(key).asText()); }
    private static Integer count(JsonNode doc, String key) { return doc.path(key).isNull() ? null : doc.path(key).asInt(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static long next(EventIdAllocator ids, Connection c) throws SQLException {
        long id = ids.next(c);
        if (id <= 0) throw new SQLException("QUARTER_ID_UNAVAILABLE", "22003");
        return id;
    }

    private static void written(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("QUARTER_WRITE_UNCONFIRMED", "P0001");
    }

    @Override public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
        if (!UC.equals(command.ucCode()) || !OPERATION.equals(command.operation())) return false;
        auth.require(UC, command.clinics());
        String quarter = command.payload().path("quarter").asText();
        var ref = saved.path("businessReference");
        JsonNode frozen;
        try (var sql = c.prepareStatement("""
                SELECT source_basis FROM adc.quarterly_operating_statistic WHERE statistic_id=? AND quarter_code=?
                """)) {
            sql.setLong(1, ref.path("statisticId").asLong());
            sql.setString(2, quarter);
            try (var row = sql.executeQuery()) {
                if (!row.next()) return false;
                frozen = AuditDocuments.parse(row.getString(1));
                if (!AuditDocuments.hash(frozen).equals(saved.path("verification").path("manifestHash").asText())) return false;
            }
        }
        if (frozen.has("appliedCorrections")) {
            try (var sql = c.prepareStatement("""
                    SELECT 1 FROM adc.quarterly_issue_correction_history
                    WHERE correction_history_id=? AND resulting_statistic_id=?
                    """)) {
                for (var correction : frozen.path("appliedCorrections")) {
                    sql.setLong(1, correction.path("historyId").asLong());
                    sql.setLong(2, ref.path("statisticId").asLong());
                    try (var row = sql.executeQuery()) {
                        if (!row.next()) return false;
                    }
                }
            }
        }
        try (var sql = c.prepareStatement("SELECT clinic_id FROM adc.quarterly_statistic_clinic WHERE statistic_id=?")) {
            sql.setLong(1, ref.path("statisticId").asLong());
            var clinics = new HashSet<Long>();
            try (var rows = sql.executeQuery()) {
                while (rows.next()) clinics.add(rows.getLong(1));
            }
            return clinics.equals(command.clinics());
        }
    }

    @Override public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        return true;
    }
}
