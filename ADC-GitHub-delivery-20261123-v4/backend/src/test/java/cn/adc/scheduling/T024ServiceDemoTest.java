package cn.adc.scheduling;

import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.config.DentistEligibilityProvider;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controller, real service and real command journal with in-memory storage and mock JDBC. */
class T024ServiceDemoTest {
    private static final Path DATA = Path.of("target", "t024-demo", "dataset.json");
    private static final String ROOT = "/api/v1/staff";
    private static final String CSRF = "SYNTHETIC-T024";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T08:00:00Z"), ZoneOffset.UTC);
    private static final SourceStamp STAMP = new SourceStamp("SYNTHETIC", "1", "fixture", CLOCK.instant(), true);
    private final T024MemoryJournal journal = new T024MemoryJournal();
    private final ScheduleStore store = mock(ScheduleStore.class);
    private final List<ScheduleStateToken.Version> versions = new ArrayList<>();
    private final Map<Long, ScheduleStore.Issue> issues = new HashMap<>();
    private final List<JsonNode> appointments = new ArrayList<>();
    private final AtomicLong scheduleIds = new AtomicLong(91000);
    private final AtomicLong issueIds = new AtomicLong(92000);
    private final AtomicLong auditIds = new AtomicLong(93000);
    private List<ScheduleStateToken.Version> initialEffective;
    private ScheduleStateToken.Version base;
    private ZoneId zone;
    private long otherClinic;
    private MockMvc mvc;

    @BeforeEach void setUp() throws Exception {
        var dataset = JSON.readTree(Files.readString(DATA));
        assertEquals("SYNTHETIC_TEST_ONLY", dataset.path("scope").asText());
        var clinic = dataset.path("clinics").get(0);
        var dentist = dataset.path("dentists").get(0);
        var manager = dataset.path("staffBindings").valueStream().filter(n -> "UC21".equals(n.path("action").asText()))
                .findFirst().orElseThrow();
        long clinicId = number(clinic, "id");
        long dentistId = number(dentist, "id");
        otherClinic = number(dataset.path("clinics").get(1), "id");
        zone = ZoneId.of(clinic.path("timeZone").asText());
        var qualification = dentist.path("qualificationReference").asText();
        dataset.path("appointments").forEach(appointments::add);
        for (var row : dataset.path("scheduleVersions")) {
            var state = row.path("status").asText();
            versions.add(new ScheduleStateToken.Version(number(row, "scheduleId"), row.path("versionNo").asInt(),
                    number(row, "clinicId"), number(row, "dentistId"), instant(row, "startsAt"), instant(row, "endsAt"),
                    state, null, qualification, "CLEAR", null, "EFFECTIVE".equals(state) ? CLOCK.instant() : null));
        }
        initialEffective = effective();
        base = initialEffective.stream().filter(v -> v.clinicId() == clinicId).findFirst().orElseThrow();
        var actor = manager.path("subject").asText();
        var session = new StaffSessionIdentityAdapter(() -> new StaffSessionIdentityAdapter.VerifiedSession(actor, CSRF, STAMP));
        var grant = new StaffAuthorization.Grant("1", Set.of(manager.path("role").asText()), Set.of("UC21"),
                Set.of(number(manager.path("clinicIds"), 0)), false);
        var auth = new StaffAuthorization(session, () -> Map.of(actor, grant));
        var tx = new JdbcTransactions(journal.source());
        var service = new ScheduleDraftService(tx, auth,
                id -> id == clinicId ? new ClinicDirectoryProvider.Clinic(id, clinic.path("name").asText(), zone, STAMP) : null,
                (id, place) -> new DentistEligibilityProvider.Eligibility(id, place, qualification, STAMP),
                (place, id, window, submitted, conflicts) -> Optional.empty(), CLOCK, store,
                verifier -> new UcAuditService(tx, auth, verifier, CLOCK, c -> auditIds.incrementAndGet())
                        .commands()::executeUc21);
        mvc = MockMvcBuilders.standaloneSetup(new ScheduleController(new ScheduleAccess() {
            @Override public StaffSessionIdentityAdapter identity(HttpServletRequest request) { return session; }
            @Override public ScheduleOperations service(HttpServletRequest request) { return service; }
        })).build();
        stubStore();
    }

