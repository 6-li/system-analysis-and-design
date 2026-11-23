package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockMakers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Test-only oracle for Tables 6/7. No UC05/06 service, controller, writer or route is registered. */
class ArrivalNoShowTest {
    private static final long CLINIC = 81001;
    private static final long PATIENT = 88001;
    private static final long DENTIST = 79001;
    private static final long APPOINTMENT = 901;
    private static final String ACTOR = "SYNTHETIC-clinic-reception";
    private static final String BASIS = "SYNTHETIC-human-verification";
    private static final Instant END = Instant.parse("2026-11-08T09:27:00.123456Z");
    private static final StaffAuthorization.Grant RECEPTION = new StaffAuthorization.Grant("SYNTHETIC-v1",
            Set.of("CLINIC_RECEPTION"), Set.of("UC05", "UC06"), Set.of(CLINIC), false);

    enum State {
        WAITING("待到诊"), PENDING("待核实"), CANCELLED("已取消"), ARRIVED("已到诊"), NO_SHOW("已确认未到诊");
        final String label;
        State(String label) { this.label = label; }
    }
    enum Action {
        ARRIVAL("UC05"), NO_SHOW("UC06");
        final String uc;
        Action(String uc) { this.uc = uc; }
    }
    enum Outcome { RECORDED, PENDING, PRIOR, DENIED, CONFLICT }
    record Visit(long appointment, long patient, long clinic, long dentist) {}
    record Facts(State state, Visit visit, String basis) {}
    record Request(String key, Action action, boolean verified, String basis, Instant observedAt) {}
    record Result(Outcome outcome, Facts facts) {}
    record Evidence(String key, String uc, String actor, long clinic, long appointment, Instant at,
                    String basis, Outcome outcome) {}

    private static Request verified(String key, Action action, Instant when) {
        return new Request(key, action, true, BASIS, when);
    }

    private static Facts seed(State state) {
        return new Facts(state, state == State.ARRIVED ? new Visit(APPOINTMENT, PATIENT, CLINIC, DENTIST) : null,
                switch (state) {
                    case WAITING -> null;
                    case PENDING, CANCELLED, ARRIVED, NO_SHOW -> "SYNTHETIC-prior-" + state;
                });
    }

    // This models only reviewed assertions; no automatic result is created from an elapsed clock.
    private static Result expectedDecision(Facts before, Request request, Instant end) {
        var state = before.state();
        if ((before.visit() != null) != (state == State.ARRIVED)) return new Result(Outcome.CONFLICT, before);
        if (state == State.CANCELLED) return new Result(Outcome.DENIED, before);
        if (state == State.ARRIVED || state == State.NO_SHOW) {
            var originalAction = state == State.ARRIVED ? Action.ARRIVAL : Action.NO_SHOW;
            return new Result(originalAction == request.action() ? Outcome.PRIOR : Outcome.CONFLICT, before);
        }
        if (request.basis() == null || request.basis().isBlank()) return new Result(Outcome.DENIED, before);
        if (!request.verified()) return new Result(Outcome.PENDING, new Facts(State.PENDING, null, request.basis()));
        if (request.action() == Action.NO_SHOW && request.observedAt().isBefore(end)) {
            return new Result(Outcome.DENIED, before);
        }
        var facts = request.action() == Action.ARRIVAL
                ? new Facts(State.ARRIVED, new Visit(APPOINTMENT, PATIENT, CLINIC, DENTIST), request.basis())
                : new Facts(State.NO_SHOW, null, request.basis());
        return new Result(Outcome.RECORDED, facts);
    }

