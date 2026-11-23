package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.duration.DurationRepository;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.ClinicTimePolicy;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Unregistered UC02 review component. It neither persists a booking nor emits a successful audit. */
public final class InitialBookingReview {
    public record AcceptedScheme(long patientId, Version schedule, long clinicId, long dentistId,
                                 Instant startsAt, Instant endsAt, String treatmentRequest, Basis duration) {}

    @FunctionalInterface
    public interface AcceptanceEvidence {
        /** A trusted verifier must match the actual applicant's evidence to this exact scheme. */
        boolean matches(Connection connection, String evidenceReference, AcceptedScheme scheme) throws SQLException;
    }

    private record Proposal(long patientId, Version schedule, String startsAt, String endsAt,
                            String treatmentRequest, Basis duration, String acceptanceReference) {
        static Proposal from(JsonNode input) {
            var scheme = input.path("scheme");
            var basis = scheme.path("durationBasis");
            if (!"INITIAL_CURRENT".equals(AuditDocuments.text(basis, "basisMode"))) {
                throw new Rejection("INITIAL_CURRENT_REQUIRED");
            }
            if (StrictInputPolicy.positiveMinutes(basis.get("schemaVersion")) != 1) {
                throw new Rejection("INVALID_DURATION_BASIS");
            }
            var ref = scheme.path("effectiveScheduleRef");
            return new Proposal(id(input, "patientId"),
                    new Version(id(ref, "scheduleId"), StrictInputPolicy.positiveMinutes(ref.get("versionNo"))),
                    AuditDocuments.text(scheme, "startsAt"), AuditDocuments.text(scheme, "endsAt"),
                    AuditDocuments.text(scheme, "treatmentRequest"),
                    new Basis(id(basis, "primaryProjectId"), StrictInputPolicy.positiveMinutes(basis.get("projectVersion")),
                            StrictInputPolicy.positiveMinutes(basis.get("adoptedDurationMinutes"))),
                    AuditDocuments.text(input, "acceptanceEvidenceReference"));
        }
    }

    record VerifiedBooking(AcceptedScheme scheme, String projectName, ClinicTimePolicy.Resolved time,
                           BookingAcceptanceVerifier.Confirmation confirmation) {}

    private record Reviewed(AcceptedScheme scheme, JsonNode current, ClinicTimePolicy.Resolved time) {}

    record Schedule(long clinic, long dentist, Instant start, Instant end, String status) {}

    private final JdbcTransactions transactions;
    private final StaffAuthorization authorization;
    private final ClinicDirectoryProvider clinics;
    private final AcceptanceEvidence acceptance;
    private final DurationRepository durations = new DurationRepository();

    public InitialBookingReview(JdbcTransactions transactions, StaffAuthorization authorization,
                                ClinicDirectoryProvider clinics, AcceptanceEvidence acceptance) {
        this.transactions = Objects.requireNonNull(transactions);
        this.authorization = Objects.requireNonNull(authorization);
        this.clinics = Objects.requireNonNull(clinics);
        this.acceptance = Objects.requireNonNull(acceptance);
    }

    /** Standalone review only. The result cannot authorize a later write in a different transaction. */
    public AcceptedScheme review(UUID requestKey, JsonNode input) {
        var proposal = Proposal.from(input);
        var actor = authorization.evaluate("UC02", Set.of()).actor();
        var reference = reference(actor, requestKey);
        return transactions.execute(connection -> {
            var locks = new ResourceLockCoordinator().scope(connection);
            locks.command(reference);
            return verify(connection, locks, reference, actor, proposal);
        });
    }

    /** Future UC02 command work must call this with its original connection and L1 scope before any write. */
    AcceptedScheme reviewLocked(Connection connection, ResourceLockCoordinator.Scope locks,
                                String actor, UUID requestKey, JsonNode input) throws SQLException {
        return verify(connection, locks, reference(actor, requestKey), actor, Proposal.from(input));
    }

    private AcceptedScheme verify(Connection connection, ResourceLockCoordinator.Scope locks,
                                  String reference, String actor, Proposal proposal) throws SQLException {
        var reviewed = reviewResources(connection, locks, reference, actor, proposal, null);
        if (!acceptance.matches(connection, proposal.acceptanceReference(), reviewed.scheme())) {
            throw new Rejection("ACCEPTANCE_RECONFIRMATION_REQUIRED");
        }
        requireActor(actor, reviewed.scheme().clinicId());
        return reviewed.scheme();
    }

    VerifiedBooking reviewForCreate(Connection connection, ResourceLockCoordinator.Scope locks,
                                    String actor, UUID key, long clinic, JsonNode input) throws SQLException {
        if (!(acceptance instanceof BookingAcceptanceVerifier verifier)) {
            throw new SQLException("TRUSTED_ACCEPTANCE_ADAPTER_REQUIRED", "55000");
        }
        var proposal = Proposal.from(input);
        var reviewed = reviewResources(connection, locks, reference(actor, key), actor, proposal, clinic);
        var confirmation = verifier.verified(connection, proposal.acceptanceReference(), reviewed.scheme())
                .orElseThrow(() -> new Rejection("ACCEPTANCE_RECONFIRMATION_REQUIRED"));
        requireActor(actor, clinic);
        return new VerifiedBooking(reviewed.scheme(), AuditDocuments.text(reviewed.current(), "project_name"),
                reviewed.time(), confirmation);
    }

