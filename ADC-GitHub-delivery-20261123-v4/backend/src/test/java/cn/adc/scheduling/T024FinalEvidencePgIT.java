package cn.adc.scheduling;

import cn.adc.shared.config.ClinicDirectoryProvider.Clinic;
import cn.adc.shared.config.DentistEligibilityProvider.Eligibility;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Actual controller, service, JDBC and isolated runtime ACL. No HTTP listener or enabled application profile. */
class T024FinalEvidencePgIT {
    private static final String ROOT = "/api/v1/staff";
    private static final String ACTOR = "SYNTHETIC-T024-final";
    private static final String CSRF = "SYNTHETIC-CSRF";
    private static final String VERSIONS = "SELECT row_to_json(v)::text FROM adc.reception_schedule_version v ";
    private static final String ISSUES = "SELECT row_to_json(i)::text FROM adc.schedule_maintenance_issue i ORDER BY maintenance_issue_id";
    private final IsolatedData data = new IsolatedData();

    @Test void readsAuthorizedListAndExistingIssueThroughController() throws Exception {
        var owner = controller(Set.of(1L), true, null);
        var other = controller(Set.of(2L), true, null);
        var anonymous = controller(Set.of(), false, null);
        var source = rows(ISSUES).get(0);
        var read = get(owner, "/schedules", 200);
        var listed = read.at("/body/items");
        assertTrue(listed.valueStream().anyMatch(n -> n.at("/ref/scheduleId").asText().equals("9000")));
        assertTrue(listed.valueStream().allMatch(n -> n.path("clinicId").asText().equals("1")));
        assertEquals("9001", get(other, "/schedules?clinicId=2", 200).at("/body/items/0/ref/scheduleId").asText());
        var forbidden = get(owner, "/schedules?clinicId=2", 403);
        var anonymousList = get(anonymous, "/schedules", 401);
        var issue = get(owner, "/schedule-maintenance-issues/501", 200);
        assertEquals("501", issue.at("/body/issueId").asText());
        assertEquals(source.path("failure_reason"), issue.at("/body/failureReason"));
        var deniedIssue = get(other, "/schedule-maintenance-issues/501", 404);
        var missingIssue = get(owner, "/schedule-maintenance-issues/999999", 404);
        var anonymousIssue = get(anonymous, "/schedule-maintenance-issues/501", 401);
        assertEquals(missingIssue, deniedIssue);
        for (var denial : new JsonNode[]{forbidden, anonymousList, deniedIssue, anonymousIssue}) {
            assertFalse(denial.path("body").has("failureReason"));
            assertFalse(denial.path("body").has("items"));
        }
        save("reads", JSON.createObjectNode().put("scope", "SYNTHETIC_TEST_ONLY").set("sourceIssue", source)
                .set("list", read).set("forbiddenList", forbidden).set("anonymousList", anonymousList)
                .set("issue", issue).set("missingIssue", missingIssue).set("deniedIssue", deniedIssue)
                .set("anonymousIssue", anonymousIssue));
    }

    @Test void crossClinicFirstDraftsWaitForDentistAndRereadCommittedDraft() throws Exception {
        var before = effective();
        var issues = rows(ISSUES);
        var hold = new Hold();
        var first = controller(Set.of(1L), true, hold);
        var second = controller(Set.of(2L), true, null);
        var result = race(hold, "lock_dentist", () -> post(first, "/schedule-drafts", "SYNTHETIC-L4-A", proposal(1,"05:00","06:00")),
                () -> post(second, "/schedule-drafts", "SYNTHETIC-L4-B", proposal(2,"05:15","06:15")));
        assertEquals(200, status(result.first()), result::toString);
        assertEquals(200, status(result.second()), result::toString);
        var one = drafts(result.first()).get(0);
        var two = drafts(result.second()).get(0);
        assertEquals("CLEAR", one.path("conflict_check_result").asText());
        assertEquals("OVERLAP", two.path("conflict_check_result").asText());
        assertEquals(1, one.path("clinic_id").asLong());
        assertEquals(2, two.path("clinic_id").asLong());
        assertNotEquals(one.path("schedule_id"), two.path("schedule_id"));
        terminal(result.first(), "SUCCESS");
        terminal(result.second(), "SUCCESS");
        archive("race004-L4", result, before, issues, JSON.createArrayNode().add(one).add(two));
    }

