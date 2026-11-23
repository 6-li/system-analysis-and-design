package cn.adc.scheduling;

import cn.adc.appointments.T028IsolatedPgIT;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

public class T037IsolatedPgIT {
    @Test void activationRollsBackOnAuditFailureAndThenRetainsTheOriginalHistory() throws Exception {
        var db = new T028IsolatedPgIT.IsolatedData("runtime", 0);
        var tx = new JdbcTransactions(db);
        var auth = new StaffAuthorization(() -> Optional.of("SYNTHETIC activation"), () -> Map.of(
                "SYNTHETIC activation", new StaffAuthorization.Grant("SYNTHETIC-v1", Set.of("CLINIC_MANAGER"),
                        Set.of("UC22"), Set.of(81002L), false)));
        var versions = tx.execute(c -> new JdbcScheduleStore().versions(c, 87002));
        var draft = versions.stream().filter(v -> v.versionNo() == 2).findFirst().orElseThrow();
        var previous = versions.stream().filter(v -> v.versionNo() == 1).findFirst().orElseThrow();
        var scan = new ScheduleActivationScan(v -> new ScheduleActivationScan.Eligibility("SYNTHETIC", true));
        var observed = tx.execute(c -> scan.read(c, draft));
        assertEquals(java.util.List.of(902L), observed.appointments().stream()
                .map(ScheduleActivationReview.AppointmentState::id).toList());
        assertFalse(observed.appointments().getFirst().blocksActivation());
        var token = new ScheduleActivationReview().token(ScheduleStateToken.of(draft, versions), "SYNTHETIC",
                observed.appointments());
        var work = new ScheduleActivationService().work(draft, token, auth, scan, Clock.systemUTC());
        var ids = new AtomicLong(81000);
        var verifier = new cn.adc.shared.audit.CommandRecovery.SuccessVerifier() {
            public boolean matchesCommitted(Connection c, cn.adc.shared.audit.CommandRecovery.Command command,
                                           tools.jackson.databind.JsonNode saved) { return false; }
            public boolean hasContradictorySuccess(Connection c, cn.adc.shared.audit.CommandRecovery.Command command,
                                                   String ref) { return true; }
        };
        var failedJournal = new cn.adc.shared.audit.UcAuditService(tx, auth, verifier, Clock.systemUTC(), c -> {
            long id = ids.incrementAndGet();
            if (id == 81002) throw new SQLException("SYNTHETIC audit failure", "23514");
            return id;
        }).commands();
        var payload = AuditDocuments.JSON.createObjectNode().put("checkToken", token)
                .put("scheduleId", "87002").put("versionNo", 2);
        var failed = failedJournal.execute(new cn.adc.shared.audit.CommandRecovery.Command(
                "ACTIVATE", "UC22", UUID.randomUUID(), payload, Set.of(81002L)), work);
        assertEquals("FAILED", failed.businessOutcome());
        assertEquals(versions, tx.execute(c -> new JdbcScheduleStore().versions(c, 87002)));
        var lossy = new JdbcTransactions(new T028IsolatedPgIT.IsolatedData("runtime", 5));
        var stamp = new cn.adc.shared.config.SourceStamp("SYNTHETIC", "test", "SYNTHETIC verifier",
                java.time.Instant.EPOCH, true);
        var bindings = new cn.adc.shared.security.StaffBindingAdapter(() -> java.util.List.of(
                new cn.adc.shared.security.StaffBindingAdapter.Entry("SYNTHETIC activation",
                        new StaffAuthorization.Grant("test", Set.of("CLINIC_MANAGER"), Set.of("UC21", "UC22"),
                                Set.of(81002L), false), stamp)));
        var factory = new ScheduleAccessFactory(lossy, bindings, () -> stamp,
                clinic -> new cn.adc.shared.config.ClinicDirectoryProvider.Clinic(
                        clinic, "SYNTHETIC", java.time.ZoneId.of("Etc/UTC"), stamp),
                (dentist, clinic) -> new cn.adc.shared.config.DentistEligibilityProvider.Eligibility(
                        dentist, clinic, "SYNTHETIC", stamp),
                (clinic, dentist, start, end, submitted, conflicts) -> Optional.empty(),
                Clock.systemUTC(), c -> ids.incrementAndGet());
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(cn.adc.shared.security.StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new cn.adc.shared.security.StaffSessionIdentityAdapter.VerifiedSession(
                        "SYNTHETIC activation", "SYNTHETIC-CSRF", stamp));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new ResolutionController(factory), new ScheduleController(factory)).build();
        ResolutionBrowserBridge.verify(mvc, session, true);
        var committed = tx.execute(c -> new JdbcScheduleStore().versions(c, 87002));
        assertEquals("SUPERSEDED", committed.get(0).status());
        assertEquals(previous.activatedAt(), committed.get(0).activatedAt());
        assertEquals("EFFECTIVE", committed.get(1).status());
        assertNotNull(committed.get(1).activatedAt());
        ResolutionBrowserBridge.verify(mvc, session, "create-activate");
        var newlyCreated = tx.execute(c -> new JdbcScheduleStore().versions(c, 88000));
        assertEquals(1, newlyCreated.size());
        assertEquals("EFFECTIVE", newlyCreated.getFirst().status());
    }

    @Test void lostCommitAcknowledgementRecoversOriginalResultWithoutAppendingAgain() throws Exception {
        var db = new T028IsolatedPgIT.IsolatedData("runtime", 2);
        var auth = new StaffAuthorization(() -> Optional.of("SYNTHETIC recovery"), () -> Map.of(
                "SYNTHETIC recovery", new StaffAuthorization.Grant("SYNTHETIC-v1", Set.of("CLINIC_MANAGER"),
                        Set.of("UC22"), Set.of(81001L), false)));
        var ids = new AtomicLong(77000);
        var commands = new ResolutionHistoryService().bind(new JdbcTransactions(db), auth,
                Clock.systemUTC(), c -> ids.incrementAndGet());
        ScheduleStateToken.Version draft;
        try (var c = db.getConnection()) {
            draft = new JdbcScheduleStore().versions(c, 87001).stream()
                    .filter(v -> v.versionNo() == 3).findFirst().orElseThrow();
        }
        var key = UUID.randomUUID();
        var input = AuditDocuments.JSON.createObjectNode().put("note", "SYNTHETIC commit acknowledgement lost");
        var effectiveBefore = effectiveSnapshot(db);
        var uncertain = commands.submit(key, input, draft, 901, null, null,
                (c, ref, hash) -> document(ref, hash, 78001));
        assertEquals("UNKNOWN", uncertain.businessOutcome());
        var recovered = commands.recover(key, input, draft, 901, null, null);
        assertEquals("SUCCEEDED", recovered.businessOutcome());
        assertEquals(uncertain.requestReference(), recovered.requestReference());
        assertEquals("78001", recovered.result().path("resolution_id").asText());
        var repeated = commands.submit(key, input, draft, 901, null, null, (c, ref, hash) -> {
            throw new AssertionError("Committed work must never be executed again");
        });
        assertEquals(recovered.result(), repeated.result());
        assertEquals(1, count(db, 78001));
        var conflict = commands.submit(key, input.deepCopy().put("note", "SYNTHETIC different payload"),
                draft, 901, null, null, (c, ref, hash) -> {
                    throw new AssertionError("Mismatched request must never execute");
                });
        assertEquals("REJECTED", conflict.businessOutcome());
        assertEquals(recovered.requestReference(), conflict.requestReference());
        assertEquals(cn.adc.shared.audit.CommandRecovery.Persistence.CONFIRMED, conflict.auditPersistence());
        try (var c = db.getConnection(); var query = c.prepareStatement("""
                SELECT evidence FROM adc.operation_audit_event WHERE request_reference=?
                  AND evidence::jsonb->>'eventPhase'='RECOVERY_OBSERVATION'
                  AND reason='SAME_REQUEST_DIFFERENT_PAYLOAD'
                """)) {
            query.setString(1, conflict.requestReference());
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                var observation = AuditDocuments.parse(AuditDocuments.parse(rows.getString(1))
                        .path("observationReason").asText());
                assertEquals("SAME_REQUEST_DIFFERENT_PAYLOAD", observation.path("reason").asText());
                assertNotEquals(observation.path("originalPayloadHash"), observation.path("attemptedPayloadHash"));
                assertFalse(rows.next());
            }
        }
        assertEquals(recovered.result(), commands.recover(key, input, draft, 901, null, null).result());
        assertEquals(1, count(db, 78001));
        assertEquals(effectiveBefore, effectiveSnapshot(db));
    }

    private static String effectiveSnapshot(T028IsolatedPgIT.IsolatedData db) throws SQLException {
        try (var c = db.getConnection(); var p = c.prepareStatement("""
                SELECT jsonb_agg(to_jsonb(v) ORDER BY schedule_id,schedule_version_no)::text
                FROM adc.reception_schedule_version v WHERE activation_status='EFFECTIVE'
                """); var rows = p.executeQuery()) {
            assertTrue(rows.next());
            var snapshot = rows.getString(1);
            assertNotNull(snapshot, "fixture must contain effective schedules");
            return snapshot;
        }
    }

    public static void assertFormalFactCommits(T028IsolatedPgIT.IsolatedData db, long appointment,
                                               String token, String uc, String event, String basis,
                                               long id) throws SQLException {
        var auth = new StaffAuthorization(() -> Optional.of("SYNTHETIC manager"), () -> Map.of(
                "SYNTHETIC manager", new StaffAuthorization.Grant("SYNTHETIC-v1", Set.of("CLINIC_MANAGER"),
                        Set.of("UC22"), Set.of(81001L), false)));
        var ids = new AtomicLong(id * 10);
        var commands = new ResolutionHistoryService().bind(new JdbcTransactions(db), auth,
                Clock.systemUTC(), c -> ids.incrementAndGet());
        ScheduleStateToken.Version draft;
        try (var c = db.getConnection()) {
            draft = new JdbcScheduleStore().versions(c, 87001).stream()
                    .filter(v -> v.versionNo() == 3).findFirst().orElseThrow();
        }
        var input = AuditDocuments.JSON.createObjectNode().put("sourceEvent", event);
        var key = UUID.randomUUID();
        var result = commands.submit(key, input, draft, appointment, null, null, (c, ref, hash) -> {
            var doc = withAppointment(document(ref, hash, id), appointment);
            var verification = (ObjectNode) doc.get("verification");
            verification.put("appointmentFingerprint", token).put("conclusion", "VERIFIED").putNull("pendingReason");
            verification.putObject("formalEvidence").put("ucCode", uc).put("auditSuccessEventId", event)
                    .put("UC03".equals(uc) ? "appointmentChangeId" : "cancellationBasisReference", basis);
            var after = (ObjectNode) doc.get("after");
            after.put("resolution_result", "VERIFIED").putNull("pending_confirmation");
            if ("UC03".equals(uc)) after.put("appointment_change_id", basis);
            return doc;
        });
        assertEquals("SUCCEEDED", result.businessOutcome(), result::reason);
        assertEquals(1, count(db, id));
        assertEquals(result.result(), commands.recover(key, input, draft, appointment, null, null).result());
        var history = new ResolutionHistoryReadService(new JdbcTransactions(db), auth)
                .forVersion(87001, 3).stream().filter(h -> h.root() == id).findFirst().orElseThrow();
        assertEquals("VERIFIED", history.currentAssessment());
        assertEquals(event, history.formalFacts().getFirst().path("auditSuccessEventId").asText());
        if ("UC04".equals(uc)) {
            var former = new ResolutionHistoryReadService(new JdbcTransactions(db), auth)
                    .forVersion(87001, 3).stream().filter(h -> h.root() == 76001).findFirst().orElseThrow();
            assertEquals("STALE", former.currentAssessment());
            assertEquals("UC03", former.formalFacts().getFirst().path("ucCode").asText());
        }
    }

    @Test void concurrentCorrectionsToTheSameHeadLeaveOnlyOneCommittedAppend() throws Exception {
        var db = new T028IsolatedPgIT.IsolatedData("runtime", 0);
        var auth = new StaffAuthorization(() -> Optional.of("SYNTHETIC manager"), () -> Map.of(
                "SYNTHETIC manager", new StaffAuthorization.Grant("SYNTHETIC-v1", Set.of("CLINIC_MANAGER"),
                        Set.of("UC22"), Set.of(81001L), false)));
        var service = new ResolutionHistoryService();
        var ids = new AtomicLong(74000);
        var commands = service.bind(new JdbcTransactions(db), auth, Clock.systemUTC(), c -> ids.incrementAndGet());
        ScheduleStateToken.Version draft;
        try (var c = db.getConnection()) {
            draft = new JdbcScheduleStore().versions(c, 87001).stream()
                    .filter(v -> v.versionNo() == 3).findFirst().orElseThrow();
        }
        var input = AuditDocuments.JSON.createObjectNode().put("note", "SYNTHETIC competing correction");
        var original = commands.submit(UUID.randomUUID(), input, draft, 901, null, null,
                (c, ref, hash) -> withAppointment(document(ref, hash, 75001), 901));
        assertEquals("SUCCEEDED", original.businessOutcome());
        var token = AuditDocuments.hash(original.result());
        var firstReady = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> commands.submit(UUID.randomUUID(), input, draft, 901, 75001L, token,
                    (c, ref, hash) -> {
                        firstReady.countDown();
                        try {
                            if (!release.await(4, TimeUnit.SECONDS)) throw new SQLException("SYNTHETIC release timeout");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new SQLException(e);
                        }
                        return correction(ref, hash, 75002, (ObjectNode) original.result());
                    }));
            try {
                assertTrue(firstReady.await(5, TimeUnit.SECONDS));
                var second = pool.submit(() -> commands.submit(UUID.randomUUID(), input, draft, 901, 75001L, token,
                        (c, ref, hash) -> correction(ref, hash, 75003, (ObjectNode) original.result())));
                assertTrue(observeAdvisoryWait(db), "second transaction must wait on the first");
                release.countDown();
                assertEquals("SUCCEEDED", first.get(5, TimeUnit.SECONDS).businessOutcome());
                var rejected = second.get(5, TimeUnit.SECONDS);
                assertEquals("REJECTED", rejected.businessOutcome());
                assertEquals("RESOLUTION_HEAD_STALE", rejected.reason());
            } finally {
                release.countDown();
            }
        }
        assertEquals(1, count(db, 75002));
        assertEquals(0, count(db, 75003));
        assertEquals("SUCCEEDED", commands.submit(UUID.randomUUID(), input, draft, 901, null, null,
                (c, ref, hash) -> document(ref, hash, 75004)).businessOutcome());
        var histories = new ResolutionHistoryReadService(new JdbcTransactions(db), auth);
        var history = histories.forAppointment(81001, 87001, 3, 901).stream()
                .filter(chain -> chain.root() == 75001).findFirst().orElseThrow();
        assertEquals(75002, history.head());
        assertEquals(2, history.revisions().size());
        var all = histories.forVersion(87001, 3);
        assertTrue(all.stream().anyMatch(chain -> chain.root() == 75001 && chain.head() == 75002));
        assertThrows(SecurityException.class, () -> histories.forVersion(87002, 1));
        var impacts = new ImpactAssessmentService();
        var preview = impacts.preview(new JdbcTransactions(db), auth, 81001L, 87001, 3);
        assertEquals(3, preview.target().version());
        assertTrue(preview.items().stream().anyMatch(item -> item.impact().appointmentId() == 901
                && item.impact().historicalVersion() == 1 && !item.appointmentToken().isBlank()));
        assertTrue(preview.items().stream().noneMatch(item -> item.impact().appointmentId() == 902));
        assertThrows(SecurityException.class,
                () -> impacts.preview(new JdbcTransactions(db), auth, 81001L, 87002, 1));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new ResolutionController((request, schedule, version) ->
                        ResolutionController.historyBody(histories.forVersion(schedule, version)))).build();
        String historyPath = "/api/v1/staff/schedules/{id}/versions/{version}/resolution-histories";
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(historyPath, 87001, 3)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        var body = AuditDocuments.parse(response.getContentAsString());
        assertTrue(body.isArray());
        assertTrue(body.size() >= 2);
        var persistedChain = body.valueStream().filter(item -> "75001".equals(item.path("rootResolutionId").asText()))
                .findFirst().orElseThrow();
        assertEquals("75002", persistedChain.path("currentHeadReference").asText());
        assertEquals("75002", persistedChain.path("revisions").path(0).path("resolution_id").asText());
        var denied = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(historyPath, 87002, 1)).andReturn().getResponse();
        assertEquals(403, denied.getStatus());
        assertEquals("FORBIDDEN", AuditDocuments.parse(denied.getContentAsString()).path("code").asText());
        assertThrows(SecurityException.class, () -> histories.forAppointment(81002, 87001, 3, 901));
        assertThrows(SecurityException.class, () -> histories.forAppointment(81001, 87001, 3, 902));
    }

    private static boolean observeAdvisoryWait(T028IsolatedPgIT.IsolatedData db) throws Exception {
        try (var c = db.getConnection(); var p = c.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                WHERE datname=current_database() AND wait_event='advisory'
                  AND cardinality(pg_blocking_pids(pid)) > 0)
                """)) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1250);
            do {
                try (var rows = p.executeQuery()) {
                    if (rows.next() && rows.getBoolean(1)) return true;
                }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            return false;
        }
    }

    private static ObjectNode withAppointment(tools.jackson.databind.JsonNode document, long appointment) {
        ((ObjectNode) document.get("after")).put("appointment_id", Long.toString(appointment));
        return (ObjectNode) document;
    }

    private static ObjectNode correction(String ref, String hash, long id, ObjectNode original) {
        var doc = withAppointment(document(ref, hash, id), 901)
                .put("operationKind", "RESOLUTION_APPEND").put("rootResolutionId", "75001")
                .put("predecessorResolutionId", "75001").put("revisionReason", "SYNTHETIC correction");
        doc.set("before", original);
        return doc;
    }

    @Test void appendAndSuccessAuditAreAtomicAndRecoveryReturnsTheCommittedRecord() throws Exception {
        var db = new T028IsolatedPgIT.IsolatedData("runtime", 0);
        var auth = new StaffAuthorization(() -> Optional.of("SYNTHETIC manager"), () -> Map.of(
                "SYNTHETIC manager", new StaffAuthorization.Grant("SYNTHETIC-v1", Set.of("CLINIC_MANAGER"),
                        Set.of("UC22"), Set.of(81001L), false)));
        var service = new ResolutionHistoryService();
        var ids = new AtomicLong(72000);
        var commands = service.bind(new JdbcTransactions(db), auth, Clock.systemUTC(), c -> ids.incrementAndGet());
        ScheduleStateToken.Version draft;
        try (var c = db.getConnection()) {
            draft = new JdbcScheduleStore().versions(c, 87001).stream()
                    .filter(v -> v.versionNo() == 3).findFirst().orElseThrow();
        }
        var key = UUID.randomUUID();
        var input = AuditDocuments.JSON.createObjectNode().put("note", "SYNTHETIC pending");
        var result = commands.submit(key, input, draft, 901, null, null,
                (c, ref, hash) -> document(ref, hash, 73001));
        assertEquals("SUCCEEDED", result.businessOutcome());
        assertEquals(result.result(), commands.recover(key, input, draft, 901, null, null).result());
        assertEquals(1, count(db, 73001));

        var fail = service.bind(new JdbcTransactions(db), auth, Clock.systemUTC(), c -> {
            if (count(c, 73002) != 0) throw new SQLException("SYNTHETIC injected audit failure", "23514");
            return ids.incrementAndGet();
        });
        var failed = fail.submit(UUID.randomUUID(), input, draft, 901, null, null,
                (c, ref, hash) -> document(ref, hash, 73002));
        assertEquals("FAILED", failed.businessOutcome());
        assertEquals(0, count(db, 73002));
        var tx = new JdbcTransactions(db);
        var token = tx.execute(c -> new cn.adc.appointments.AppointmentResolutionEvidence()
                .currentToken(c, 81001, 901).orElseThrow());
        var submission = new ResolutionSubmission(tx, auth, Clock.systemUTC(),
                c -> ids.incrementAndGet(), c -> 73003);
        var request = AuditDocuments.JSON.createObjectNode().put("appointmentId", "901")
                .put("expectedAppointmentToken", token).put("subject", "SYNTHETIC")
                .put("claimedResult", "VERIFIED").put("pendingReason", "SYNTHETIC pending");
        var saved = submission.save(87001, 3, "SYNTHETIC-HTTP-input", request);
        assertEquals("SUCCEEDED", saved.businessOutcome());
        assertEquals("PENDING", saved.result().path("resolution_result").asText());
        assertEquals(1, count(db, 73003));
        assertTrue(submission.save(87001, 3, "SYNTHETIC-HTTP-input", request).existingResult());
        var rejected = submission.save(87001, 3, "SYNTHETIC-stale",
                request.deepCopy().put("expectedAppointmentToken", "stale"));
        assertEquals("RESOLUTION_APPOINTMENT_STALE", rejected.reason());
        var nextId = new AtomicLong(73003);
        var reader = new ResolutionHistoryReadService(tx, auth);
        var stamp = new cn.adc.shared.config.SourceStamp("SYNTHETIC source", "SYNTHETIC-v1",
                "SYNTHETIC verifier", java.time.Instant.parse("2026-11-08T00:00:00Z"), true);
        var binding = new cn.adc.shared.security.StaffBindingAdapter.Entry("SYNTHETIC manager",
                new StaffAuthorization.Grant(stamp.version(), Set.of("CLINIC_MANAGER"), Set.of("UC22"),
                        Set.of(81001L), false), stamp);
        var grants = new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of(binding));
        var factory = new ScheduleAccessFactory(tx, new cn.adc.shared.security.StaffBindingAdapter(grants::get),
                () -> stamp, clinic -> { throw new AssertionError("unexpected clinic lookup"); },
                (dentist, clinic) -> { throw new AssertionError("unexpected dentist lookup"); },
                (clinic, dentist, start, end, submitted, conflicts) -> Optional.empty(),
                Clock.systemUTC(), c -> ids.incrementAndGet(), c -> nextId.incrementAndGet());
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(cn.adc.shared.security.StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new cn.adc.shared.security.StaffSessionIdentityAdapter.VerifiedSession(
                        "SYNTHETIC manager", "SYNTHETIC-CSRF", stamp));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new ResolutionController(factory)).build();
        var original = reader.forVersion(87001, 3).stream().filter(h -> h.root() == 73003).findFirst().orElseThrow();
        var correction = request.deepCopy().put("resolutionId", "73003")
                .put("expectedResolutionToken", original.headFingerprint())
                .put("revisionReason", "SYNTHETIC correction").put("subject", "SYNTHETIC corrected");
        String url = "/api/v1/staff/schedules/87001/versions/3/resolutions";
        for (var clientKey : java.util.List.of("SYNTHETIC-revision", "SYNTHETIC-revision")) {
            var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                    .session(session)
                    .header("Idempotency-Key", clientKey).header("X-CSRF-Token", "SYNTHETIC-CSRF")
                    .contentType("application/json").content(correction.toString())).andReturn().getResponse();
            assertEquals(200, response.getStatus(), response.getContentAsString());
            assertEquals("73004", AuditDocuments.parse(response.getContentAsString()).path("links")
                    .path("resolutionId").asText());
        }
        var stale = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .session(session)
                .header("Idempotency-Key", "SYNTHETIC-competing").header("X-CSRF-Token", "SYNTHETIC-CSRF")
                .contentType("application/json").content(correction.toString())).andReturn().getResponse();
        assertEquals(409, stale.getStatus(), stale.getContentAsString());
        assertEquals("RESOLUTION_HEAD_STALE", AuditDocuments.parse(stale.getContentAsString()).path("code").asText());
        var historyReply = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/staff/schedules/87001/versions/3/resolution-histories").session(session)).andReturn().getResponse();
        var history = AuditDocuments.parse(historyReply.getContentAsString()).valueStream()
                .filter(item -> "73003".equals(item.path("rootResolutionId").asText())).findFirst().orElseThrow();
        assertEquals(200, historyReply.getStatus());
        assertEquals(original.revisions().getFirst(), history.get("originalRecord"));
        assertEquals("73004", history.path("currentHeadReference").asText());
        assertEquals(1, history.path("revisions").size());
        String impact = "/api/v1/staff/schedules/87001/versions/3/impacts";
        assertEquals(401, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(impact)).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(impact).session(session)).andReturn().getResponse().getStatus());
        String checkPath = "/api/v1/staff/schedules/87001/versions/3/impact-checks";
        var check = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(checkPath)
                .session(session).header("Idempotency-Key", "SYNTHETIC-check")
                .header("X-CSRF-Token", "SYNTHETIC-CSRF");
        var observed = mvc.perform(check).andReturn().getResponse();
        assertEquals(200, observed.getStatus());
        assertFalse(AuditDocuments.parse(observed.getContentAsString()).path("checkToken").asText().isBlank());
        grants.set(java.util.List.of());
        assertEquals(403, mvc.perform(check).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(impact).session(session)).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .session(session).header("Idempotency-Key", "SYNTHETIC-revision")
                .header("X-CSRF-Token", "SYNTHETIC-CSRF").contentType("application/json")
                .content(correction.toString())).andReturn().getResponse().getStatus());
        assertEquals(0, count(db, 73006));
        grants.set(java.util.List.of(binding));
        ResolutionBrowserBridge.verify(mvc, session);
        assertEquals(1, count(db, nextId.get()));
    }

    private static long count(T028IsolatedPgIT.IsolatedData db, long id) throws SQLException {
        try (var c = db.getConnection()) { return count(c, id); }
    }

    private static long count(Connection c, long id) throws SQLException {
        try (var p = c.prepareStatement("SELECT count(*) FROM adc.appointment_resolution WHERE resolution_id=?")) {
            p.setLong(1, id);
            try (var rows = p.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static tools.jackson.databind.JsonNode document(String ref, String hash, long id) {
        String resolution = Long.toString(id);
        var doc = AuditDocuments.JSON.createObjectNode().put("schemaVersion", 1)
                .put("operationKind", "RESOLUTION_CREATE").put("requestReference", ref)
                .put("canonicalPayloadHash", hash).put("rootResolutionId", resolution)
                .put("newResolutionId", resolution).putNull("predecessorResolutionId")
                .putNull("revisionReason").putNull("before");
        doc.putObject("targetDraftRef").put("scheduleId", "87001").put("versionNo", 3);
        doc.putObject("after").put("resolution_id", resolution).put("schedule_id", "87001")
                .put("schedule_version_no", 3).put("appointment_id", "901")
                .put("resolution_subject", "SYNTHETIC").put("resolution_result", "PENDING")
                .putNull("resolution_basis").put("responsible_party", "SYNTHETIC manager")
                .put("pending_confirmation", "SYNTHETIC pending")
                .put("recorded_at", "2026-11-08T00:00:00Z").putNull("appointment_change_id");
        doc.putObject("verification").put("evaluatedAt", "2026-11-08T00:00:00Z")
                .put("appointmentFingerprint", "a".repeat(64)).put("conclusion", "PENDING")
                .putNull("formalEvidence").put("pendingReason", "SYNTHETIC pending").putArray("supportReasons");
        return doc;
    }
}
