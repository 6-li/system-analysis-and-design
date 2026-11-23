package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.CommandRecovery.Command;
import cn.adc.shared.audit.CommandRecovery.Receipt;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator.Scope;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static cn.adc.appointments.InitialBookingReview.id;
import static cn.adc.shared.audit.AuditDocuments.JSON;
import static cn.adc.shared.audit.AuditDocuments.text;

final class AppointmentCancellationService implements CommandRecovery.SuccessVerifier {
    static final String OPERATION = "APPOINTMENT_CANCEL";
    private static final String USE_CASE = "UC04";
    private static final String ALREADY_CANCELLED = "APPOINTMENT_ALREADY_CANCELLED";

    @FunctionalInterface
    interface Verification {
        Optional<String> verify(Connection connection, AppointmentRepository.Appointment appointment,
                                String verification) throws SQLException;
    }

    interface MiniSource {
        void requireCurrent(Connection c, String source, AppointmentRepository.Appointment original,
                            String verifiedCancellation) throws SQLException;
        void linkResult(Connection c, String source, AppointmentRepository.Appointment cancelled,
                        String requestReference) throws SQLException;
        boolean matchesCommitted(Connection c, String source, long appointmentId, String requestReference)
                throws SQLException;
    }

    private final JdbcTransactions transactions;
    private final StaffAuthorization authorization;
    private final AppointmentRepository repository;
    private final Verification verification;
    private final Clock clock;
    private final MiniSource miniSource;
    private final AuditStore audit = new AuditStore(AuditStore.Family.UC);
    private final CommandRecovery commands;

    AppointmentCancellationService(JdbcTransactions transactions, StaffAuthorization authorization,
                                   Verification verification, AuditStore.EventIdAllocator auditIds, Clock clock,
                                   AppointmentRepository repository) {
        this(transactions, authorization, verification, auditIds, clock, repository, null);
    }

    AppointmentCancellationService(JdbcTransactions transactions, StaffAuthorization authorization,
                                   Verification verification, AuditStore.EventIdAllocator auditIds, Clock clock,
                                   AppointmentRepository repository, MiniSource miniSource) {
        this.transactions = transactions;
        this.authorization = authorization;
        this.verification = verification;
        this.clock = clock;
        this.repository = repository;
        this.miniSource = miniSource;
        commands = new UcAuditService(transactions, authorization, this, clock, Objects.requireNonNull(auditIds)).commands();
    }

    Receipt cancel(long appointmentId, long clinic, UUID key, JsonNode input) {
        var command = command(appointmentId, clinic, key, input);
        var actor = authorization.evaluate(USE_CASE, command.clinics()).actor();
        var receipt = commands.execute(command, (c, locks) -> apply(c, locks, command, actor));
        return priorCancellation(command, actor, receipt);
    }

    Receipt recover(long appointmentId, long clinic, UUID key, JsonNode input) {
        var command = command(appointmentId, clinic, key, input);
        var actor = authorization.evaluate(USE_CASE, command.clinics()).actor();
        return priorCancellation(command, actor, commands.recover(command, false));
    }

    static Command command(long appointmentId, long clinic, UUID key, JsonNode input) {
        if (input == null || !input.isObject()) throw new Rejection("INVALID_CANCELLATION_INPUT");
        var payload = JSON.createObjectNode().put("appointmentId", Long.toString(appointmentId));
        payload.set("input", input);
        return new Command(OPERATION, USE_CASE, key, payload, Set.of(clinic));
    }

