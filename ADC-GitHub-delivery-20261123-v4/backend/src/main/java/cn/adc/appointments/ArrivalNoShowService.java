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
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static cn.adc.appointments.InitialBookingReview.id;
import static cn.adc.shared.audit.AuditDocuments.JSON;
import static cn.adc.shared.audit.AuditDocuments.text;

final class ArrivalNoShowService implements CommandRecovery.SuccessVerifier {
    enum Action {
        ARRIVAL("UC05", "ARRIVAL_VERIFY", "ARRIVAL_VERIFIED", "已到诊"),
        NO_SHOW("UC06", "NO_SHOW_VERIFY", "NO_SHOW_VERIFIED", "已确认未到诊");

        final String uc;
        final String operation;
        final String finding;
        final String status;

        Action(String uc, String operation, String finding, String status) {
            this.uc = uc;
            this.operation = operation;
            this.finding = finding;
            this.status = status;
        }
    }

    private static final String PENDING = "PENDING_VERIFICATION";
    private static final String PRIOR = "ATTENDANCE_ALREADY_CONFIRMED";

    record Finding(Action action, String finding, String basis, String identityReference,
                   long clinic, Long actualDentist) {}
    record Verified(String reference, Instant arrivedAt) {}

    @FunctionalInterface
    interface Verification {
        /** Verify the supplied human finding against this appointment and the actual clinic/dentist. */
        Optional<Verified> verify(Connection c, AppointmentRepository.Appointment appointment, Finding finding) throws SQLException;
    }

    private final JdbcTransactions transactions;
    private final StaffAuthorization authorization;
    private final AppointmentRepository repository;
    private final Verification verification;
    private final FormalBookingService.AppointmentIds visitIds;
    private final Clock clock;
    private final AuditStore audit = new AuditStore(AuditStore.Family.UC);
    private final CommandRecovery commands;

    ArrivalNoShowService(JdbcTransactions transactions, StaffAuthorization authorization,
                         AppointmentRepository repository, Verification verification,
                         FormalBookingService.AppointmentIds visitIds, AuditStore.EventIdAllocator auditIds, Clock clock) {
        this.transactions = transactions;
        this.authorization = authorization;
        this.repository = repository;
        this.verification = verification;
        this.visitIds = visitIds;
        this.clock = clock;
        commands = new UcAuditService(transactions, authorization, this, clock, Objects.requireNonNull(auditIds)).commands();
    }

    Receipt submit(Action action, long appointmentId, long clinic, UUID key, JsonNode input) {
        var command = command(action, appointmentId, clinic, key, input);
        var actor = authorization.evaluate(action.uc, command.clinics()).actor();
        return prior(action, command, actor, commands.execute(command, (c, locks) -> apply(c, locks, action, command, actor)));
    }

    Receipt recover(Action action, long appointmentId, long clinic, UUID key, JsonNode input) {
        var command = command(action, appointmentId, clinic, key, input);
        var actor = authorization.evaluate(action.uc, command.clinics()).actor();
        return prior(action, command, actor, commands.recover(command, false));
    }

    static Command command(Action action, long appointmentId, long clinic, UUID key, JsonNode input) {
        if (input == null || !input.isObject()) throw new Rejection("INVALID_ATTENDANCE_INPUT");
        var payload = JSON.createObjectNode().put("appointmentId", Long.toString(appointmentId));
        payload.set("input", input);
        return new Command(action.operation, action.uc, key, payload, Set.of(clinic));
    }

