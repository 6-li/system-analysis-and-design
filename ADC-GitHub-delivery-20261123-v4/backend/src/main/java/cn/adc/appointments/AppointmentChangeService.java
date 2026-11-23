package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.duration.DurationRepository;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.CommandRecovery.Command;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.config.DentistEligibilityProvider;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator.Scope;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static cn.adc.appointments.InitialBookingReview.id;
import static cn.adc.shared.audit.AuditDocuments.JSON;
import static cn.adc.shared.audit.AuditDocuments.text;

/** Internal UC03 command and recovery. The origin and target scopes come from the staff adapter. */
final class AppointmentChangeService implements CommandRecovery.SuccessVerifier {
    private static final String OPERATION = "APPOINTMENT_CHANGE";
    private static final String USE_CASE = "UC03";

    @FunctionalInterface
    interface ReminderPreparation {
        String prepare(Connection connection, AppointmentRepository.Appointment original,
                       InitialBookingReview.AcceptedScheme target) throws SQLException;
    }

    interface MiniSource {
        void requireCurrent(Connection c, String reference, AppointmentRepository.Appointment original,
                            InitialBookingReview.AcceptedScheme scheme, String acceptanceReference) throws SQLException;

        void linkCommittedResult(Connection c, String reference, AppointmentRepository.Change change) throws SQLException;

        boolean matchesCommitted(Connection c, String reference, AppointmentRepository.Change change) throws SQLException;
    }

    private record DurationSelection(Basis basis, String projectName, String mode) {}

    private final AppointmentRepository repository;
    private final DurationRepository durations = new DurationRepository();
    private final StaffAuthorization authorization;
    private final ClinicDirectoryProvider clinics;
    private final DentistEligibilityProvider dentists;
    private final BookingAcceptanceVerifier acceptance;
    private final FormalBookingService.AppointmentIds changeIds;
    private final ReminderPreparation reminders;
    private final FormalBookingService.ReleaseEvidence releaseEvidence;
    private final Clock clock;
    private final MiniSource miniSource;
    private final CommandRecovery commands;

    AppointmentChangeService(JdbcTransactions transactions, StaffAuthorization authorization,
                             ClinicDirectoryProvider clinics, DentistEligibilityProvider dentists,
                             BookingAcceptanceVerifier acceptance, FormalBookingService.AppointmentIds changeIds,
                             AuditStore.EventIdAllocator auditIds, ReminderPreparation reminders,
                             FormalBookingService.ReleaseEvidence releaseEvidence, Clock clock) {
        this(transactions, authorization, clinics, dentists, acceptance, changeIds, auditIds, reminders,
                releaseEvidence, clock, new AppointmentRepository());
    }

    AppointmentChangeService(JdbcTransactions transactions, StaffAuthorization authorization,
                             ClinicDirectoryProvider clinics, DentistEligibilityProvider dentists,
                             BookingAcceptanceVerifier acceptance, FormalBookingService.AppointmentIds changeIds,
                             AuditStore.EventIdAllocator auditIds, ReminderPreparation reminders,
                             FormalBookingService.ReleaseEvidence releaseEvidence, Clock clock,
                             AppointmentRepository repository) {
        this(transactions, authorization, clinics, dentists, acceptance, changeIds, auditIds, reminders,
                releaseEvidence, clock, repository, null);
    }

    AppointmentChangeService(JdbcTransactions transactions, StaffAuthorization authorization,
                             ClinicDirectoryProvider clinics, DentistEligibilityProvider dentists,
                             BookingAcceptanceVerifier acceptance, FormalBookingService.AppointmentIds changeIds,
                             AuditStore.EventIdAllocator auditIds, ReminderPreparation reminders,
                             FormalBookingService.ReleaseEvidence releaseEvidence, Clock clock,
                             AppointmentRepository repository, MiniSource miniSource) {
        this.authorization = authorization;
        this.clinics = clinics;
        this.dentists = dentists;
        this.acceptance = acceptance;
        this.changeIds = changeIds;
        this.reminders = reminders;
        this.releaseEvidence = releaseEvidence;
        this.clock = clock;
        this.repository = repository;
        this.miniSource = miniSource;
        commands = new UcAuditService(transactions, authorization, this, clock, java.util.Objects.requireNonNull(auditIds)).commands();
    }

