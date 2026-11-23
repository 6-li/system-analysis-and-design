package cn.adc.shared.audit;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;

/** Real shared command infrastructure around a test-only cancellation callback and transactional JDBC double. */
class Uc04OfflineCancellationTest {
    private static final long CLINIC = 81001;
    private static final long PATIENT = 88001;
    private static final long DENTIST = 79001;
    private static final long SCHEDULE = 87001;
    private static final long APPOINTMENT = 901;
    private static final String ACTOR = "SYNTHETIC-UC04-reception";
    private static final String BASIS = "SYNTHETIC-verified-cancellation";
    private static final String TOKEN = "SYNTHETIC-appointment-v1";
    private static final Instant NOW = Instant.parse("2026-11-08T08:30:00.123456Z");
    private static final UUID ORIGINAL = UUID.fromString("46fd00ad-0bdc-4d09-90e9-2db9f6404634");
    private static final UUID OTHER = UUID.fromString("321e49ea-c00d-443e-bde5-d8cfb60573bb");
    private static final StaffAuthorization.Grant RECEPTION = new StaffAuthorization.Grant("SYNTHETIC-v1",
            Set.of("BOOKING_RECEPTION"), Set.of("UC04"), Set.of(CLINIC), false);
    private static final List<String> RESOURCE_SQL = List.of(
            "SELECT pg_advisory_xact_lock(?)",
            "SELECT dentist_id FROM adc.dentist WHERE dentist_id=? FOR UPDATE",
            "SELECT schedule_id FROM adc.reception_schedule_version WHERE schedule_id=? AND schedule_version_no=? FOR UPDATE",
            "SELECT appointment_id FROM adc.appointment WHERE appointment_id=? FOR UPDATE");

    @Test void committedCancellationStopsOnlyFutureReminderAndPreservesIdentityAndHistory() {
        var fixture = new Fixture();
        var before = fixture.current;
        var reply = fixture.execute(command(ORIGINAL, BASIS, TOKEN));
        assertEquals("SUCCEEDED", reply.businessOutcome());
        assertEquals(CommandRecovery.Persistence.CONFIRMED, reply.auditPersistence());
        assertFalse(fixture.current.occupies());
        assertFalse(fixture.current.reminderEnabled());
        assertEquals(before.pastReminders(), fixture.current.pastReminders());
        assertEquals(before.changeHistory(), fixture.current.changeHistory());
        assertEquals(before.appointment(), fixture.current.appointment());
        assertEquals(before.patient(), fixture.current.patient());
        assertEquals(before.origin(), fixture.current.origin());
        assertEquals(RESOURCE_SQL, fixture.resourceStatements);
        assertEquals(1, fixture.releases);
        assertEquals(1, fixture.successes());
        var audit = fixture.memory.committed.getLast();
        assertEquals(reply.successEventId(), audit.get(1));
        assertEquals(reply.requestReference(), audit.get(2));
        assertEquals("UC04", audit.get(4));
        assertEquals(ACTOR, audit.get(6));
        assertEquals(NOW.atOffset(ZoneOffset.UTC), audit.get(7));
        var scope = AuditDocuments.parse((String)audit.get(8));
        assertEquals("ALLOW", scope.path("decision").asText());
        assertEquals(CLINIC, scope.at("/actualGrantedScope/0/clinicIds/0").asLong());
        var saved = fixture.evidence(audit).path("businessResultSnapshot");
        assertEquals("待到诊", saved.at("/before/status").asText());
        assertEquals("已取消", saved.at("/after/status").asText());
        assertEquals(BASIS, saved.at("/after/cancellationBasis").asText());
        assertFalse(saved.at("/after/reminderEnabled").asBoolean(true));
        assertFalse(saved.path("businessReference").has("appointmentChangeId"));
    }

    @ParameterizedTest(name = "T025-04-Q02 {0}")
    @CsvSource({"MISSING_BASIS", "WRONG_PATIENT", "STALE", "ARRIVED", "NO_SHOW", "CONTRADICTORY_VISIT"})
    void eligibilityAndLaterFactsRejectWithoutPartialWrites(String rejected) {
        var fixture = new Fixture();
        if (rejected.equals("WRONG_PATIENT")) fixture.verifiedPatient = PATIENT + 1;
        if (rejected.equals("ARRIVED")) fixture.current = fixture.current.state("已到诊", true);
        if (rejected.equals("NO_SHOW")) fixture.current = fixture.current.state("已确认未到诊", false);
        if (rejected.equals("CONTRADICTORY_VISIT")) fixture.current = fixture.current.state("待到诊", true);
        var before = fixture.current;
        var input = command(ORIGINAL, rejected.equals("MISSING_BASIS") ? " " : BASIS,
                rejected.equals("STALE") ? "SYNTHETIC-outdated" : TOKEN);
        var receipt = fixture.execute(input);
        assertEquals("REJECTED", receipt.businessOutcome());
        assertNull(receipt.successEventId());
        assertSame(before, fixture.current);
        assertEquals(0, fixture.releases);
        assertEquals(0, fixture.successes());
        assertEquals("REJECTED", fixture.memory.committed.getLast().get(10));
    }