    AuditDocuments.Change apply(Connection c, Scope locks, Command command, String actor) throws SQLException {
        var reference = reference(actor, command.clientKey());
        locks.requireCommand(c, reference);
        requireActor(command, actor);
        var input = command.payload().path("input");
        var source = input.has("sourceContextReference") ? text(input, "sourceContextReference") : null;
        if (source != null && miniSource == null) throw new Rejection("VERIFIED_MINIPROGRAM_CANCELLATION_UNAVAILABLE");
        var expectedToken = text(input, "expectedAppointmentToken");
        var submittedVerification = text(input, "cancellationVerification");
        long appointmentId = id(command.payload(), "appointmentId");
        long clinic = clinic(command);
        var firstVersion = repository.findOrigin(c, appointmentId, clinic)
                .orElseThrow(() -> new Rejection("APPOINTMENT_NOT_FOUND"));
        var firstSchedule = InitialBookingReview.schedule(c, firstVersion);
        if (firstSchedule.clinic() != clinic) throw new SecurityException("SCHEDULE_SCOPE_DENIED");

        locks.schedules(List.of(firstVersion.scheduleId()));
        locks.dentists(List.of(firstSchedule.dentist()));
        locks.versions(List.of(firstVersion));
        locks.appointments(List.of(appointmentId));
        requireActor(command, actor);

        var before = repository.findAppointment(c, appointmentId, clinic)
                .orElseThrow(() -> new Rejection("STALE_APPOINTMENT"));
        var lockedSchedule = InitialBookingReview.schedule(c, firstVersion);
        if (!before.terms().schedule().equals(firstVersion) || lockedSchedule.clinic() != clinic
                || lockedSchedule.dentist() != firstSchedule.dentist()) throw new Rejection("STALE_APPOINTMENT");
        if ("已取消".equals(before.status())) throw new Rejection(ALREADY_CANCELLED);
        if (!AppointmentReadService.token(before).equals(expectedToken))
            throw new Rejection("STALE_APPOINTMENT");
        if (!"待到诊".equals(before.status()) || before.cancellation() != null || before.noShow() != null
                || repository.hasVisit(c, before)) throw new Rejection("APPOINTMENT_NOT_CANCELLABLE");

        var verified = verification.verify(c, before, submittedVerification)
                .filter(value -> !value.isBlank()).orElseThrow(() -> new Rejection("CANCELLATION_VERIFICATION_REQUIRED"));
        if (source != null) miniSource.requireCurrent(c, source, before, verified);
        requireActor(command, actor);
        var stoppedReminder = JSON.createObjectNode().put("schemaVersion", 1).put("state", "STOPPED")
                .put("previousArrangement", before.terms().reminder()).put("requestReference", reference).toString();
        var basis = JSON.createObjectNode().put("schemaVersion", 1).put("verificationReference", verified)
                .put("requestReference", reference).toString();
        var old = before.terms();
        var terms = new AppointmentRepository.Terms(old.schedule(), old.startsAt(), old.endsAt(), old.treatmentRequest(), stoppedReminder);
        var after = new AppointmentRepository.Appointment(before.id(), before.patientId(), terms, before.acceptance(),
                "已取消", null, basis, actor, StrictInputPolicy.eventTime(clock), before.representative(), before.pendingArrival());
        repository.updateAppointment(c, before, after);
        if (source != null) miniSource.linkResult(c, source, after, reference);
        var result = result(appointmentId);
        var facts = JSON.createObjectNode().put("appointmentFingerprint", AppointmentReadService.token(after))
                .put("requestReference", reference);
        return new AuditDocuments.Change(result, null, result, facts);
    }

    private Receipt priorCancellation(Command command, String actor, Receipt receipt) {
        if (!ALREADY_CANCELLED.equals(receipt.reason())) return receipt;
        if (command.payload().path("input").has("sourceContextReference")) return receipt;
        try {
            return transactions.execute(c -> {
                requireActor(command, actor);
                long appointmentId = id(command.payload(), "appointmentId");
                var row = repository.findAppointment(c, appointmentId, clinic(command));
                var prior = row.isEmpty() ? Optional.<AuditStore.Event>empty() : cancelledEvent(c, row.orElseThrow());
                requireActor(command, actor);
                return prior.map(event -> new Receipt(receipt.requestReference(), "SUCCEEDED", receipt.auditPersistence(),
                                result(appointmentId), true, event.id(), null))
                        .orElseGet(() -> pending(receipt));
            });
        } catch (SecurityException e) {
            return new Receipt(receipt.requestReference(), "REJECTED", receipt.auditPersistence(),
                    null, false, null, "EXECUTION_PERMISSION_DENIED");
        } catch (JdbcTransactions.Failure e) {
            return pending(receipt);
        }
    }

    private static Receipt pending(Receipt receipt) {
        return new Receipt(receipt.requestReference(), "UNKNOWN", receipt.auditPersistence(), null, false);
    }