    CommandRecovery.Receipt change(long appointmentId, long originClinic, long targetClinic, UUID key, JsonNode input) {
        var command = command(appointmentId, originClinic, targetClinic, key, input);
        var actor = authorization.evaluate(USE_CASE, command.clinics()).actor();
        return commands.execute(command, (c, locks) -> apply(c, locks, command, actor));
    }

    CommandRecovery.Receipt recover(long appointmentId, long originClinic, long targetClinic, UUID key, JsonNode input) {
        return commands.recover(command(appointmentId, originClinic, targetClinic, key, input), false);
    }

    static Command command(long appointmentId, long originClinic, long targetClinic, UUID key, JsonNode input) {
        if (input == null || !input.isObject()) throw new Rejection("INVALID_CHANGE_INPUT");
        var payload = JSON.createObjectNode().put("appointmentId", Long.toString(appointmentId))
                .put("originClinic", Long.toString(originClinic)).put("targetClinic", Long.toString(targetClinic));
        payload.set("input", input);
        var clinics = originClinic == targetClinic ? Set.of(originClinic) : Set.of(originClinic, targetClinic);
        return new Command(OPERATION, USE_CASE, key, payload, clinics);
    }

    AuditDocuments.Change apply(Connection c, Scope locks, Command command, String actor) throws SQLException {
        var reference = reference(actor, command.clientKey());
        locks.requireCommand(c, reference);
        requireActor(command, actor);
        var payload = command.payload();
        var input = payload.path("input");
        var source = input.has("sourceContextReference") ? text(input, "sourceContextReference") : null;
        if (source != null && miniSource == null) throw new Rejection("VERIFIED_MINIPROGRAM_CHANGE_UNAVAILABLE");
        long appointmentId = id(payload, "appointmentId");
        long originClinic = id(payload, "originClinic");
        long targetClinic = id(payload, "targetClinic");
        var proposal = input.path("scheme");
        var ref = proposal.path("effectiveScheduleRef");
        var targetVersion = new Version(id(ref, "scheduleId"), StrictInputPolicy.positiveMinutes(ref.get("versionNo")));

        locks.catalog(false);
        var firstOrigin = repository.findOrigin(c, appointmentId, originClinic)
                .orElseThrow(() -> new Rejection("APPOINTMENT_NOT_FOUND"));
        var firstOld = InitialBookingReview.schedule(c, firstOrigin);
        var firstTarget = InitialBookingReview.schedule(c, targetVersion);
        if (firstOld.clinic() != originClinic || firstTarget.clinic() != targetClinic)
            throw new SecurityException("SCHEDULE_SCOPE_DENIED");
        locks.schedules(List.of(firstOrigin.scheduleId(), targetVersion.scheduleId()));
        locks.dentists(List.of(firstOld.dentist(), firstTarget.dentist()));
        locks.versions(List.of(firstOrigin, targetVersion));
        locks.appointments(List.of(appointmentId));

        requireActor(command, actor);
        var before = repository.findAppointment(c, appointmentId, originClinic)
                .orElseThrow(() -> new Rejection("STALE_APPOINTMENT"));
        if (!firstOrigin.equals(before.terms().schedule())
                || !AppointmentReadService.token(before).equals(text(input, "expectedAppointmentToken")))
            throw new Rejection("STALE_APPOINTMENT");
        var old = InitialBookingReview.schedule(c, firstOrigin);
        var target = InitialBookingReview.schedule(c, targetVersion);
        if (old.clinic() != originClinic || old.dentist() != firstOld.dentist()
                || target.clinic() != targetClinic || target.dentist() != firstTarget.dentist())
            throw new Rejection("STALE_SCHEDULE");
        if (!"EFFECTIVE".equals(target.status())) throw new Rejection("EFFECTIVE_SCHEDULE_REQUIRED");
        if (!"待到诊".equals(before.status()) || before.cancellation() != null || before.noShow() != null
                || repository.hasVisit(c, before)) throw new Rejection("APPOINTMENT_NOT_CHANGEABLE");

        var selection = selectDuration(c, before, originClinic, proposal.path("durationBasis"));
        var interval = InitialBookingReview.interval(clinics, text(proposal, "startsAt"),
                text(proposal, "endsAt"), target, selection.basis().minutes());
        var scheme = new InitialBookingReview.AcceptedScheme(before.patientId(), targetVersion, targetClinic,
                target.dentist(), interval.start(), interval.end(), text(proposal, "treatmentRequest"), selection.basis());
        FormalBookingService.requireDentist(dentists, scheme);
        for (var overlap : repository.overlaps(c, scheme.dentistId(), scheme.startsAt(), scheme.endsAt())) {
            if (overlap.appointmentId() != appointmentId && !FormalBookingService.released(c, overlap, releaseEvidence, clock))
                throw new Rejection("APPOINTMENT_CAPACITY_CONFLICT");
        }
        var confirmation = acceptance.verified(c, text(input, "acceptanceEvidenceReference"), scheme)
                .orElseThrow(() -> new Rejection("ACCEPTANCE_RECONFIRMATION_REQUIRED"));
        if (source != null) miniSource.requireCurrent(c, source, before, scheme, confirmation.reference());
        var reason = text(input, "changeBasis");
        var reminder = reminders.prepare(c, before, scheme);
        if (reminder == null) throw new SQLException("CHANGE_REMINDER_UNAVAILABLE", "55000");
        requireActor(command, actor);

        long changeId = changeIds.next(c);
        if (changeId <= 0) throw new SQLException("CHANGE_ID_UNAVAILABLE", "22003");
        var accepted = JSON.createObjectNode().put("schemaVersion", 1).put("requestReference", reference)
                .put("acceptanceEvidenceReference", confirmation.reference())
                .put("applicantSubject", confirmation.applicantSubject()).put("acceptedAt", confirmation.acceptedAt().toString())
                .toString();
        var terms = new AppointmentRepository.Terms(targetVersion, scheme.startsAt(), scheme.endsAt(),
                FormalBookingService.freeze(scheme, selection.projectName(), interval, selection.mode()), reminder);
        var now = StrictInputPolicy.eventTime(clock);
        var after = new AppointmentRepository.Appointment(appointmentId, before.patientId(), terms,
                accepted, before.status(), before.noShow(), before.cancellation(), actor, now,
                confirmation.representativeAuthorityReference(), before.pendingArrival());
        var history = new AppointmentRepository.ChangeEvidence(changeId, accepted, reason,
                confirmation.representativeAuthorityReference(), actor, now, reference);
        repository.updateAppointment(c, before, after);
        repository.appendChange(c, before, after, history);
        var change = new AppointmentRepository.Change(appointmentId, before.terms(), terms, history);
        if (source != null) miniSource.linkCommittedResult(c, source, change);
        var result = JSON.createObjectNode().put("appointmentId", Long.toString(appointmentId))
                .put("appointmentChangeId", Long.toString(changeId));
        var fingerprint = fingerprint(change);
        return new AuditDocuments.Change(result, null, result, JSON.createObjectNode().put("historyFingerprint", fingerprint));
    }