    @ParameterizedTest(name = "T025-04-Q03 {0}")
    @CsvSource({"ROLE", "CLINIC", "ACTION", "REVOKED_IN_WORK"})
    void realSharedAuthorizationRejectsRoleScopeOrRevocation(String reason) {
        var fixture = new Fixture();
        var before = fixture.current;
        switch (reason) {
            case "ROLE" -> fixture.grant.set(new StaffAuthorization.Grant("SYNTHETIC-v1", Set.of("CLINIC_MANAGER"),
                    RECEPTION.actions(), RECEPTION.clinicIds(), false));
            case "CLINIC" -> fixture.grant.set(new StaffAuthorization.Grant("SYNTHETIC-v1", RECEPTION.roles(),
                    RECEPTION.actions(), Set.of(81002L), false));
            case "ACTION" -> fixture.grant.set(new StaffAuthorization.Grant("SYNTHETIC-v1", RECEPTION.roles(),
                    Set.of("UC03"), RECEPTION.clinicIds(), false));
            case "REVOKED_IN_WORK" -> fixture.revokeAfterStaging = true;
            default -> throw new AssertionError(reason);
        }
        var receipt = fixture.execute(command(ORIGINAL, BASIS, TOKEN));
        assertEquals("REJECTED", receipt.businessOutcome());
        assertNull(receipt.result());
        assertSame(before, fixture.current);
        assertEquals(0, fixture.releases);
        assertEquals(0, fixture.successes());
        var row = fixture.memory.committed.getLast();
        assertEquals("DENY", AuditDocuments.parse((String)row.get(8)).path("decision").asText());
    }

    @Test void sameKeyRecoveryAndDifferentPayloadLeaveOnlyOriginalSuccess() {
        var fixture = new Fixture();
        var input = command(ORIGINAL, BASIS, TOKEN);
        var first = fixture.execute(input);
        var replay = fixture.commands.execute(input, Uc04OfflineCancellationTest::unexpectedReplay);
        assertTrue(replay.existingResult());
        assertEquals(first.successEventId(), replay.successEventId());
        assertEquals(first.result(), replay.result());
        var mismatch = fixture.commands.execute(command(ORIGINAL, "SYNTHETIC-altered", TOKEN),
                Uc04OfflineCancellationTest::unexpectedReplay);
        assertEquals("REJECTED", mismatch.businessOutcome());
        assertNull(mismatch.result());
        var observation = fixture.evidence(fixture.memory.committed.getLast());
        var detail = AuditDocuments.parse(observation.path("observationReason").asText());
        assertEquals("SAME_REQUEST_DIFFERENT_PAYLOAD", detail.path("reason").asText());
        assertNotEquals(detail.path("originalPayloadHash"), detail.path("attemptedPayloadHash"));
        assertEquals(first.successEventId(), fixture.commands.recover(input, false).successEventId());
        assertEquals(1, fixture.releases);
        assertEquals(1, fixture.successes());
    }

    @Test void confirmedAlreadyCancelledReviewReturnsPriorFactWithoutStoppingReminderTwice() {
        var fixture = new Fixture();
        var original = command(ORIGINAL, BASIS, TOKEN);
        var first = fixture.execute(original);
        var prior = fixture.reviewExistingCancellation(command(OTHER, BASIS, TOKEN), original);
        assertTrue(prior.existingResult());
        assertEquals(first.successEventId(), prior.successEventId());
        assertEquals(first.result(), prior.result());
        assertEquals(first.requestReference(), prior.requestReference());
        assertFalse(fixture.current.reminderEnabled());
        assertEquals(1, fixture.releases);
        assertEquals(1, fixture.successes());
    }

    @Test void auditFailureRollsBackStagedEffectsAndFreshRequestAfterConfirmedFailureWorks() {
        var fixture = new Fixture();
        var before = fixture.current;
        var input = command(ORIGINAL, BASIS, TOKEN);
        var failed = fixture.commands.execute(input, (connection, locks) -> {
            var staged = fixture.stageCancellation(input, locks);
            fixture.failSuccessAudit = true;
            return staged;
        });
        assertEquals("FAILED", failed.businessOutcome());
        assertNull(failed.successEventId());
        assertSame(before, fixture.current);
        assertEquals(0, fixture.releases);
        assertEquals(0, fixture.successes());
        assertEquals("FAILED", fixture.commands.execute(input, Uc04OfflineCancellationTest::unexpectedReplay).businessOutcome());
        var afterReview = fixture.execute(command(OTHER, BASIS, TOKEN));
        assertEquals("SUCCEEDED", afterReview.businessOutcome());
        assertEquals(1, fixture.releases);
        assertEquals(1, fixture.successes());
    }