    @Test void demonstratedFlowPreservesEffectiveRowsAndReplaysCommittedImpact() throws Exception {
        var transcript = JSON.createObjectNode();
        var create = proposal(base.startsAt().minusSeconds(1800), base.startsAt());
        var created = capture(transcript, "create", send("schedule-drafts", "create", create, 200));
        assertEquals("DRAFT", created.path("businessOutcome").asText());
        assertEquals(1, created.path("links").path("scheduleRef").path("versionNo").asInt());
        assertTrue(created.at("/appointmentImpact/appointmentIds").isEmpty());

        var detail = capture(transcript, "version", getVersion());
        var revise = proposal(base.startsAt(), base.startsAt().plusSeconds(600));
        revise.set("basisRef", detail.path("ref"));
        revise.put("expectedStateToken", detail.path("stateToken").asText()).put("changeBasis", "SYNTHETIC-change");
        var check = checkBody(revise);
        var preview = body(send("schedule-checks", "preview", check, 200));
        assertTrue(preview.path("conflicts").toString().contains("预约 901"));
        assertFalse(preview.path("conflicts").toString().contains("预约 902"));
        var extra = appointments.getFirst().deepCopy().asObject().put("id", "902");
        extra.put("startsAt", appointments.getFirst().path("endsAt").asText());
        extra.put("endsAt", instant(extra, "startsAt").plusSeconds(27 * 60).toString());
        appointments.add(extra);

        journal.loseNextSuccessReply = true;
        var revised = capture(transcript, "revise", send(revisionPath(), "revise", revise, 200));
        assertEquals(base.versionNo() + 1, revised.at("/links/scheduleRef/versionNo").asInt());
        assertTrue(revised.path("existingResult").asBoolean());
        assertEquals(List.of("901", "902"), ids(revised));
        appointments.removeLast();
        var recovered = body(send(revisionPath(), "revise", revise, 200));
        assertEquals(ids(revised), ids(recovered));
        assertEquals(revised.at("/links/originalUcSuccessEventId"), recovered.at("/links/originalUcSuccessEventId"));
        var stale = capture(transcript, "stale", send(revisionPath(), "new-key-old-token", revise, 409));
        assertEquals("STALE_VERSION", stale.path("code").asText());

        var issueRequest = JSON.createObjectNode().put("failureReason", "SYNTHETIC-incomplete");
        issueRequest.set("submittedContext", check);
        var issue = capture(transcript, "issue", send("schedule-maintenance-issues", "issue", issueRequest, 200));
        assertEquals("PERSISTED_INCOMPLETE", issue.path("status").asText());
        assertTrue(issue.at("/links/scheduleRef").isMissingNode());
        assertTrue(issue.path("appointmentImpact").isMissingNode());
        assertEquals(1, issues.size());
        assertEquals(new ScheduleStore.Ref(base.scheduleId(), base.versionNo()), issues.values().iterator().next().basis());

        assertEquals(initialEffective, effective());
        assertEquals(3, journal.successes());
        mvc.perform(post(ROOT + "/schedules/" + base.scheduleId() + "/versions/3/activations"))
                .andExpect(status().isNotFound());
        transcript.put("scope", "SYNTHETIC_TEST_ONLY");
        transcript.set("createInput", create);
        transcript.set("reviseInput", revise);
        transcript.set("issueInput", issueRequest);
        Files.writeString(DATA.resolveSibling("service-transcript.json"), transcript.toPrettyString());
    }

    @Test void authorizationAndAuditFailureCannotMutateBusinessState() throws Exception {
        var draft = proposal(base.startsAt().minusSeconds(1800), base.startsAt());
        int before = versions.size();
        var forbidden = draft.deepCopy().put("clinicId", Long.toString(otherClinic));
        send("schedule-drafts", "other-clinic", forbidden, 403);
        journal.failNextSuccessAudit = true;
        var failed = body(send("schedule-drafts", "fail-audit", draft, 503));
        assertEquals("OPERATION_FAILED", failed.path("code").asText());
        assertTrue(failed.path("links").isMissingNode());
        assertEquals(before, versions.size());
        assertEquals(0, journal.successes());
        var recovered = body(send("schedule-drafts", "fail-audit", draft, 503));
        assertEquals(failed.path("requestReference"), recovered.path("requestReference"));
        assertEquals(before, versions.size());
        assertEquals(initialEffective, effective());
    }

    @Test void lostStartStaysUnknownWithoutReplayingEitherWriteKind() throws Exception {
        int before = versions.size();
        var draft = proposal(base.startsAt().minusSeconds(1800), base.startsAt());
        journal.loseNextStartReply = true;
        var unknown = body(send("schedule-drafts", "unknown-draft", draft, 202));
        var again = body(send("schedule-drafts", "unknown-draft", draft, 202));
        assertEquals("UNKNOWN", unknown.path("status").asText());
        assertEquals(unknown.path("requestReference"), again.path("requestReference"));
        assertTrue(again.path("links").isMissingNode());

        var issue = JSON.createObjectNode().put("failureReason", "SYNTHETIC-unverified");
        issue.set("submittedContext", checkBody(draft));
        journal.loseNextStartReply = true;
        var unknownIssue = body(send("schedule-maintenance-issues", "unknown-issue", issue, 503));
        assertEquals("COMMIT_OUTCOME_UNKNOWN", unknownIssue.path("code").asText());
        assertEquals(unknownIssue.path("requestReference"),
                body(send("schedule-maintenance-issues", "unknown-issue", issue, 503)).path("requestReference"));
        assertEquals(before, versions.size());
        assertTrue(issues.isEmpty());
        assertEquals(0, journal.successes());
        assertEquals(initialEffective, effective());
    }