    @ParameterizedTest(name = "T026-S01 {0}/{1}")
    @CsvSource({
            "WAITING,ARRIVAL,RECORDED,ARRIVED,true", "WAITING,NO_SHOW,RECORDED,NO_SHOW,false",
            "PENDING,ARRIVAL,RECORDED,ARRIVED,true", "PENDING,NO_SHOW,RECORDED,NO_SHOW,false",
            "CANCELLED,ARRIVAL,DENIED,CANCELLED,false", "CANCELLED,NO_SHOW,DENIED,CANCELLED,false",
            "ARRIVED,ARRIVAL,PRIOR,ARRIVED,true", "ARRIVED,NO_SHOW,CONFLICT,ARRIVED,true",
            "NO_SHOW,ARRIVAL,CONFLICT,NO_SHOW,false", "NO_SHOW,NO_SHOW,PRIOR,NO_SHOW,false"
    })
    void allFiveReportStatesKeepTheirOwnFacts(State state, Action action, Outcome outcome, State resultState, boolean visit) {
        var before = seed(state);
        var decision = expectedDecision(before, verified("SYNTHETIC-state", action, END.plusNanos(1_000)), END);
        assertEquals(outcome, decision.outcome());
        assertEquals(resultState, decision.facts().state());
        assertEquals(visit, decision.facts().visit() != null);
        if (outcome != Outcome.RECORDED) assertSame(before, decision.facts());
        else assertEquals(BASIS, decision.facts().basis());
        if (state == State.WAITING && action == Action.ARRIVAL) {
            assertEquals(new Visit(APPOINTMENT, PATIENT, CLINIC, DENTIST), decision.facts().visit());
        }
    }

    @ParameterizedTest(name = "T026-S02 {0}")
    @CsvSource({"ARRIVAL", "NO_SHOW"})
    void humanEvidenceAndPendingAreDifferent(Action action) {
        var before = seed(State.WAITING);
        var noBasis = new Request("SYNTHETIC-missing", action, true, " ", END);
        var denied = expectedDecision(before, noBasis, END);
        assertEquals(new Result(Outcome.DENIED, before), denied);
        var pending = new Request("SYNTHETIC-pending", action, false, BASIS, END.plusNanos(1_000));
        assertEquals(new Result(Outcome.PENDING, new Facts(State.PENDING, null, BASIS)),
                expectedDecision(before, pending, END));
        var confirmed = seed(State.ARRIVED);
        assertSame(confirmed, expectedDecision(confirmed, pending, END).facts());
    }

    @ParameterizedTest(name = "T026-S03 {0}us")
    @CsvSource({"-1,DENIED", "0,RECORDED", "1,RECORDED"})
    void noShowEndUsesAbsoluteInstantEvenAcrossClinicDate(long microseconds, Outcome expected) {
        var start = OffsetDateTime.parse("2026-11-08T23:40:00+08:00");
        var end = OffsetDateTime.parse("2026-11-09T00:07:00.123456+08:00");
        assertNotEquals(start.toLocalDate(), end.toLocalDate());
        assertEquals(Instant.parse("2026-11-08T16:07:00.123456Z"), end.toInstant());
        var at = end.toInstant().plusNanos(microseconds * 1_000);
        assertEquals(expected, expectedDecision(seed(State.WAITING), verified("SYNTHETIC-end", Action.NO_SHOW, at),
                end.toInstant()).outcome());
        assertNotEquals(Outcome.RECORDED, expectedDecision(seed(State.WAITING),
                new Request("SYNTHETIC-clock-only", Action.NO_SHOW, false, null, at), end.toInstant()).outcome());
    }

    @Test void cancelledWithAnOppositeVisitCannotBeNormalizedOrOverwritten() {
        var contradiction = new Facts(State.CANCELLED, new Visit(APPOINTMENT, PATIENT, CLINIC, DENTIST),
                "SYNTHETIC-previous-cancellation");
        for (var action : Action.values()) {
            assertEquals(new Result(Outcome.CONFLICT, contradiction),
                    expectedDecision(contradiction, verified("SYNTHETIC-contradiction", action, END), END));
        }
    }

    @ParameterizedTest(name = "T026-S05 {0}")
    @CsvSource({"MISSING_ROLE", "MISSING_CLINIC", "OTHER_CLINIC", "MISSING_ACTION", "MISSING_IDENTITY"})
    void sharedAuthorizationRejectsUntrustedReadsAndWritesBeforeEvidenceIsDisclosed(String reason) {
        var binding = switch (reason) {
            case "MISSING_ROLE" -> new StaffAuthorization.Grant("SYNTHETIC-v1", Set.of("BOOKING_RECEPTION"),
                    RECEPTION.actions(), RECEPTION.clinicIds(), false);
            case "MISSING_CLINIC" -> new StaffAuthorization.Grant("SYNTHETIC-v1", RECEPTION.roles(),
                    RECEPTION.actions(), Set.of(), false);
            case "OTHER_CLINIC" -> new StaffAuthorization.Grant("SYNTHETIC-v1", RECEPTION.roles(),
                    RECEPTION.actions(), Set.of(81002L), false);
            case "MISSING_ACTION" -> new StaffAuthorization.Grant("SYNTHETIC-v1", RECEPTION.roles(),
                    Set.of(), RECEPTION.clinicIds(), false);
            case "MISSING_IDENTITY" -> RECEPTION;
            default -> throw new AssertionError(reason);
        };
        var auth = new StaffAuthorization(() -> reason.equals("MISSING_IDENTITY") ? Optional.empty() : Optional.of(ACTOR),
                () -> Map.of(ACTOR, binding));
        var harness = new Journal(seed(State.WAITING), auth);
        for (var action : Action.values()) {
            assertThrows(SecurityException.class, () -> harness.read(action));
            assertThrows(SecurityException.class, () -> harness.submit(verified("SYNTHETIC-denied", action, END)));
        }
        assertEquals(seed(State.WAITING), harness.facts);
        assertTrue(harness.events.isEmpty());
    }