    @Test void unknownBeforeCommitKeepsReminderAndDoesNotPermitReplayOrAlternateKeyRecovery() {
        var fixture = new Fixture();
        var before = fixture.current;
        var input = command(ORIGINAL, BASIS, TOKEN);
        var unknown = fixture.commands.execute(input, (connection, locks) -> {
            fixture.stageCancellation(input, locks);
            throw new SQLException("SYNTHETIC-pre-commit disconnect", "08006");
        });
        assertUnknown(unknown);
        assertSame(before, fixture.current);
        assertUnknown(fixture.commands.execute(input, Uc04OfflineCancellationTest::unexpectedReplay));
        assertUnknown(fixture.commands.recover(command(OTHER, BASIS, TOKEN), false));
        assertEquals(0, fixture.releases);
        assertEquals(0, fixture.successes());
    }

    @Test void lostCommitReplyRequiresOriginalEvidenceBeforeDisclosingCancelledState() {
        var fixture = new Fixture();
        var input = command(ORIGINAL, BASIS, TOKEN);
        var unknown = fixture.commands.execute(input, (connection, locks) -> {
            var staged = fixture.stageCancellation(input, locks);
            fixture.memory.loseCommit = true;
            return staged;
        });
        assertUnknown(unknown);
        assertFalse(fixture.current.reminderEnabled());
        assertUnknown(fixture.commands.recover(command(OTHER, BASIS, TOKEN), false));
        var verified = fixture.commands.recover(input, false);
        assertEquals("SUCCEEDED", verified.businessOutcome());
        assertEquals(unknown.requestReference(), verified.requestReference());
        assertFalse(verified.result().path("reminderEnabled").asBoolean(true));
        assertEquals(1, fixture.releases);
        assertEquals(1, fixture.successes());
        fixture.current = fixture.current.state("已取消", true);
        assertUnknown(fixture.commands.recover(input, false));
    }

    private static AuditDocuments.Change unexpectedReplay(Connection c, ResourceLockCoordinator.Scope locks) {
        throw new AssertionError("Unverified cancellation replay");
    }

    private static void assertUnknown(CommandRecovery.Receipt receipt) {
        assertEquals("UNKNOWN", receipt.businessOutcome());
        assertNull(receipt.result());
        assertNull(receipt.successEventId());
    }

    private static CommandRecovery.Command command(UUID key, String basis, String token) {
        return new CommandRecovery.Command("APPOINTMENT_CANCEL", "UC04", key,
                JSON.createObjectNode().put("expectedAppointmentToken", token).put("cancellationVerification", basis),
                Set.of(CLINIC));
    }

    private record Snapshot(long appointment, long patient, ResourceLockCoordinator.Version origin, String status,
                            boolean visit, boolean occupies, boolean reminderEnabled, String cancellationBasis,
                            List<String> pastReminders, List<String> changeHistory) {
        Snapshot state(String newStatus, boolean newVisit) {
            return new Snapshot(appointment, patient, origin, newStatus, newVisit, occupies, reminderEnabled,
                    cancellationBasis, pastReminders, changeHistory);
        }
        JsonNode document() {
            var node = JSON.createObjectNode().put("appointmentId", Long.toString(appointment))
                    .put("status", status).put("reminderEnabled", reminderEnabled);
            if (cancellationBasis != null) node.put("cancellationBasis", cancellationBasis);
            return node;
        }
    }

    private static final class Fixture {
        final RecoveryRepairTest.Memory memory = new RecoveryRepairTest.Memory();
        final AtomicReference<StaffAuthorization.Grant> grant = new AtomicReference<>(RECEPTION);
        final StaffAuthorization auth = new StaffAuthorization(() -> Optional.of(ACTOR), () -> Optional.ofNullable(grant.get())
                .map(binding -> Map.of(ACTOR, binding)).orElseGet(Map::of));
        final AtomicLong ids = new AtomicLong();
        final List<String> resourceStatements = new ArrayList<>();
        Snapshot current = new Snapshot(APPOINTMENT, PATIENT, new ResourceLockCoordinator.Version(SCHEDULE, 2),
                "待到诊", false, true, true, null, List.of("SYNTHETIC-sent"), List.of("SYNTHETIC-prior-51"));
        long verifiedPatient = PATIENT;
        int releases;
        boolean revokeAfterStaging, failSuccessAudit;
        Runnable pendingWrite;
        final CommandRecovery commands = new UcAuditService(new JdbcTransactions(dataSource()), auth,
                new CommandRecovery.SuccessVerifier() {
                    public boolean matchesCommitted(Connection c, CommandRecovery.Command cmd, JsonNode saved) {
                        return !current.visit() && "已取消".equals(current.status())
                                && !current.reminderEnabled() && !current.occupies()
                                && current.document().equals(saved.path("after"))
                                && Objects.equals(current.cancellationBasis(), cmd.payload().path("cancellationVerification").asText());
                    }
                    public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command cmd, String ref) {
                        return current.visit();
                    }
                }, Clock.fixed(NOW, ZoneOffset.UTC), c -> ids.incrementAndGet()).commands();

