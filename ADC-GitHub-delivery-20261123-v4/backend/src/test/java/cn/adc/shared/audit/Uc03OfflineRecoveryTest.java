package cn.adc.shared.audit;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Actual generic audit/recovery command with only test callback snapshots and a JDBC audit double. */
class Uc03OfflineRecoveryTest {
    private static final String ACTOR = "SYNTHETIC-UC03-reception";
    private static final UUID KEY = UUID.fromString("03000000-0000-4000-8000-000000000001");
    private static final Set<Long> CLINICS = Set.of(81001L, 81002L);
    private static final String APPOINTMENT = "901", CHANGE = "51001";
    private final RecoveryRepairTest.Memory audit = new RecoveryRepairTest.Memory();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicBoolean authorized = new AtomicBoolean(true);
    private final AtomicBoolean historyVerified = new AtomicBoolean(true);
    private final CommandRecovery recovery = recovery();
    private final CommandRecovery.Command command = command("SYNTHETIC-UC03-original-demand");

    @Test void u03c01SameKeyKeepsOriginalIdHistoryAndAuditAndRejectsAlteredPayload() {
        var result = recovery.execute(command, (c, locks) -> change());
        assertEquals("SUCCEEDED", result.businessOutcome());
        assertEquals(APPOINTMENT, result.result().path("appointmentId").asText());
        assertEquals(CHANGE, result.result().path("appointmentChangeId").asText());
        var recovered = recovery.execute(command, (c, locks) -> { throw new AssertionError("duplicate write"); });
        assertTrue(recovered.existingResult());
        assertEquals(result.requestReference(), recovered.requestReference());
        assertEquals(result.successEventId(), recovered.successEventId());
        assertEquals(result.result(), recovered.result());
        var mismatch = recovery.execute(command("SYNTHETIC-UC03-other-demand"),
                (c, locks) -> { throw new AssertionError("altered payload write"); });
        assertEquals("REJECTED", mismatch.businessOutcome());
        assertNull(mismatch.result());
        assertEquals(1, successfulAudits());
        var record = AuditDocuments.parse((String)audit.committed.get(1).get(12)).path("businessResultSnapshot");
        assertEquals(change().before(), record.path("before"));
        assertEquals(change().after(), record.path("after"));
        assertEquals(change().businessReference(), record.path("businessReference"));
        assertEquals(change().verification(), record.path("verification"));
    }

    @Test void u03c02UnknownStartNeverExecutesOrReportsSuccess() {
        audit.loseCommit = true;
        var result = recovery.execute(command, (c, locks) -> { throw new AssertionError("must not run"); });
        assertEquals("UNKNOWN", result.businessOutcome());
        assertNull(result.result());
        assertEquals(result.requestReference(), recovery.recover(command, false).requestReference());
        assertEquals("UNKNOWN", recovery.recover(command, false).businessOutcome());
        assertEquals(0, successfulAudits());
    }

    @Test void u03c03LostCommitReplyRecoversOnlyWithVerifiedHistory() {
        var result = recovery.execute(command, (c, locks) -> {
            audit.loseCommit = true;
            return change();
        });
        assertEquals("UNKNOWN", result.businessOutcome());
        assertNull(result.result());
        historyVerified.set(false);
        var unknown = recovery.recover(command, false);
        assertEquals("UNKNOWN", unknown.businessOutcome());
        assertNull(unknown.result());
        historyVerified.set(true);
        var confirmed = recovery.recover(command, false);
        assertEquals("SUCCEEDED", confirmed.businessOutcome());
        assertEquals("2", confirmed.successEventId());
        assertEquals(result.requestReference(), confirmed.requestReference());
        assertEquals(change().after(), confirmed.result());
        assertEquals(1, successfulAudits());
    }

    @Test void u03c04DisconnectBeforeBusinessCommitCannotClaimSuccessOrFailure() {
        var result = recovery.execute(command, (c, locks) -> {
            throw new SQLException("synthetic lost connection before commit", "08006");
        });
        assertEquals("UNKNOWN", result.businessOutcome());
        assertEquals("WORK_ROLLBACK_UNCONFIRMED", result.reason());
        assertNull(result.result());
        assertEquals("UNKNOWN", recovery.recover(command, false).businessOutcome());
        assertEquals(0, successfulAudits());
    }