    @ParameterizedTest(name = "T026-S06 {0}")
    @CsvSource({"ARRIVAL", "NO_SHOW"})
    void duplicateKeysAndRepeatedFactsDoNotReplaceOriginalEvidence(Action action) {
        var harness = new Journal(seed(State.WAITING), auth(new AtomicReference<>(RECEPTION)));
        var first = verified("SYNTHETIC-original", action, END);
        var committed = harness.submit(first);
        assertEquals(Outcome.RECORDED, committed.outcome());
        assertSame(committed, harness.submit(first));
        var repeated = verified("SYNTHETIC-other-key", action, END.plusNanos(1_000));
        assertEquals(new Result(Outcome.PRIOR, committed.facts()), harness.submit(repeated));
        assertEquals(List.of(Outcome.RECORDED, Outcome.PRIOR, Outcome.PRIOR),
                harness.events.stream().map(Evidence::outcome).toList());
        assertEquals(List.of(first.key(), first.key(), repeated.key()), harness.events.stream().map(Evidence::key).toList());
        var evidence = harness.events.getFirst();
        assertEquals(new Evidence(first.key(), action.uc, ACTOR, CLINIC, APPOINTMENT, END, BASIS, Outcome.RECORDED),
                evidence);
        assertSame(committed.facts(), harness.facts);
    }

    @Test void repeatedPendingAndPriorCancellationNeverCountAsConfirmedNoShow() {
        var pending = new Request("SYNTHETIC-pending", Action.NO_SHOW, false, BASIS, END);
        var pendingJournal = new Journal(seed(State.WAITING), auth(new AtomicReference<>(RECEPTION)));
        assertEquals(Outcome.PENDING, pendingJournal.submit(pending).outcome());
        assertEquals(Outcome.PENDING, pendingJournal.submit(pending).outcome());
        assertEquals(State.PENDING, pendingJournal.facts.state());
        var cancelled = new Journal(seed(State.CANCELLED), auth(new AtomicReference<>(RECEPTION)));
        var request = verified("SYNTHETIC-already-cancelled", Action.NO_SHOW, END);
        assertEquals(Outcome.DENIED, cancelled.submit(request).outcome());
        assertEquals(Outcome.DENIED, cancelled.submit(request).outcome());
        assertEquals(seed(State.CANCELLED), cancelled.facts);
    }

    @ParameterizedTest(name = "T026-S07 {0}/{1}")
    @CsvSource({"ARRIVAL,REVOKED", "NO_SHOW,REVOKED", "ARRIVAL,AUDIT_FAILURE", "NO_SHOW,AUDIT_FAILURE",
            "ARRIVAL,COMMIT_UNKNOWN", "NO_SHOW,COMMIT_UNKNOWN"})
    void sharedJdbcBoundaryDoesNotPublishStagedSuccessOnError(Action action, String failure) throws Exception {
        var source = proxy(DataSource.class);
        var connection = proxy(Connection.class);
        var statement = proxy(Statement.class);
        var commandLock = proxy(PreparedStatement.class);
        var appointmentLock = proxy(PreparedStatement.class);
        var lockRow = proxy(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return switch (sql) {
                case "SELECT pg_advisory_xact_lock(41001,?)" -> commandLock;
                case "SELECT appointment_id FROM adc.appointment WHERE appointment_id=? FOR UPDATE" -> appointmentLock;
                default -> throw new AssertionError("Unexpected offline SQL: " + sql);
            };
        });
        when(appointmentLock.executeQuery()).thenReturn(lockRow);
        when(lockRow.next()).thenReturn(true);
        if (failure.equals("COMMIT_UNKNOWN")) doThrow(new SQLException("SYNTHETIC-response-lost", "08006")).when(connection).commit();

