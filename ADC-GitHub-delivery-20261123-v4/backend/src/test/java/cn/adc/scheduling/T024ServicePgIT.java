package cn.adc.scheduling;

import cn.adc.shared.audit.CommandRecovery.Receipt;
import cn.adc.shared.config.ClinicDirectoryProvider.Clinic;
import cn.adc.shared.config.ConfigurationUnavailable;
import cn.adc.shared.config.DentistEligibilityProvider.Eligibility;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;

/** Real JDBC under the isolated runtime login. No HTTP server or application context. */
class T024ServicePgIT {
    private static final String DB = "adc_p0_uc21_review";
    private static final String ROLE = "p0_uc21_tx";
    private static final String ACTOR = "SYNTHETIC-T024-manager";
    private static final String VERSIONS = "SELECT row_to_json(v)::text FROM adc.reception_schedule_version v ";
    private static final String EVENTS = "SELECT row_to_json(e)::text FROM adc.operation_audit_event e ";
    private IsolatedDataSource data;
    private ScheduleDraftService service;
    private JsonNode effectiveBefore;

    @BeforeEach void setUp() throws Exception {
        data = new IsolatedDataSource();
        service = service();
        effectiveBefore = effective();
        assertEquals(2, effectiveBefore.size());
    }

    @Test void c05HistoricalSuccessRemainsVerifiableAfterCurrentVersionChanges() throws Exception {
        var base = service.version(9000, 1);
        var key = UUID.randomUUID();
        var input = revision(base, "16:10", "16:55");
        var original = success(service.revise(key, 9000, input));
        var saved = event(original);
        var successor = success(service.revise(UUID.randomUUID(), 9000,
                revision(service.version(9000, version(original)), "16:15", "16:55")));
        assertTrue(version(successor) > version(original));
        var currentVersions = versions(9000);
        assertEquals(version(successor), currentVersions.get(currentVersions.size() - 1).path("schedule_version_no").asInt());
        assertNotEquals(base.path("stateToken"), service.version(9000, 1).path("stateToken"));
        var recovered = same(original, service().revise(key, 9000, input));
        assertEquals(saved, event(recovered));
        assertEquals(currentVersions, versions(9000));
        evidence("C05", JSON.createObjectNode().set("historicalSuccess", saved)
                .set("currentVersions", currentVersions).set("recovery", receipt(recovered)));
    }

    @Test void c06RevisionLinksExactIssueAndLaterRevisionPreservesOriginalFacts() throws Exception {
        var draft = success(service.create(UUID.randomUUID(), proposal("05:00", "06:00")));
        long id = schedule(draft);
        var base = service.version(id, version(draft));
        var issueInput = JSON.createObjectNode().put("failureReason", "SYNTHETIC-incomplete");
        issueInput.putObject("submittedContext").put("clinicId", "1").put("dentistId", "1").set("basisRef", base.path("ref"));
        var issueKey = UUID.randomUUID();
        var issue = success(service.recordIssue(issueKey, issueInput));
        long issueId = issue.result().path("issueId").asLong();
        var issueEvent = event(issue);
        var key = UUID.randomUUID();
        var input = revision(base, "05:05", "06:00").put("previousIssueId", Long.toString(issueId));
        var first = success(service.revise(key, id, input));
        var firstEvent = event(first);
        var next = success(service.revise(UUID.randomUUID(), id,
                revision(service.version(id, version(first)), "05:10", "06:00")));
        assertEquals(version(first) + 1, version(next));
        var linked = service.issue(issueId);
        assertEquals(base.path("ref"), linked.path("relatedScheduleRef"));
        assertEquals(first.result().path("scheduleRef"), linked.path("resultingScheduleRef"));
        same(issue, service().recordIssue(issueKey, issueInput));
        same(first, service().revise(key, id, input));
        assertEquals(issueEvent, event(issue));
        assertEquals(firstEvent, event(first));
        assertEquals(3, versions(id).size());
        evidence("C06", JSON.createObjectNode().set("issueSuccess", issueEvent).set("revisionSuccess", firstEvent)
                .set("issueLink", linked).set("versionHistory", versions(id)));
    }

