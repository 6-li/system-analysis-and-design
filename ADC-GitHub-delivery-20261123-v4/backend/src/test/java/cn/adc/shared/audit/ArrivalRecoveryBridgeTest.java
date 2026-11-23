package cn.adc.shared.audit;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;

/** Existing audit/recovery components and a JDBC double; no arrival/no-show service or writer. */
class ArrivalRecoveryBridgeTest {
    private static final String ACTOR = "SYNTHETIC-clinic-reception";
    private static final String APPOINTMENT = "901";
    private static final Set<Long> SCOPE = Set.of(81001L);
    private static final UUID KEY = UUID.fromString("05060000-0000-4000-8000-000000000901");
    private final RecoveryRepairTest.Memory journal = new RecoveryRepairTest.Memory();
    private final AtomicInteger ids = new AtomicInteger();
    private final CommandRecovery recovery = recovery();
    private AuditDocuments.Change observedFact;

    @ParameterizedTest
    @CsvSource({"UC05,ARRIVAL_VERIFY", "UC06,NO_SHOW_VERIFY"})
    void unknownCommitRequiresMatchingEvidenceBeforeReturningOriginalReceipt(String uc, String operation) {
        var command = command(uc, operation);
        var fact = evidence(command);
        var unknown = recovery.execute(command, (connection, locks) -> {
            journal.loseCommit = true;
            return fact;
        });
        assertEquals("UNKNOWN", unknown.businessOutcome());
        assertNull(unknown.result());
        assertNull(unknown.successEventId());

        var beforeIndependentProof = recovery.execute(command, (connection, locks) -> {
            throw new AssertionError("Unknown command must not re-execute");
        });
        assertEquals("UNKNOWN", beforeIndependentProof.businessOutcome());
        assertEquals(unknown.requestReference(), beforeIndependentProof.requestReference());
        assertNull(beforeIndependentProof.result());

        observedFact = fact;
        var recovered = recovery.recover(command, false);
        assertEquals("SUCCEEDED", recovered.businessOutcome());
        assertTrue(recovered.existingResult());
        assertEquals(unknown.requestReference(), recovered.requestReference());
        assertEquals(fact.after(), recovered.result());
        assertEquals("2", recovered.successEventId());
        assertEquals(1, journal.committed.stream().filter(event -> "SUCCESS".equals(event.get(10))).count());
    }

    @Test void sameClientKeyForOtherUseCaseCannotRecoverArrivalAudit() {
        var arrival = command("UC05", "ARRIVAL_VERIFY");
        var noShow = command("UC06", "NO_SHOW_VERIFY");
        observedFact = evidence(arrival);
        var saved = recovery.execute(arrival, (connection, locks) -> observedFact);
        var other = recovery.recover(noShow, false);
        assertEquals("SUCCEEDED", saved.businessOutcome());
        assertEquals("UNKNOWN", other.businessOutcome());
        assertNotEquals(saved.requestReference(), other.requestReference());
        assertNull(other.result());
        assertNull(other.successEventId());
        assertEquals(2, journal.committed.size());
    }

    private CommandRecovery recovery() {
        var grant = new StaffAuthorization.Grant("SYNTHETIC-v1", Set.of("CLINIC_RECEPTION"),
                Set.of("UC05", "UC06"), SCOPE, false);
        var auth = new StaffAuthorization(() -> Optional.of(ACTOR), () -> Map.of(ACTOR, grant));
        var verifier = new CommandRecovery.SuccessVerifier() {
            public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode snapshot) {
                return observedFact != null
                        && command.ucCode().equals(observedFact.verification().path("syntheticFactOwner").asText())
                        && observedFact.businessReference().equals(snapshot.path("businessReference"))
                        && observedFact.after().equals(snapshot.path("after"));
            }
            public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String request) {
                return false;
            }
        };
        var clock = Clock.fixed(Instant.parse("2026-11-08T09:27:00.123456Z"), ZoneOffset.UTC);
        return new UcAuditService(new JdbcTransactions(journal.source()), auth, verifier, clock,
                connection -> ids.incrementAndGet()).commands();
    }

    private static CommandRecovery.Command command(String uc, String operation) {
        return new CommandRecovery.Command(operation, uc, KEY,
                JSON.createObjectNode().put("appointmentId", APPOINTMENT), SCOPE);
    }

    private static AuditDocuments.Change evidence(CommandRecovery.Command command) {
        var reference = JSON.createObjectNode().put("appointmentId", APPOINTMENT);
        var result = reference.deepCopy();
        if ("UC05".equals(command.ucCode())) result.put("visitId", "310901");
        return new AuditDocuments.Change(reference, reference.deepCopy(), result,
                JSON.createObjectNode().put("syntheticFactOwner", command.ucCode()));
    }
}