    // Capacity release depends on the committed cancellation, not on a status label or a filled field.
    boolean confirmsRelease(Connection c, AppointmentRepository.Overlap overlap) throws SQLException {
        return confirmsCommittedRelease(c,overlap);
    }
    static boolean confirmsCommittedRelease(Connection c,AppointmentRepository.Overlap overlap)throws SQLException {
        var repository=new AppointmentRepository();
        var row = repository.findAppointment(c, overlap.appointmentId(), overlap.clinicId());
        return row.isPresent() && cancelledEvent(c, row.orElseThrow()).isPresent();
    }

    private static Optional<AuditStore.Event> cancelledEvent(Connection c, AppointmentRepository.Appointment row) throws SQLException {
        var repository=new AppointmentRepository();var audit=new AuditStore(AuditStore.Family.UC);
        var ref = cancellationReference(row);
        if (ref.isEmpty() || CommittedBookingRelease.hasVisit(c,row.id())) return Optional.empty();
        var events = audit.read(c, ref.orElseThrow()).stream()
                .filter(event -> "TERMINAL".equals(event.kind())).toList();
        if (events.size() != 1) return Optional.empty();
        var event = events.getFirst();
        if (!OPERATION.equals(event.operation()) || !"SUCCEEDED".equals(event.outcome())
                || !Objects.equals(row.actor(), event.actor()) || !"YES".equals(event.evidence().path("confirmedCommit").asText())
                || !matchesSnapshot(row, event.evidence().path("businessResultSnapshot"), ref.orElseThrow()))
            return Optional.empty();
        return Optional.of(event);
    }

    @Override
    public boolean matchesCommitted(Connection c, Command command, JsonNode saved) throws SQLException {
        if (!OPERATION.equals(command.operation()) || !USE_CASE.equals(command.ucCode())) return false;
        long appointmentId = id(command.payload(), "appointmentId");
        var row = repository.findAppointment(c, appointmentId, clinic(command));
        if (row.isEmpty()) return false;
        var appointment = row.orElseThrow();
        var ref = reference(appointment.actor(), command.clientKey());
        if (!matchesSnapshot(appointment, saved, ref) || repository.hasVisit(c, appointment)) return false;
        var input = command.payload().path("input");
        return !input.has("sourceContextReference") || (miniSource != null && miniSource.matchesCommitted(c,
                text(input, "sourceContextReference"), appointmentId, ref));
    }

    static boolean matchesSnapshot(AppointmentRepository.Appointment row, JsonNode saved, String reference) {
        var facts = saved.path("verification");
        return result(row.id()).equals(saved.path("after")) && result(row.id()).equals(saved.path("businessReference"))
                && reference.equals(facts.path("requestReference").asText())
                && cancellationReference(row).filter(reference::equals).isPresent()
                && AppointmentReadService.token(row).equals(facts.path("appointmentFingerprint").asText());
    }

    static Optional<String> cancellationReference(AppointmentRepository.Appointment row) {
        if (!"已取消".equals(row.status()) || row.noShow() != null || row.cancellation() == null) return Optional.empty();
        try {
            var basis = AuditDocuments.parse(row.cancellation());
            var reminder = AuditDocuments.parse(row.terms().reminder());
            var ref = text(basis, "requestReference");
            if (!ref.startsWith("UC/v1/UC04/") || basis.path("schemaVersion").asInt() != 1
                    || !"STOPPED".equals(reminder.path("state").asText()) || reminder.path("schemaVersion").asInt() != 1
                    || !ref.equals(reminder.path("requestReference").asText())) return Optional.empty();
            text(basis, "verificationReference");
            return Optional.of(ref);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean hasContradictorySuccess(Connection c, Command command, String requestReference) {
        return true;
    }

    private static long clinic(Command command) {
        return command.clinics().iterator().next();
    }

    private static JsonNode result(long appointmentId) {
        return JSON.createObjectNode().put("appointmentId", Long.toString(appointmentId));
    }

    private void requireActor(Command command, String actor) {
        if (!actor.equals(authorization.require(USE_CASE, command.clinics()).actor()))
            throw new SecurityException("REQUEST_ACTOR_CHANGED");
    }

    static String reference(String actor, UUID key) {
        return AuditDocuments.requestReference(actor, "UC", OPERATION, key).replace("/v1/", "/v1/" + USE_CASE + "/");
    }
}
