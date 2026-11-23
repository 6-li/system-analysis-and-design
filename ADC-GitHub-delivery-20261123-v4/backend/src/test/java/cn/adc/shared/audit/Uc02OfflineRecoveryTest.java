package cn.adc.shared.audit;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Generic command journal with a synthetic appointment callback and JDBC transaction double. */
class Uc02OfflineRecoveryTest {
    private final RecoveryRepairTest.Memory database = new RecoveryRepairTest.Memory();
    private final AtomicLong auditIds = new AtomicLong();
    private final CommandRecovery.Command command = command(UUID.randomUUID(), "SYNTHETIC-UC02-confirmation");
    private final CommandRecovery recovery = recovery();

    @Test void sameKeyRecoversOriginalResultAndDifferentPayloadCannotRun() {
        var original = recovery.execute(command, (connection, locks) -> saved());
        assertEquals("SUCCEEDED", original.businessOutcome());
        var duplicate = recovery.execute(command, (connection, locks) -> { throw new AssertionError("replay"); });
        assertTrue(duplicate.existingResult());
        assertEquals(original.successEventId(), duplicate.successEventId());
        assertEquals(original.result(), duplicate.result());

        var conflict = recovery.execute(command(command.clientKey(), "SYNTHETIC-different"),
                (connection, locks) -> { throw new AssertionError("changed payload replay"); });
        assertEquals("REJECTED", conflict.businessOutcome());
        assertNull(conflict.result());
        assertEquals("SUCCESS", database.committed.get(1).get(10));
        var mismatch = AuditDocuments.parse(evidence(database.committed.size() - 1).path("observationReason").asText());
        assertEquals("SAME_REQUEST_DIFFERENT_PAYLOAD", mismatch.path("reason").asText());
        assertEquals(evidence(0).path("canonicalPayloadHash"), mismatch.path("originalPayloadHash"));
        assertTrue(mismatch.path("attemptedPayloadHash").isTextual());
        assertNotEquals(mismatch.path("originalPayloadHash"), mismatch.path("attemptedPayloadHash"));
    }

    @Test void commitReplyLossIsUnknownUntilOriginalSuccessIsVerified() {
        var unknown = recovery.execute(command, (connection, locks) -> {
            database.loseCommit = true;
            return saved();
        });
        assertEquals("UNKNOWN", unknown.businessOutcome());
        assertNull(unknown.result());
        var confirmed = recovery.recover(command, false);
        assertEquals("SUCCEEDED", confirmed.businessOutcome());
        assertEquals(unknown.requestReference(), confirmed.requestReference());
        assertEquals("2", confirmed.successEventId());
    }

    @Test void startReplyLossDoesNotExecuteBusinessWork() {
        database.loseCommit = true;
        var result = recovery.execute(command, (connection, locks) -> { throw new AssertionError("unexpected work"); });
        assertEquals("UNKNOWN", result.businessOutcome());
        assertEquals("UNKNOWN", recovery.recover(command, false).businessOutcome());
        assertEquals(2, database.committed.size());
        assertEquals("REQUEST_STARTED", evidence(0).path("eventPhase").asText());
        assertEquals("RECOVERY_OBSERVATION", evidence(1).path("eventPhase").asText());
        assertEquals("UNKNOWN", evidence(1).path("confirmedCommit").asText());
    }

    private JsonNode evidence(int index) {
        return AuditDocuments.parse((String) database.committed.get(index).get(12));
    }

    private CommandRecovery recovery() {
        var grant = new StaffAuthorization.Grant("SYNTHETIC-policy", Set.of("BOOKING_RECEPTION"),
                Set.of("UC02"), Set.of(2L), false);
        var auth = new StaffAuthorization(() -> Optional.of("SYNTHETIC-receptionist"),
                () -> Map.of("SYNTHETIC-receptionist", grant));
        var verifier = new CommandRecovery.SuccessVerifier() {
            @Override public boolean matchesCommitted(Connection c, CommandRecovery.Command request, JsonNode snapshot) {
                return snapshot.path("after").path("appointmentId").asText().equals("901");
            }

            @Override public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command request, String ref) {
                return false;
            }
        };
        return new UcAuditService(new JdbcTransactions(database.source()), auth, verifier, Clock.systemUTC(),
                connection -> auditIds.incrementAndGet()).commands();
    }

    private static CommandRecovery.Command command(UUID key, String confirmation) {
        return new CommandRecovery.Command("APPOINTMENT_CREATE", "UC02", key, AuditDocuments.JSON.createObjectNode()
                .put("patientId", "1").put("acceptanceEvidenceReference", confirmation), Set.of(2L));
    }

    private static AuditDocuments.Change saved() {
        var result = AuditDocuments.JSON.createObjectNode().put("appointmentId", "901").put("synthetic", true);
        return new AuditDocuments.Change(result, AuditDocuments.JSON.nullNode(), result,
                AuditDocuments.JSON.createObjectNode());
    }
}