    private Reviewed reviewResources(Connection connection, ResourceLockCoordinator.Scope locks,
                                     String reference, String actor, Proposal proposal, Long clinic) throws SQLException {
        locks.requireCommand(connection, reference);
        if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException("BOOKING_READ_COMMITTED_REQUIRED");
        }
        locks.catalog(false);
        var scheduleRef = proposal.schedule();
        // This read locates the authorization scope and lock keys only. L5 protects the final read.
        var before = schedule(connection, scheduleRef);
        if (clinic != null && before.clinic() != clinic) throw new SecurityException("SCHEDULE_SCOPE_DENIED");
        requireActor(actor, before.clinic());
        var row = durations.current(connection, proposal.duration().projectId());
        var current = currentBasis(row, proposal.duration().projectId());
        if (!current.equals(proposal.duration())) throw new Rejection("STALE_DURATION_BASIS");

        locks.schedules(List.of(scheduleRef.scheduleId()));
        locks.dentists(List.of(before.dentist()));
        locks.versions(List.of(scheduleRef));
        var schedule = schedule(connection, scheduleRef);
        if (schedule.dentist() != before.dentist() || schedule.clinic() != before.clinic()) {
            throw new Rejection("STALE_SCHEDULE");
        }
        requireActor(actor, schedule.clinic());
        if (!"EFFECTIVE".equals(schedule.status())) throw new Rejection("EFFECTIVE_SCHEDULE_REQUIRED");
        var interval = interval(proposal, schedule, current.minutes());
        var verified = new AcceptedScheme(proposal.patientId(), scheduleRef, schedule.clinic(), schedule.dentist(),
                interval.start(), interval.end(), proposal.treatmentRequest(), current);
        return new Reviewed(verified, row, interval);
    }

    static Basis currentBasis(JsonNode row, long projectId) {
        if (row == null) throw new Rejection("CURRENT_DURATION_REQUIRED");
        var project = row.path("project_id");
        if (!"CURRENT".equals(row.path("publication_status").asText()) || !project.isIntegralNumber()
                || !project.canConvertToLong() || project.longValue() != projectId) {
            throw new Rejection("INVALID_CURRENT_DURATION");
        }
        return new Basis(projectId, StrictInputPolicy.positiveMinutes(row.get("duration_version_no")),
                StrictInputPolicy.positiveMinutes(row.get("duration_minutes")));
    }

    private ClinicTimePolicy.Resolved interval(Proposal proposal, Schedule schedule, int minutes) {
        return interval(clinics, proposal.startsAt(), proposal.endsAt(), schedule, minutes);
    }

    static ClinicTimePolicy.Resolved interval(ClinicDirectoryProvider clinics, String startsAt, String endsAt,
                                               Schedule schedule, int minutes) {
        var clinic = clinics.find(schedule.clinic());
        if (clinic == null || clinic.id() != schedule.clinic() || clinic.zone() == null || clinic.provenance() == null) {
            throw new Rejection("VERIFIED_CLINIC_REQUIRED");
        }
        clinic.provenance().requireUsable();
        var requestedStart = StrictInputPolicy.instant(startsAt);
        var offsetStart = OffsetDateTime.parse(StrictInputPolicy.microsecondText(startsAt));
        var policy = new ClinicTimePolicy(clinic.provenance().version(), Map.of(clinic.id(), clinic.zone().getId()));
        var resolved = policy.resolve(clinic.id(), offsetStart.toLocalDateTime().toString(), offsetStart.getOffset(), minutes);
        if (!resolved.start().equals(requestedStart) || !resolved.end().equals(StrictInputPolicy.instant(endsAt))
                || resolved.start().isBefore(schedule.start()) || resolved.end().isAfter(schedule.end())) {
            throw new Rejection("INVALID_BOOKING_INTERVAL");
        }
        return resolved;
    }

    private void requireActor(String actor, long clinic) {
        if (!actor.equals(authorization.require("UC02", Set.of(clinic)).actor())) {
            throw new SecurityException("REQUEST_ACTOR_CHANGED");
        }
    }

    static Schedule schedule(Connection connection, Version version) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT clinic_id,dentist_id,starts_at,ends_at,activation_status
                FROM adc.reception_schedule_version
                WHERE schedule_id=? AND schedule_version_no=?
                """)) {
            query.setLong(1, version.scheduleId());
            query.setInt(2, version.versionNo());
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new Rejection("EFFECTIVE_SCHEDULE_REQUIRED");
                return new Schedule(row.getLong(1), row.getLong(2), row.getObject(3, OffsetDateTime.class).toInstant(),
                        row.getObject(4, OffsetDateTime.class).toInstant(), row.getString(5));
            }
        }
    }

    static long id(JsonNode node, String field) {
        var text = AuditDocuments.text(node, field);
        if (!text.matches("-?[0-9]+")) throw new Rejection("INVALID_BOOKING_ID");
        return Long.parseLong(text);
    }

    private static String reference(String actor, UUID key) {
        return AuditDocuments.requestReference(actor, "UC", "APPOINTMENT_CREATE", key).replace("/v1/", "/v1/UC02/");
    }
}