    @Test void d08SameKeyAndPayloadRecoversWithoutNewBusinessWrite() throws Exception {
        var key = UUID.randomUUID();
        var input = proposal("06:00", "07:00");
        var original = success(service.create(key, input));
        var before = businessCounts();
        var recovered = same(original, service().create(key, input.deepCopy()));
        assertEquals(before, businessCounts());
        var history = history(original);
        assertEquals(1, history.valueStream().filter(row -> "SUCCESS".equals(row.path("outcome").asText())).count());
        evidence("D08", JSON.createObjectNode().set("receipt", receipt(recovered)).set("auditHistory", history)
                .set("businessCounts", before));
    }

    @Test void d09DifferentPayloadRejectsSameKeyAndPreservesBothDigests() throws Exception {
        var key = UUID.randomUUID();
        var input = proposal("07:00", "08:00");
        var original = success(service.create(key, input));
        var before = businessCounts();
        var event = event(original);
        var rejected = service().create(key, input.deepCopy().put("startsAt", time("07:15")));
        assertEquals("REJECTED", rejected.businessOutcome(), rejected::toString);
        assertEquals(original.requestReference(), rejected.requestReference());
        assertNull(rejected.successEventId());
        assertNull(rejected.result());
        assertEquals(before, businessCounts());
        assertEquals(event, event(original));
        var events = history(original);
        var observation = parseEvidence(events.get(events.size() - 1));
        var mismatch = JSON.readTree(observation.path("observationReason").asText());
        assertEquals("SAME_REQUEST_DIFFERENT_PAYLOAD", mismatch.path("reason").asText());
        assertEquals(parseEvidence(event).path("canonicalPayloadHash"), mismatch.path("originalPayloadHash"));
        assertTrue(mismatch.path("attemptedPayloadHash").asText().matches("[a-f0-9]{64}"));
        assertNotEquals(mismatch.path("originalPayloadHash"), mismatch.path("attemptedPayloadHash"));
        same(original, service().create(key, input));
        assertEquals(before, businessCounts());
        evidence("D09", JSON.createObjectNode().set("rejection", receipt(rejected)).set("mismatch", mismatch)
                .set("auditHistory", history(original)).set("businessCounts", before));
    }

    private ScheduleDraftService service() {
        var clock = Clock.systemUTC();
        var stamp = new SourceStamp("SYNTHETIC-T024", "1", ACTOR, clock.instant(), true);
        var grant = new StaffAuthorization.Grant("1", Set.of("CLINIC_MANAGER"), Set.of("UC21"), Set.of(1L), false);
        return new ScheduleDraftService(new JdbcTransactions(data), new StaffAuthorization(() -> Optional.of(ACTOR),
                () -> Map.of(ACTOR, grant)), id -> id == 1 ? new Clinic(id, "SYNTHETIC-A", ZoneId.of("Asia/Shanghai"), stamp) : null,
                (dentist, clinic) -> {
                    if (dentist != 1 || clinic != 1) throw new ConfigurationUnavailable();
                    return new Eligibility(dentist, clinic, "SYNTHETIC-qualification", stamp);
                }, (clinic, dentist, window, submitted, conflicts) -> Optional.empty(), clock, T024ServicePgIT::auditId);
    }

    // S14 test-only marker allocation, reused for real service documents inside this fresh cluster.
    private static long auditId(Connection connection) throws SQLException {
        try (var s = connection.createStatement()) {
            s.execute("SELECT pg_advisory_xact_lock(41003,54)");
            try (var rows = s.executeQuery("SELECT COALESCE(max(audit_event_id),0)+1 FROM adc.operation_audit_event")) {
                if (!rows.next()) throw new SQLException("TEST_AUDIT_ID_UNAVAILABLE");
                return rows.getLong(1);
            }
        }
    }

    private static Receipt success(Receipt receipt) {
        assertEquals("SUCCEEDED", receipt.businessOutcome(), receipt::toString);
        assertNotNull(receipt.successEventId());
        return receipt;
    }

    private static Receipt same(Receipt expected, Receipt result) {
        success(result);
        assertTrue(result.existingResult());
        assertEquals(expected.requestReference(), result.requestReference());
        assertEquals(expected.successEventId(), result.successEventId());
        assertEquals(expected.result(), result.result());
        return result;
    }

    private static long schedule(Receipt receipt) { return receipt.result().at("/scheduleRef/scheduleId").asLong(); }
    private static int version(Receipt receipt) { return receipt.result().at("/scheduleRef/versionNo").asInt(); }
    private static String time(String local) { return "2026-11-08T"+local+":00+08:00"; }
    private static ObjectNode proposal(String from, String to) {
        return JSON.createObjectNode().put("clinicId", "1").put("dentistId", "1")
                .put("startsAt", time(from)).put("endsAt", time(to));
    }