    AuditDocuments.Change apply(Connection c, Scope locks, Action action, Command command, String actor) throws SQLException {
        String reference = reference(action, actor, command.clientKey());
        locks.requireCommand(c, reference);
        requireActor(action, command, actor);
        long appointmentId = id(command.payload(), "appointmentId");
        long clinic = clinic(command);
        var input = command.payload().path("input");
        var token = text(input, "expectedAppointmentToken");
        var finding = finding(action, input, clinic);
        var version = repository.findOrigin(c, appointmentId, clinic)
                .orElseThrow(() -> new Rejection("APPOINTMENT_NOT_FOUND"));
        var firstSchedule = InitialBookingReview.schedule(c, version);
        if (firstSchedule.clinic() != clinic) throw new SecurityException("SCHEDULE_SCOPE_DENIED");
        locks.schedules(List.of(version.scheduleId()));
        locks.dentists(finding.actualDentist() == null ? List.of(firstSchedule.dentist())
                : List.of(firstSchedule.dentist(), finding.actualDentist()));
        locks.versions(List.of(version));
        locks.appointments(List.of(appointmentId));

        requireActor(action, command, actor);
        var before = repository.findAppointment(c, appointmentId, clinic)
                .orElseThrow(() -> new Rejection("STALE_APPOINTMENT"));
        var lockedSchedule = InitialBookingReview.schedule(c, version);
        if (!version.equals(before.terms().schedule()) || lockedSchedule.clinic() != clinic
                || lockedSchedule.dentist() != firstSchedule.dentist()) throw new Rejection("STALE_APPOINTMENT");
        var existingVisit = repository.findVisit(c, before);
        if (action.status.equals(before.status()) && action.finding.equals(finding.finding())) throw new Rejection(PRIOR);
        if ((!Set.of("待到诊", "待核实").contains(before.status())) || before.cancellation() != null
                || before.noShow() != null || existingVisit.isPresent()) throw new Rejection("ATTENDANCE_FACT_CONFLICT");
        if (!AppointmentReadService.token(before).equals(token)) throw new Rejection("STALE_APPOINTMENT");

        boolean pending = PENDING.equals(finding.finding());
        var now = StrictInputPolicy.eventTime(clock);
        if (!pending && action == Action.NO_SHOW && now.isBefore(before.terms().endsAt())) {
            throw new Rejection("APPOINTMENT_NOT_ENDED");
        }
        var verified = verification.verify(c, before, finding)
                .filter(v -> v.reference() != null && !v.reference().isBlank())
                .orElseThrow(() -> new Rejection("ATTENDANCE_VERIFICATION_REQUIRED"));
        requireActor(action, command, actor);
        var basis = JSON.createObjectNode().put("schemaVersion", 1).put("finding", finding.finding())
                .put("verificationReference", verified.reference()).put("requestReference", reference).toString();
        var after = new AppointmentRepository.Appointment(before.id(), before.patientId(), before.terms(), before.acceptance(),
                pending ? "待核实" : action.status, !pending && action == Action.NO_SHOW ? basis : null, null,
                actor, now, before.representative(), pending ? basis : before.pendingArrival());

        AppointmentRepository.Visit visit = null;
        if (!pending && action == Action.ARRIVAL) {
            long visitId = visitIds.next(c);
            if (visitId <= 0) throw new SQLException("VISIT_ID_UNAVAILABLE", "22003");
            var arrivedAt = verified.arrivedAt();
            if (arrivedAt != null && (arrivedAt.isAfter(now) || arrivedAt.getNano() % 1000 != 0)) {
                throw new Rejection("INVALID_VERIFIED_ARRIVAL_TIME");
            }
            visit = new AppointmentRepository.Visit(visitId, appointmentId, clinic, finding.actualDentist(),
                    now, basis, actor, arrivedAt);
        }
        repository.updateAppointment(c, before, after);
        if (visit != null) repository.insertVisit(c, after, visit);
        var result = result(after.id(), finding.finding(), visit);
        var facts = JSON.createObjectNode().put("appointmentFingerprint", AppointmentReadService.token(after))
                .put("requestReference", reference).put("visitFingerprint", visitFingerprint(visit));
        return new AuditDocuments.Change(result, null, result, facts);
    }

    private static Finding finding(Action action, JsonNode input, long clinic) {
        var value = text(input, "finding");
        if (!PENDING.equals(value) && !action.finding.equals(value)) throw new Rejection("INVALID_ATTENDANCE_FINDING");
        if (Action.ARRIVAL == action && action.finding.equals(value)) {
            if (id(input, "actualClinicId") != clinic) throw new SecurityException("ACTUAL_CLINIC_SCOPE_DENIED");
            return new Finding(action, value, text(input, "verificationBasis"), text(input, "verifiedIdentityReference"),
                    clinic, id(input, "actualDentistId"));
        }
        return new Finding(action, value, text(input, "verificationBasis"), null, clinic, null);
    }

    private Receipt prior(Action action, Command command, String actor, Receipt receipt) {
        if (!PRIOR.equals(receipt.reason())) return receipt;
        try {
            return transactions.execute(c -> {
                requireActor(action, command, actor);
                var row = repository.findAppointment(c, id(command.payload(), "appointmentId"), clinic(command));
                if (row.isEmpty()) return pending(receipt);
                var visit = repository.findVisit(c, row.orElseThrow()).orElse(null);
                var ref = storedReference(action, action.finding, row.orElseThrow(), visit);
                if (ref.isEmpty()) return pending(receipt);
                var events = audit.read(c, ref.orElseThrow()).stream().filter(e -> "TERMINAL".equals(e.kind())).toList();
                requireActor(action, command, actor);
                if (events.size() != 1) return pending(receipt);
                var event = events.getFirst();
                var saved = event.evidence().path("businessResultSnapshot");
                if (!action.operation.equals(event.operation()) || !"SUCCEEDED".equals(event.outcome())
                        || !row.orElseThrow().actor().equals(event.actor())
                        || !"YES".equals(event.evidence().path("confirmedCommit").asText())
                        || !matchesSnapshot(action, row.orElseThrow(), visit, saved, ref.orElseThrow())) return pending(receipt);
                return new Receipt(receipt.requestReference(),"SUCCEEDED",receipt.auditPersistence(),
                        saved.path("after"),true,event.id(),null);
            });
        } catch (SecurityException e) {
            return new Receipt(receipt.requestReference(),"REJECTED",receipt.auditPersistence(),null,false,null,"EXECUTION_PERMISSION_DENIED");
        } catch (JdbcTransactions.Failure e) {
            return pending(receipt);
        }
    }

    private static Receipt pending(Receipt receipt) {
        return new Receipt(receipt.requestReference(),"UNKNOWN",receipt.auditPersistence(),null,false);
    }