    @Test void crossClinicFirstDraftsWithSameKeyRejectDifferentPayload() throws Exception {
        var before = effective();
        var issues = rows(ISSUES);
        var scheduleCount = rows("SELECT to_json(count(*))::text FROM adc.reception_schedule_version").get(0).asLong();
        var hold = new Hold();
        var first = controller(Set.of(1L,2L), true, hold);
        var second = controller(Set.of(1L,2L), true, null);
        var result = race(hold, "pg_advisory_xact_lock(41001", () -> post(first, "/schedule-drafts", "SYNTHETIC-L1", proposal(1,"03:00","04:00")),
                () -> post(second, "/schedule-drafts", "SYNTHETIC-L1", proposal(2,"03:15","04:15")));
        assertEquals(200, status(result.first()), result::toString);
        assertEquals(409, status(result.second()), result::toString);
        assertEquals("REQUEST_CONFLICT", result.second().at("/body/code").asText());
        assertEquals(result.first().at("/body/requestReference"), result.second().at("/body/requestReference"));
        var persisted = drafts(result.first());
        assertEquals(1, persisted.size());
        assertEquals(1, persisted.get(0).path("clinic_id").asLong());
        assertEquals(scheduleCount+1,rows("SELECT to_json(count(*))::text FROM adc.reception_schedule_version").get(0).asLong());
        var audit = terminal(result.first(), "SUCCESS");
        assertTrue(audit.valueStream().anyMatch(row -> row.path("reason").asText().equals("SAME_REQUEST_DIFFERENT_PAYLOAD")));
        archive("race004-L1", result, before, issues, persisted);
    }