    private DurationSelection selectDuration(Connection c, AppointmentRepository.Appointment before,
                                             long originClinic, JsonNode basis) throws SQLException {
        var old = AppointmentEvidenceCodec.readStructured(before.terms().treatmentRequest());
        if (old.clinicId() != originClinic || !old.startsAt().equals(before.terms().startsAt())
                || !old.endsAt().equals(before.terms().endsAt())) throw new Rejection("INVALID_ORIGINAL_DURATION_BASIS");
        if (StrictInputPolicy.positiveMinutes(basis.get("schemaVersion")) != 1)
            throw new Rejection("INVALID_DURATION_BASIS");
        var submitted = new Basis(id(basis, "primaryProjectId"), StrictInputPolicy.positiveMinutes(basis.get("projectVersion")),
                StrictInputPolicy.positiveMinutes(basis.get("adoptedDurationMinutes")));
        var mode = text(basis, "basisMode");
        boolean sameProject = submitted.projectId() == old.basis().projectId();
        if (sameProject && "PRESERVED_ORIGINAL".equals(mode) && submitted.equals(old.basis())) {
            var originalBasis = JSON.readTree(old.text()).path("bookingDurationBasis");
            return new DurationSelection(old.basis(), text(originalBasis, "projectNameSnapshot"), mode);
        }
        if (!sameProject && "CHANGED_PROJECT_CURRENT".equals(mode)) {
            var current = durations.current(c, submitted.projectId());
            if (submitted.equals(InitialBookingReview.currentBasis(current, submitted.projectId())))
                return new DurationSelection(submitted, text(current, "project_name"), mode);
        }
        throw new Rejection("STALE_DURATION_BASIS");
    }