        CommandRecovery.Receipt execute(CommandRecovery.Command input) {
            return commands.execute(input, (connection, locks) -> stageCancellation(input, locks));
        }

        AuditDocuments.Change stageCancellation(CommandRecovery.Command input, ResourceLockCoordinator.Scope locks) throws Exception {
            locks.schedules(List.of(SCHEDULE));
            locks.dentists(List.of(DENTIST));
            locks.versions(List.of(current.origin()));
            locks.appointments(List.of(APPOINTMENT));
            var before = current;
            var basis = input.payload().path("cancellationVerification").asText();
            if (!TOKEN.equals(input.payload().path("expectedAppointmentToken").asText())) {
                throw new CommandRecovery.Rejection("STALE_APPOINTMENT");
            }
            if (!BASIS.equals(basis) || before.patient() != verifiedPatient) {
                throw new CommandRecovery.Rejection("CANCELLATION_NOT_VERIFIED");
            }
            if (!"待到诊".equals(before.status()) || before.visit()) {
                throw new CommandRecovery.Rejection("CANCELLATION_NOT_COMPATIBLE");
            }
            var after = new Snapshot(before.appointment(), before.patient(), before.origin(), "已取消",
                    false, false, false, basis, before.pastReminders(), before.changeHistory());
            pendingWrite = () -> { current = after; releases++; };
            if (revokeAfterStaging) grant.set(null);
            return new AuditDocuments.Change(JSON.createObjectNode().put("appointmentId", Long.toString(APPOINTMENT)),
                    before.document(), after.document(), JSON.createObjectNode().put("synthetic", true));
        }

        long successes() { return memory.committed.stream().filter(row -> "SUCCESS".equals(row.get(10))).count(); }
        JsonNode evidence(Map<Integer,Object> audit) { return AuditDocuments.parse((String)audit.get(12)); }

        // Test-only lookup supplies the previously verified request, never a new write or a blind retry.
        CommandRecovery.Receipt reviewExistingCancellation(CommandRecovery.Command attempt, CommandRecovery.Command prior) {
            auth.require(attempt.ucCode(), attempt.clinics());
            assertNotEquals(attempt.clientKey(), prior.clientKey());
            assertEquals("已取消", current.status());
            assertFalse(current.visit());
            return commands.recover(prior, false);
        }

        DataSource dataSource() {
            return RecoveryRepairTest.proxy(DataSource.class, (p,m,a) -> m.getName().equals("getConnection")
                    ? connection() : RecoveryRepairTest.zero(m.getReturnType()));
        }

        Connection connection() {
            var delegate = memory.connection();
            return RecoveryRepairTest.proxy(Connection.class, (p,m,a) -> {
                String method = m.getName();
                if (method.equals("prepareStatement")) {
                    String sql = (String)a[0];
                    if (RESOURCE_SQL.contains(sql)) {
                        resourceStatements.add(sql);
                        return RecoveryRepairTest.proxy(PreparedStatement.class, (x,y,z) -> y.getName().equals("executeQuery")
                                ? RecoveryRepairTest.proxy(ResultSet.class, (r,n,v) -> n.getName().equals("next")
                                ? true : RecoveryRepairTest.zero(n.getReturnType()))
                                : RecoveryRepairTest.zero(y.getReturnType()));
                    }
                    if (failSuccessAudit && sql.startsWith("INSERT INTO adc.operation_audit_event")) {
                        failSuccessAudit = false;
                        throw new SQLException("SYNTHETIC-success-audit-failed", "23514");
                    }
                }
                int before = memory.commits;
                try { return m.invoke(delegate,a); }
                catch (InvocationTargetException e) { throw e.getCause(); }
                finally {
                    if (method.equals("commit") && memory.commits > before && pendingWrite != null) pendingWrite.run();
                    if (Set.of("commit","rollback","close").contains(method)) pendingWrite = null;
                }
            });
        }
    }
}