    @Override
    public boolean matchesCommitted(Connection c, Command command, JsonNode saved) throws SQLException {
        var action = "UC05".equals(command.ucCode()) ? Action.ARRIVAL : Action.NO_SHOW;
        if (!action.uc.equals(command.ucCode()) || !action.operation.equals(command.operation())) return false;
        var row = repository.findAppointment(c,id(command.payload(),"appointmentId"),clinic(command));
        if (row.isEmpty()) return false;
        var appointment = row.orElseThrow();
        return matchesSnapshot(action, appointment, repository.findVisit(c,appointment).orElse(null),
                saved,reference(action,appointment.actor(),command.clientKey()));
    }

    private static boolean matchesSnapshot(Action action, AppointmentRepository.Appointment row,
                                           AppointmentRepository.Visit visit, JsonNode saved, String reference) {
        var finding = saved.path("after").path("finding").asText();
        var expected = result(row.id(), finding, visit);
        var facts = saved.path("verification");
        return expected.equals(saved.path("after")) && expected.equals(saved.path("businessReference"))
                && reference.equals(facts.path("requestReference").asText())
                && storedReference(action, finding, row, visit).filter(reference::equals).isPresent()
                && AppointmentReadService.token(row).equals(facts.path("appointmentFingerprint").asText())
                && visitFingerprint(visit).equals(facts.path("visitFingerprint").asText());
    }
    static boolean confirmsCommittedRelease(Connection c,AppointmentRepository.Overlap overlap)throws SQLException {
        var repository=new AppointmentRepository();var row=repository.findAppointment(c,overlap.appointmentId(),overlap.clinicId());
        if(row.isEmpty())return false;var appointment=row.orElseThrow();
        if(CommittedBookingRelease.hasVisit(c,appointment.id()))return false;
        var reference=storedReference(Action.NO_SHOW,Action.NO_SHOW.finding,appointment,null);if(reference.isEmpty())return false;
        var events=new AuditStore(AuditStore.Family.UC).read(c,reference.orElseThrow()).stream().filter(e->"TERMINAL".equals(e.kind())).toList();
        if(events.size()!=1)return false;var event=events.getFirst();
        return Action.NO_SHOW.operation.equals(event.operation())&&"SUCCEEDED".equals(event.outcome())
            &&appointment.actor().equals(event.actor())&&"YES".equals(event.evidence().path("confirmedCommit").asText())
            &&matchesSnapshot(Action.NO_SHOW,appointment,null,event.evidence().path("businessResultSnapshot"),reference.orElseThrow());
    }

    private static Optional<String> storedReference(Action action, String finding,
                                                    AppointmentRepository.Appointment row, AppointmentRepository.Visit visit) {
        if (row.cancellation() != null) return Optional.empty();
        String document;
        if (PENDING.equals(finding) && "待核实".equals(row.status()) && visit == null && row.noShow() == null) {
            document = row.pendingArrival();
        } else if (action.finding.equals(finding) && action.status.equals(row.status())) {
            if (action == Action.ARRIVAL && visit != null && row.noShow() == null && visit.appointmentId() == row.id()) {
                document = visit.verification();
            } else if (action == Action.NO_SHOW && visit == null) {
                document = row.noShow();
            } else return Optional.empty();
        } else return Optional.empty();
        if (document == null) return Optional.empty();
        try {
            var basis = AuditDocuments.parse(document);
            var ref = text(basis,"requestReference");
            text(basis,"verificationReference");
            return ref.startsWith("UC/v1/"+action.uc+"/") && finding.equals(basis.path("finding").asText())
                    ? Optional.of(ref) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static ObjectNode result(long appointmentId, String finding, AppointmentRepository.Visit visit) {
        var result = JSON.createObjectNode().put("appointmentId",Long.toString(appointmentId)).put("finding",finding);
        if (visit != null) result.put("visitId",Long.toString(visit.id()));
        return result;
    }

    private static String visitFingerprint(AppointmentRepository.Visit visit) {
        if (visit == null) return "";
        return AuditDocuments.hash(JSON.createObjectNode().put("id",visit.id()).put("appointmentId",visit.appointmentId())
                .put("clinic",visit.clinicId()).put("dentist",visit.dentistId()).put("registeredAt",visit.registeredAt().toString())
                .put("arrivedAt",visit.arrivedAt()==null ? null : visit.arrivedAt().toString())
                .put("verification",visit.verification()).put("actor",visit.actor()));
    }

    @Override
    public boolean hasContradictorySuccess(Connection c, Command command, String reference) {
        return true;
    }

    private void requireActor(Action action, Command command, String actor) {
        if (!actor.equals(authorization.require(action.uc,command.clinics()).actor()))
            throw new SecurityException("REQUEST_ACTOR_CHANGED");
    }

    private static long clinic(Command command) {
        return command.clinics().iterator().next();
    }

    static String reference(Action action, String actor, UUID key) {
        return AuditDocuments.requestReference(actor,"UC",action.operation,key).replace("/v1/","/v1/"+action.uc+"/");
    }
}