    private void stubStore() throws Exception {
        when(store.versions(any(), anyLong())).thenAnswer(call -> versions.stream()
                .filter(v -> v.scheduleId() == call.<Long>getArgument(1)).toList());
        when(store.overlaps(any(), anyLong(), any())).thenAnswer(call -> {
            ScheduleStore.Window w = call.getArgument(2);
            return versions.stream().filter(v -> v.dentistId() == call.<Long>getArgument(1)
                    && v.startsAt().isBefore(w.end()) && w.start().isBefore(v.endsAt())).toList();
        });
        when(store.nextId(any(), anyBoolean())).thenAnswer(call ->
                (call.<Boolean>getArgument(1) ? issueIds : scheduleIds).incrementAndGet());
        doAnswer(call -> {
            ScheduleStateToken.Version draft = call.getArgument(1);
            assertEquals("DRAFT", draft.status());
            assertNull(draft.activatedAt());
            journal.stage(call.getArgument(0), () -> versions.add(draft));
            return null;
        }).when(store).insertDraft(any(), any());
        doAnswer(call -> {
            ScheduleStore.Issue issue = call.getArgument(1);
            journal.stage(call.getArgument(0), () -> issues.put(issue.id(), issue));
            return null;
        }).when(store).insertIssue(any(), any());
        when(store.affectedAppointments(any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyBoolean()))
                .thenAnswer(call -> {
                    ScheduleStore.Window window = call.getArgument(5);
                    return appointments.stream().filter(a -> number(a, "clinicId") == call.<Long>getArgument(1)
                            && number(a.path("effectiveScheduleRef"), "scheduleId") == call.<Long>getArgument(3)
                            && (number(a, "clinicId") != call.<Long>getArgument(2)
                            || number(a, "dentistId") != call.<Long>getArgument(4)
                            || instant(a, "startsAt").isBefore(window.start()) || instant(a, "endsAt").isAfter(window.end())))
                            .map(a -> a.path("id").asText()).toList();
                });
    }

    private ObjectNode proposal(Instant start, Instant end) {
        return JSON.createObjectNode().put("clinicId", Long.toString(base.clinicId()))
                .put("dentistId", Long.toString(base.dentistId()))
                .put("startsAt", start.atZone(zone).toOffsetDateTime().toString())
                .put("endsAt", end.atZone(zone).toOffsetDateTime().toString());
    }

    private static ObjectNode checkBody(JsonNode proposal) {
        var out = JSON.createObjectNode().put("clinicId", proposal.path("clinicId").asText())
                .put("dentistId", proposal.path("dentistId").asText())
                .put("submittedStartsAt", proposal.path("startsAt").asText())
                .put("submittedEndsAt", proposal.path("endsAt").asText());
        if (proposal.has("basisRef")) out.set("basisRef", proposal.path("basisRef"));
        return out;
    }

    private MvcResult getVersion() throws Exception {
        return mvc.perform(get(ROOT + "/schedules/" + base.scheduleId() + "/versions/" + base.versionNo()))
                .andExpect(status().isOk()).andReturn();
    }

    private String revisionPath() { return "schedules/" + base.scheduleId() + "/draft-revisions"; }

    private MvcResult send(String path, String key, JsonNode input, int expected) throws Exception {
        return mvc.perform(post(ROOT + "/" + path).header("Idempotency-Key", "SYNTHETIC-" + key)
                        .header("X-CSRF-Token", CSRF).contentType(MediaType.APPLICATION_JSON).content(input.toString()))
                .andExpect(status().is(expected)).andReturn();
    }

    private static JsonNode capture(ObjectNode transcript, String key, MvcResult response) throws Exception {
        var body = body(response);
        transcript.putObject(key).put("status", response.getResponse().getStatus()).set("body", body);
        return body;
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    private List<ScheduleStateToken.Version> effective() {
        return versions.stream().filter(v -> "EFFECTIVE".equals(v.status())).toList();
    }

    private static List<String> ids(JsonNode result) {
        return result.at("/appointmentImpact/appointmentIds").valueStream().map(JsonNode::asText).toList();
    }

    private static long number(JsonNode row, String field) { return Long.parseLong(row.path(field).asText()); }
    private static long number(JsonNode row, int index) { return Long.parseLong(row.path(index).asText()); }
    private static Instant instant(JsonNode row, String field) { return Instant.parse(row.path(field).asText()); }
}