    @Override
    public boolean matchesCommitted(Connection c, Command command, JsonNode saved) throws SQLException {
        if (!OPERATION.equals(command.operation()) || !USE_CASE.equals(command.ucCode())) return false;
        var result = saved.path("after");
        if (!result.equals(saved.path("businessReference"))) return false;
        long appointmentId = id(command.payload(), "appointmentId");
        if (id(result, "appointmentId") != appointmentId) return false;
        var history = repository.findChange(c, appointmentId, id(result, "appointmentChangeId"));
        if (history.isEmpty()) return false;
        var row = history.orElseThrow();
        if (!reference(row.evidence().actor(), command.clientKey()).equals(row.evidence().requestReference())
                || InitialBookingReview.schedule(c, row.before().schedule()).clinic() != id(command.payload(), "originClinic")
                || InitialBookingReview.schedule(c, row.after().schedule()).clinic() != id(command.payload(), "targetClinic")
                || !fingerprint(row).equals(saved.path("verification").path("historyFingerprint").asText())) return false;
        var input = command.payload().path("input");
        return !input.has("sourceContextReference") || (miniSource != null
                && miniSource.matchesCommitted(c, text(input, "sourceContextReference"), row));
    }

    @Override
    public boolean hasContradictorySuccess(Connection c, Command command, String requestReference) {
        return true;
    }

    private void requireActor(Command command, String actor) {
        if (!actor.equals(authorization.require(USE_CASE, command.clinics()).actor()))
            throw new SecurityException("REQUEST_ACTOR_CHANGED");
    }

    static String reference(String actor, UUID key) {
        return AuditDocuments.requestReference(actor, "UC", OPERATION, key).replace("/v1/", "/v1/" + USE_CASE + "/");
    }

    static String fingerprint(AppointmentRepository.Change row) {
        var e = row.evidence();
        var node = JSON.createObjectNode().put("appointmentId", row.appointmentId()).put("changeId", e.id())
                .put("acceptance", e.acceptance()).put("reason", e.reason()).put("verification", e.verification())
                .put("actor", e.actor()).put("changedAt", e.changedAt().toString()).put("reference", e.requestReference());
        node.set("before", terms(row.before()));
        node.set("after", terms(row.after()));
        return AuditDocuments.hash(node);
    }

    private static ObjectNode terms(AppointmentRepository.Terms terms) {
        return JSON.createObjectNode().put("scheduleId", terms.schedule().scheduleId())
                .put("versionNo", terms.schedule().versionNo()).put("startsAt", terms.startsAt().toString())
                .put("endsAt", terms.endsAt().toString()).put("request", terms.treatmentRequest()).put("reminder", terms.reminder());
    }
}