        var grant = new AtomicReference<>(RECEPTION);
        var harness = new Journal(seed(State.WAITING), auth(grant));
        var before = harness.facts;
        var request = verified("SYNTHETIC-failure", action, END);
        var tx = new JdbcTransactions(source);
        var work = (org.junit.jupiter.api.function.Executable) () -> {
            var result = tx.execute(c -> {
                var locks = new ResourceLockCoordinator().scope(c);
                locks.command(request.key());
                locks.appointments(List.of(APPOINTMENT));
                var staged = expectedDecision(harness.read(action), request, END);
                if (failure.equals("REVOKED")) grant.set(null);
                harness.auth.require(action.uc, Set.of(CLINIC));
                if (failure.equals("AUDIT_FAILURE")) throw new SQLException("SYNTHETIC-audit-failed", "23514");
                return staged;
            });
            harness.facts = result.facts();
        };
        if (failure.equals("REVOKED")) assertThrows(SecurityException.class, work);
        else {
            var error = assertThrows(JdbcTransactions.Failure.class, work);
            assertEquals(failure.equals("COMMIT_UNKNOWN"), error.unknown());
            assertEquals(failure.equals("COMMIT_UNKNOWN"), error.commitAttempted());
        }
        var order = inOrder(commandLock, appointmentLock, connection);
        order.verify(commandLock).execute();
        order.verify(appointmentLock).setLong(1, APPOINTMENT);
        order.verify(appointmentLock).executeQuery();
        if (failure.equals("COMMIT_UNKNOWN")) order.verify(connection).commit();
        else verify(connection, never()).commit();
        order.verify(connection).rollback();
        assertSame(before, harness.facts);
        assertTrue(harness.events.isEmpty());
    }

    @ParameterizedTest(name = "T026-S08 {0}/{1}")
    @CsvSource({"ARRIVAL,CANCELLED,DENIED", "NO_SHOW,CANCELLED,DENIED", "ARRIVAL,NO_SHOW,CONFLICT", "NO_SHOW,ARRIVED,CONFLICT"})
    void serializedModelUsesLaterFactInsteadOfStalePreLockSnapshot(Action action, State intervening, Outcome expected) {
        var request = verified("SYNTHETIC-race", action, END);
        var beforeLock = seed(State.WAITING);
        var harness = new Journal(beforeLock, auth(new AtomicReference<>(RECEPTION)));
        assertEquals(Outcome.RECORDED, expectedDecision(harness.read(action), request, END).outcome());
        // Test-only interleaving fixture: another command committed before the simulated lock was acquired.
        var afterLock = seed(intervening);
        harness.facts = afterLock;
        assertEquals(new Result(expected, afterLock), harness.submit(request));
        assertSame(afterLock, harness.facts);
        assertEquals(expected, harness.events.getFirst().outcome());
    }

    private static StaffAuthorization auth(AtomicReference<StaffAuthorization.Grant> grant) {
        return new StaffAuthorization(() -> Optional.of(ACTOR), () -> {
            var current = grant.get();
            return current == null ? Map.of() : Map.of(ACTOR, current);
        });
    }

    private static <T> T proxy(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.PROXY));
    }

    private static final class Journal {
        private final StaffAuthorization auth;
        private final Map<Request, Result> receipts = new HashMap<>();
        private final ArrayList<Evidence> events = new ArrayList<>();
        private Facts facts;

        Journal(Facts facts, StaffAuthorization auth) { this.facts = facts; this.auth = auth; }
        Facts read(Action action) { auth.require(action.uc, Set.of(CLINIC)); return facts; }
        Result submit(Request request) {
            var before = read(request.action());
            var prior = receipts.get(request);
            if (prior != null) {
                evidence(request, Outcome.PRIOR);
                return prior;
            }
            var result = expectedDecision(before, request, END);
            facts = result.facts();
            receipts.put(request, result);
            evidence(request, result.outcome());
            return result;
        }
        private void evidence(Request request, Outcome outcome) {
            events.add(new Evidence(request.key(), request.action().uc, ACTOR, CLINIC, APPOINTMENT, request.observedAt(),
                    request.basis(), outcome));
        }
    }
}