    @Test void twoRevisionsWithSameOldTokenCommitOnceAndReturnStale409() throws Exception {
        var before = effective();
        var owner = controller(Set.of(1L), true, null);
        var base = get(owner, "/schedules/9000/versions/1", 200).path("body");
        var issueInput = JSON.createObjectNode().put("failureReason","SYNTHETIC-old-basis");
        issueInput.putObject("submittedContext").put("clinicId","1").put("dentistId","1").set("basisRef",base.path("ref"));
        var issue = post(owner, "/schedule-maintenance-issues", "SYNTHETIC-005-ISSUE", issueInput);
        assertEquals(200, status(issue));
        var issueId = issue.at("/body/links/issueId").asText();
        var issues = rows(ISSUES);
        var firstInput = proposal(1,"16:10","16:50").put("changeBasis","SYNTHETIC-A")
                .put("expectedStateToken",base.path("stateToken").asText()).put("previousIssueId",issueId).set("basisRef",base.path("ref"));
        var secondInput = firstInput.deepCopy().put("changeBasis","SYNTHETIC-B").put("endsAt",time("16:55"));
        var hold = new Hold();
        var first = controller(Set.of(1L), true, hold);
        var result = race(hold, "pg_advisory_xact_lock($1)", () -> post(first, "/schedules/9000/draft-revisions", "SYNTHETIC-005-A", firstInput),
                () -> post(owner, "/schedules/9000/draft-revisions", "SYNTHETIC-005-B", secondInput));
        assertEquals(200, status(result.first()), result::toString);
        assertEquals(409, status(result.second()), result::toString);
        assertEquals("STALE_VERSION", result.second().at("/body/code").asText());
        assertNotEquals(base.path("stateToken"), get(owner, "/schedules/9000/versions/1", 200).at("/body/stateToken"));
        var versions = rows(VERSIONS+"WHERE schedule_id=9000 ORDER BY schedule_version_no");
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(1).path("schedule_version_no").asInt());
        assertEquals("DRAFT", versions.get(1).path("activation_status").asText());
        var linked = get(owner, "/schedule-maintenance-issues/"+issueId, 200);
        assertEquals(base.path("ref"), linked.at("/body/relatedScheduleRef"));
        assertEquals(result.first().at("/body/links/scheduleRef"), linked.at("/body/resultingScheduleRef"));
        var expectedIssues = issues.deepCopy();
        var linkedRow = (ObjectNode) expectedIssues.get(expectedIssues.size()-1);
        assertEquals(issueId, linkedRow.path("maintenance_issue_id").asText());
        linkedRow.put("resulting_schedule_id",9000).put("resulting_schedule_version_no",2);
        terminal(issue, "SUCCESS");
        terminal(result.first(), "SUCCESS");
        terminal(result.second(), "REJECTED");
        archive("race005", result, before, expectedIssues, versions);
    }

    private void archive(String id, Race result, JsonNode before, JsonNode expectedIssues, JsonNode versions) throws Exception {
        var observed = effective();
        var issues = rows(ISSUES);
        assertEquals(before, observed);
        assertEquals(expectedIssues, issues);
        save(id, JSON.createObjectNode().set("wait",result.waitEvidence()).set("first",result.first())
                .set("second",result.second()).set("firstAudit",audit(result.first())).set("secondAudit",audit(result.second()))
                .set("effectiveBefore",before).set("effectiveAfter",observed).set("versions",versions).set("issues",issues));
    }

    private Race race(Hold hold, String expectedQuery, Callable<JsonNode> a, Callable<JsonNode> b) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(tagged("T024-first",a));
            try {
                assertTrue(hold.entered.await(5,TimeUnit.SECONDS),"First request never reached post-lock eligibility");
                var second = pool.submit(tagged("T024-second",b));
                var evidence = awaitBlock(expectedQuery);
                hold.release.countDown();
                return new Race(first.get(8,TimeUnit.SECONDS),second.get(8,TimeUnit.SECONDS),evidence);
            } finally { hold.release.countDown(); }
        }
    }

    private JsonNode awaitBlock(String expectedQuery) throws Exception {
        var deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do {
            var observed = rows("""
                SELECT json_build_object('waitingPid',b.pid,'blockingPid',a.pid,'query',b.query,'waitEvent',b.wait_event,
                                         'waitingApplication',b.application_name,'blockingApplication',a.application_name)::text
                FROM pg_stat_activity b JOIN pg_stat_activity a ON a.pid=ANY(pg_blocking_pids(b.pid))
                WHERE b.application_name='T024-second' AND a.application_name='T024-first'
                """);
            if (!observed.isEmpty()) {
                var proof = observed.get(0);
                assertNotEquals(proof.path("waitingPid"),proof.path("blockingPid"));
                assertTrue(proof.path("query").asText().contains(expectedQuery),proof::toString);
                return proof;
            }
            Thread.sleep(25);
        } while (System.nanoTime()<deadline);
        throw new AssertionError("No real PostgreSQL blocker observed for "+expectedQuery);
    }

    private Callable<JsonNode> tagged(String name, Callable<JsonNode> work) {
        return () -> {
            data.tag.set(name);
            try { return work.call(); } finally { data.tag.remove(); }
        };
    }

    private record Race(JsonNode first, JsonNode second, JsonNode waitEvidence) {}
    private static final class Hold {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        void pause() {
            entered.countDown();
            try { assertTrue(release.await(8,TimeUnit.SECONDS),"Test barrier expired"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }

    private MockMvc controller(Set<Long> clinics, boolean signedIn, Hold hold) {
        var clock = Clock.systemUTC();
        var stamp = new SourceStamp("SYNTHETIC-T024","1",ACTOR,clock.instant(),true);
        var identity = new StaffSessionIdentityAdapter(() -> signedIn
                ? new StaffSessionIdentityAdapter.VerifiedSession(ACTOR,CSRF,stamp) : null);
        var grant = new StaffAuthorization.Grant("1",Set.of("CLINIC_MANAGER"),Set.of("UC21"),clinics,false);
        var auth = new StaffAuthorization(identity, () -> Map.of(ACTOR,grant));
        var service = new ScheduleDraftService(new JdbcTransactions(data),auth,
                id -> new Clinic(id,"SYNTHETIC-"+id,ZoneId.of("Asia/Shanghai"),stamp),
                (dentist,clinic) -> {
                    assertEquals(1,dentist);
                    assertTrue(clinics.contains(clinic));
                    if (hold!=null) hold.pause();
                    return new Eligibility(dentist,clinic,"SYNTHETIC-qualification",stamp);
                }, (clinic,dentist,window,submitted,conflicts) -> Optional.empty(),clock,T024FinalEvidencePgIT::fixtureAuditId);
        return MockMvcBuilders.standaloneSetup(new ScheduleController(new ScheduleAccess() {
            public StaffSessionIdentityAdapter identity(HttpServletRequest request) { return identity; }
            public ScheduleOperations service(HttpServletRequest request) { return service; }
        })).build();
    }

    // Previously approved S14 fixture allocator. The deployment allocator is outside this test.
    private static long fixtureAuditId(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("SELECT pg_advisory_xact_lock(41003,54)");
            try (var r = s.executeQuery("SELECT COALESCE(max(audit_event_id),0)+1 FROM adc.operation_audit_event")) {
                if (!r.next()) throw new SQLException("TEST_AUDIT_ID_UNAVAILABLE");
                return r.getLong(1);
            }
        }
    }

    private JsonNode terminal(JsonNode receipt, String outcome) throws Exception {
        var journal = audit(receipt);
        var terminal = journal.valueStream().filter(row -> Set.of("SUCCESS","REJECTED","FAILED").contains(row.path("outcome").asText())).toList();
        assertEquals(1,terminal.size(),journal::toString);
        assertEquals(outcome,terminal.get(0).path("outcome").asText(),journal::toString);
        return journal;
    }
    private JsonNode audit(JsonNode receipt) throws Exception {
        return rows("SELECT row_to_json(e)::text FROM adc.operation_audit_event e WHERE request_reference=? ORDER BY event_sequence_no",
                receipt.at("/body/requestReference").asText());
    }
    private JsonNode effective() throws Exception {
        var current = rows(VERSIONS+"WHERE activation_status='EFFECTIVE' ORDER BY schedule_id");
        assertEquals(2,current.size());
        return current;
    }
    private JsonNode drafts(JsonNode receipt) throws Exception {
        return rows(VERSIONS+"WHERE schedule_id=? AND schedule_version_no=? AND activation_status='DRAFT'",
                receipt.at("/body/links/scheduleRef/scheduleId").asLong(),receipt.at("/body/links/scheduleRef/versionNo").asInt());
    }
    private JsonNode rows(String sql, Object... args) throws Exception {
        var result = JSON.createArrayNode();
        try (var c=data.getConnection(); var p=c.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) p.setObject(i+1,args[i]);
            try(var r=p.executeQuery()){while(r.next())result.add(JSON.readTree(r.getString(1)));}
        }
        return result;
    }
    private static String time(String local) { return "2026-11-08T"+local+":00+08:00"; }
    private static ObjectNode proposal(long clinic,String start,String end) {
        return JSON.createObjectNode().put("clinicId",Long.toString(clinic)).put("dentistId","1")
                .put("startsAt",time(start)).put("endsAt",time(end));
    }
    private static JsonNode get(MockMvc mvc,String path,int expected) throws Exception {
        var response=mvc.perform(MockMvcRequestBuilders.get(ROOT+path)).andReturn().getResponse();
        assertEquals(expected,response.getStatus(),() -> new String(response.getContentAsByteArray(),StandardCharsets.UTF_8));
        return JSON.createObjectNode().put("status",response.getStatus()).set("body",JSON.readTree(response.getContentAsByteArray()));
    }
    private static JsonNode post(MockMvc mvc,String path,String key,JsonNode input) throws Exception {
        var response=mvc.perform(MockMvcRequestBuilders.post(ROOT+path)
                .header("Idempotency-Key",key).header("X-CSRF-Token",CSRF)
                .contentType("application/json").content(input.toString())).andReturn().getResponse();
        return JSON.createObjectNode().put("status",response.getStatus()).set("body",JSON.readTree(response.getContentAsByteArray()));
    }
    private static int status(JsonNode response) { return response.path("status").asInt(); }
    private static void save(String name,JsonNode proof) throws Exception {
        Files.writeString(Path.of(System.getenv("ADC_T024_EVIDENCE"),name+".json"),proof.toPrettyString());
    }

    private static final class IsolatedData extends PGSimpleDataSource {
        private final int port = Integer.parseInt(System.getenv("ADC_T024_PORT"));
        private final String startedAt = System.getenv("ADC_T024_POSTMASTER_START");
        private final ThreadLocal<String> tag = new ThreadLocal<>();
        IsolatedData() {
            var secret = System.getenv("ADC_T024_PASSWORD");
            if (port<1024||port==5432||port>65535||startedAt==null||secret==null) throw new IllegalStateException("Isolated run required");
            setServerNames(new String[]{"127.0.0.1"});
            setPortNumbers(new int[]{port});
            setDatabaseName("adc_p0_uc21_review");
            setUser("p0_uc21_tx");
            setPassword(secret);
            setSslMode("disable");
            setConnectTimeout(3);
            setSocketTimeout(12);
        }
        @Override public Connection getConnection() throws SQLException {
            var c=super.getConnection();
            try {
                try (var s=c.createStatement();var r=s.executeQuery("""
                    SELECT current_database(),session_user,current_user,host(inet_server_addr()),inet_server_port(),
                           current_setting('server_version_num'),extract(epoch FROM pg_postmaster_start_time())::text
                    """)) {
                    if(!r.next()||!"adc_p0_uc21_review".equals(r.getString(1))||!"p0_uc21_tx".equals(r.getString(2))
                            ||!"p0_uc21_tx".equals(r.getString(3))||!"127.0.0.1".equals(r.getString(4))||port!=r.getInt(5)
                            ||!"180006".equals(r.getString(6))||!startedAt.equals(r.getString(7)))
                        throw new SQLException("T024_ISOLATED_TARGET_MISMATCH","08001");
                }
                if(tag.get()!=null)c.setClientInfo("ApplicationName",tag.get());
                return c;
            } catch (SQLException e) { c.close(); throw e; }
        }
    }
}