    @Test void u03c05ConfirmedSaveFailureRollsBackAndRecoversOriginalFailure() {
        var original = recovery.execute(command, (c, locks) -> { throw new SQLException("synthetic write failure", "23514"); });
        assertEquals("FAILED", original.businessOutcome());
        assertNull(original.result());
        assertEquals(1, audit.rollbacks);
        var repeated = recovery.execute(command, (c, locks) -> { throw new AssertionError("failed request replayed"); });
        assertEquals("FAILED", repeated.businessOutcome());
        assertEquals(original.requestReference(), repeated.requestReference());
        assertNull(repeated.result());
        assertEquals(0, successfulAudits());
    }

    @Test void u03c06RevocationBeforeSuccessAuditRejectsAndRollsBack() {
        var result = recovery.execute(command, (c, locks) -> {
            authorized.set(false);
            return change();
        });
        assertEquals("REJECTED", result.businessOutcome());
        assertEquals("EXECUTION_PERMISSION_DENIED", result.reason());
        assertNull(result.result());
        assertEquals(1, audit.rollbacks);
        assertEquals(0, successfulAudits());
    }

    private long successfulAudits() { return audit.committed.stream().filter(row -> "SUCCESS".equals(row.get(10))).count(); }

    private CommandRecovery recovery() {
        var grant = new StaffAuthorization.Grant("SYNTHETIC-UC03", Set.of("BOOKING_RECEPTION"), Set.of("UC03"), CLINICS, false);
        var auth = new StaffAuthorization(() -> Optional.of(ACTOR), () -> authorized.get() ? Map.of(ACTOR, grant) : Map.of());
        var verifier = new CommandRecovery.SuccessVerifier() {
            public boolean matchesCommitted(Connection c, CommandRecovery.Command cmd, JsonNode snapshot) {
                return historyVerified.get() && command.equals(cmd)
                        && change().businessReference().equals(snapshot.path("businessReference"))
                        && change().after().equals(snapshot.path("after"));
            }
            public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command cmd, String ref) { return false; }
        };
        var clock = Clock.fixed(Instant.parse("2026-11-08T08:00:00Z"), ZoneOffset.UTC);
        return new UcAuditService(new JdbcTransactions(audit.source()), auth, verifier, clock,
                c -> ids.incrementAndGet()).commands();
    }

    private static CommandRecovery.Command command(String demand) {
        return new CommandRecovery.Command("APPOINTMENT_CHANGE", "UC03", KEY, AuditDocuments.JSON.createObjectNode()
                .put("appointmentId", APPOINTMENT).put("expectedAppointmentToken", "SYNTHETIC-UC03-v1")
                .put("treatmentRequest", demand).put("acceptanceEvidenceReference", "SYNTHETIC-UC03-acceptance"), CLINICS);
    }

    private static AuditDocuments.Change change() {
        var reference = AuditDocuments.JSON.createObjectNode().put("appointmentId", APPOINTMENT).put("appointmentChangeId", CHANGE);
        var before = AuditDocuments.JSON.createObjectNode().put("appointmentId", APPOINTMENT).put("patientId", "88001")
                .put("scheduleId", "87001").put("versionNo", 2)
                .put("startsAt", "2026-11-08T08:00:00.123456Z").put("endsAt", "2026-11-08T08:27:00.123456Z");
        var after = before.deepCopy().put("appointmentChangeId", CHANGE).put("scheduleId", "87002")
                .put("startsAt", "2026-11-08T09:00:00.123456Z").put("endsAt", "2026-11-08T09:27:00.123456Z");
        var proof = AuditDocuments.JSON.createObjectNode().put("synthetic", true)
                .put("appointmentChangeId", CHANGE).put("acceptanceEvidenceReference", "SYNTHETIC-UC03-acceptance");
        return new AuditDocuments.Change(reference, before, after, proof);
    }
}