    private static ObjectNode revision(JsonNode base, String from, String to) {
        return proposal(from, to).put("changeBasis", "SYNTHETIC-revision")
                .put("expectedStateToken", base.path("stateToken").asText()).set("basisRef", base.path("ref"));
    }

    private JsonNode effective() throws Exception { return rows(VERSIONS+"WHERE activation_status='EFFECTIVE' ORDER BY schedule_id"); }
    private JsonNode versions(long id) throws Exception { return rows(VERSIONS+"WHERE schedule_id=? ORDER BY schedule_version_no", id); }
    private JsonNode event(Receipt receipt) throws Exception {
        var rows = rows(EVENTS+"WHERE audit_event_id=?", Long.valueOf(receipt.successEventId()));
        assertEquals(1, rows.size());
        assertEquals("SUCCESS", rows.get(0).path("outcome").asText());
        return rows.get(0);
    }
    private JsonNode history(Receipt receipt) throws Exception {
        return rows(EVENTS+"WHERE request_reference=? ORDER BY event_sequence_no", receipt.requestReference());
    }
    private JsonNode businessCounts() throws Exception {
        return rows("SELECT json_build_array((SELECT count(*) FROM adc.reception_schedule_version),"
                + "(SELECT count(*) FROM adc.schedule_maintenance_issue))::text");
    }
    private static JsonNode parseEvidence(JsonNode row) { return JSON.readTree(row.path("evidence").asText()); }
    private static JsonNode receipt(Receipt receipt) {
        return JSON.createObjectNode().put("requestReference", receipt.requestReference())
                .put("businessOutcome", receipt.businessOutcome()).put("successEventId", receipt.successEventId())
                .put("existingResult", receipt.existingResult()).set("result", receipt.result());
    }
    private JsonNode rows(String sql, Object... params) throws Exception {
        var out = JSON.createArrayNode();
        try (var c = data.getConnection(); var p = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) p.setObject(i+1, params[i]);
            try (var r = p.executeQuery()) { while (r.next()) out.add(JSON.readTree(r.getString(1))); }
        }
        return out;
    }
    private void evidence(String id, ObjectNode result) throws Exception {
        var after = effective();
        assertEquals(effectiveBefore, after);
        result.set("effectiveBefore", effectiveBefore).set("effectiveAfter", after);
        Files.writeString(Path.of(System.getenv("ADC_T024_EVIDENCE"), id+".json"), result.toPrettyString());
    }

    private static final class IsolatedDataSource extends PGSimpleDataSource {
        private final int port = Integer.parseInt(System.getenv("ADC_T024_PORT"));
        private final String startedAt = System.getenv("ADC_T024_POSTMASTER_START");
        IsolatedDataSource() {
            if (port < 1024 || port == 5432 || port > 65535 || startedAt == null
                    || System.getenv("ADC_T024_PASSWORD") == null) throw new IllegalStateException("Isolated run required");
            setServerNames(new String[]{"127.0.0.1"});
            setPortNumbers(new int[]{port});
            setDatabaseName(DB);
            setUser(ROLE);
            setPassword(System.getenv("ADC_T024_PASSWORD"));
            setSslMode("disable");
            setConnectTimeout(3);
            setSocketTimeout(10);
            setOptions("-c timezone=UTC");
        }

        @Override public Connection getConnection() throws SQLException {
            var c = super.getConnection();
            try (var s = c.createStatement(); var row = s.executeQuery("""
                SELECT current_database(),session_user,current_user,host(inet_server_addr()),inet_server_port(),
                       current_setting('server_version_num'),extract(epoch FROM pg_postmaster_start_time())::text
                """)) {
                if (!row.next() || !DB.equals(row.getString(1)) || !ROLE.equals(row.getString(2))
                        || !ROLE.equals(row.getString(3)) || !"127.0.0.1".equals(row.getString(4))
                        || port != row.getInt(5) || !"180006".equals(row.getString(6))
                        || !startedAt.equals(row.getString(7))) throw new SQLException("T024_ISOLATED_TARGET_MISMATCH", "08001");
                return c;
            } catch (SQLException e) { c.close(); throw e; }
        }
    }
}
