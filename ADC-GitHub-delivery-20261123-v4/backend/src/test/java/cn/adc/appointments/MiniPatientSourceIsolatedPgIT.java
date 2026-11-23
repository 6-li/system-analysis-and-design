package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class MiniPatientSourceIsolatedPgIT {
    @Test void nativeSimulatorSubmitsAndRecoversCommittedReceipt() throws Exception {
        var db = new T028IsolatedPgIT.IsolatedData("runtime", 0);
        var sessions = new MiniRequestSession(code -> "SYNTHETIC-code".equals(code)
                ? Optional.of("SYNTHETIC-native-account") : Optional.empty(), new MiniRequestSession.Sessions() {
            private final String token = UUID.randomUUID().toString();
            public String issue(String account) { return token; }
            public Optional<String> activeAccount(String value) {
                return token.equals(value) ? Optional.of("SYNTHETIC-native-account") : Optional.empty();
            }
        });
        var intake = new MiniNewRequestIntake(sessions,
                new JdbcMiniRequestIntakeStore(new JdbcTransactions(db), Clock.systemUTC()));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new MiniIntakeController(sessions, intake)).build();
        cn.adc.scheduling.ResolutionBrowserBridge.verifyNative(mvc, null, (port, recovered) -> {
            var evidence = java.nio.file.Path.of(System.getenv("ADC_T028_EVIDENCE"));
            java.nio.file.Files.writeString(evidence.resolve("uc01-native-ready.txt"), "http://127.0.0.1:" + port);
            assertTrue(recovered.await(8, java.util.concurrent.TimeUnit.MINUTES), "Native receipt lookup timed out");
            assertEquals(1, count(db, "SELECT count(*) FROM adc_mini.preliminary_request"));
            assertEquals(1, count(db, "SELECT count(*) FROM adc_mini.operation_audit_event WHERE operation='op008'"));
            assertEquals(1, count(db, """
                    SELECT count(*) FROM adc_mini.preliminary_request
                    WHERE submitted_content->>'intendedClinicId'='81002'
                    """), "The simulator must switch from the default demo clinic to the second clinic before submitting");
        });
    }

    @Test void auditFailureRollsBackBothSidesThenRetryWithNewKeyCommitsOnce() throws Exception {
        verify(false);
    }

    @Test void miniPageAndStaffBrowserShareOnePersistedRequest() throws Exception {
        verify(true);
    }

    private void verify(boolean browser) throws Exception {
        var db = new T028IsolatedPgIT.IsolatedData("runtime", 0);
        var tx = new JdbcTransactions(db);
        var clock = Clock.systemUTC();
        var content = AuditDocuments.JSON.createObjectNode().put("intendedClinicId", "81001");
        content.put("type", "NEW").put("actingMode", "SELF").put("treatmentNeed", "SYNTHETIC");
        content.putObject("identity").put("patientIdentification", "SYNTHETIC-UC01-identity")
                .put("applicantInformation", "SYNTHETIC").put("contactInformation", "SYNTHETIC");
        content.putObject("preferred").put("date", "2026-11-16");
        content.putObject("bookingIntent").put("visitType", "INITIAL");
        var sessions = new MiniRequestSession(code -> "SYNTHETIC-code".equals(code)
                ? Optional.of("SYNTHETIC-account") : Optional.empty(), new MiniRequestSession.Sessions() {
            public String issue(String account) { return "SYNTHETIC-token"; }
            public Optional<String> activeAccount(String token) {
                return "SYNTHETIC-token".equals(token) ? Optional.of("SYNTHETIC-account") : Optional.empty();
            }
        });
        var intake = new MiniNewRequestIntake(sessions, new JdbcMiniRequestIntakeStore(tx, clock));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new MiniIntakeController(sessions, intake)).build();
        var login = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/mini/sessions/wechat").contentType("application/json")
                .content("{\"code\":\"SYNTHETIC-code\"}")).andReturn().getResponse();
        assertEquals(200, login.getStatus());
        String token = AuditDocuments.parse(login.getContentAsString()).path("accessToken").asText();
        var submission = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/mini/requests").header("Idempotency-Key", "SYNTHETIC-UC01")
                .header("Authorization", "Bearer " + token).contentType("application/json")
                .content(content.toString())).andReturn().getResponse();
        assertEquals(201, submission.getStatus());
        var originalReceipt = AuditDocuments.parse(submission.getContentAsString());
        String requestId = AuditDocuments.text(originalReceipt, "requestId");
        var command = AuditDocuments.JSON.createObjectNode().put("patientName", "SYNTHETIC")
                .put("identityInformation", "SYNTHETIC-UC01-identity").put("contactInformation", "SYNTHETIC");
        command.putObject("source").put("kind", "MINI_REQUEST").put("requestId", requestId)
                .put("expectedRequestVersion", 0).put("identityMatchBasis", "SYNTHETIC");
        var stamp = new cn.adc.shared.config.SourceStamp("SYNTHETIC", "SYNTHETIC-v1",
                "SYNTHETIC verifier", java.time.Instant.EPOCH, true);
        var grant = new StaffAuthorization.Grant(stamp.version(), Set.of("BOOKING_RECEPTION"), Set.of("UC01"),
                Set.of(81001L), false);
        var bindings = new cn.adc.shared.security.StaffBindingAdapter(() -> java.util.List.of(
                new cn.adc.shared.security.StaffBindingAdapter.Entry("SYNTHETIC staff", grant, stamp)));
        var ids = new AtomicLong(91000);
        var mini = new JdbcMiniPatientSource((c, actor, clinic, patient, input) ->
                assertEquals("SYNTHETIC", input.path("source").path("identityMatchBasis").asText()), clock);
        var access = new PatientMaintenanceAccess(tx, bindings, req -> 81001,
                (c, ref, actor, clinic) -> Optional.empty(),
                (c, a, clinic, patient, input) -> { throw new AssertionError("no pure UC fallback"); },
                mini, c -> 91001L, c -> {
                    long id = ids.incrementAndGet();
                    if (!browser && id == 91002L) throw new java.sql.SQLException("synthetic original audit failure", "23514");
                    return id;
                }, clock);
        var staffMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new PatientMaintenanceController(access)).build();
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(cn.adc.shared.security.StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new cn.adc.shared.security.StaffSessionIdentityAdapter.VerifiedSession(
                        "SYNTHETIC staff", "SYNTHETIC-csrf", stamp));
        if (browser) {
            session.setAttribute(cn.adc.shared.security.StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                    new cn.adc.shared.security.StaffSessionIdentityAdapter.VerifiedSession(
                            "SYNTHETIC staff", "SYNTHETIC-CSRF", stamp));
            var bridgeMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new MiniIntakeController(sessions, intake), new PatientMaintenanceController(access)).build();
            cn.adc.scheduling.ResolutionBrowserBridge.verify(bridgeMvc, session, "patient");
            assertEquals(1, count(db, "SELECT count(*) FROM adc_mini.preliminary_request WHERE associated_patient_id=91001"));
            assertEquals(1, count(db, "SELECT count(*) FROM adc.operation_audit_event WHERE uc_code='UC01' AND outcome='SUCCESS'"));
            return;
        }
        var create = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/staff/patients").header("X-CSRF-Token", "SYNTHETIC-csrf")
                .contentType("application/json").content(command.toString());
        assertEquals(401, staffMvc.perform(create.header("Idempotency-Key", "unauthenticated"))
                .andReturn().getResponse().getStatus());
        assertEquals(503, staffMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/staff/patients").session(session).header("X-CSRF-Token", "SYNTHETIC-csrf")
                .header("Idempotency-Key", "fail-audit").contentType("application/json").content(command.toString()))
                .andReturn().getResponse().getStatus());
        assertEquals(0, count(db, "SELECT count(*) FROM adc.patient WHERE patient_id=91001"));
        assertEquals(0, count(db, "SELECT count(*) FROM adc_mini.operation_audit_event WHERE operation='UC01_ASSOCIATED'"));
        assertEquals(0, count(db, "SELECT count(*) FROM adc_mini.preliminary_request WHERE associated_patient_id=91001"));
        var accepted = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/staff/patients").session(session).header("X-CSRF-Token", "SYNTHETIC-csrf")
                .header("Idempotency-Key", "original-create").contentType("application/json").content(command.toString());
        var saved = staffMvc.perform(accepted).andReturn().getResponse();
        assertEquals(201, saved.getStatus(), saved.getContentAsString());
        var document = AuditDocuments.parse(saved.getContentAsString());
        assertTrue(document.path("originalUc01SuccessAudit").path("eventId").isString());
        assertTrue(document.path("extensionResultEvent").path("eventId").isString());
        assertFalse(document.path("sourceAssociation").path("patientAccessGrantedByAssociation").asBoolean(true));
        assertEquals(document, AuditDocuments.parse(staffMvc.perform(accepted).andReturn().getResponse().getContentAsString()));
        assertEquals(1, count(db, "SELECT count(*) FROM adc.patient WHERE patient_id=91001"));
        assertEquals(1, count(db, "SELECT count(*) FROM adc_mini.operation_audit_event WHERE operation='UC01_ASSOCIATED'"));
        assertEquals(1, count(db, "SELECT count(*) FROM adc.operation_audit_event WHERE uc_code='UC01' AND outcome='SUCCESS'"));
        var queried = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/mini/submission-receipts/SYNTHETIC-UC01").param("operation", "op008")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertEquals(200, queried.getStatus());
        assertEquals(originalReceipt, AuditDocuments.parse(queried.getContentAsString()));
    }

    private static long count(T028IsolatedPgIT.IsolatedData db, String sql) throws Exception {
        try (var c = db.getConnection(); var p = c.prepareStatement(sql); var rows = p.executeQuery()) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
