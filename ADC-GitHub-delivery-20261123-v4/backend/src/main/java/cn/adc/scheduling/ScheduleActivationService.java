package cn.adc.scheduling;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Version switching is available only as work inside the original-UC audit transaction. */
final class ScheduleActivationService {
    interface FinalScan {
        Observation read(Connection c, ScheduleStateToken.Version draft) throws SQLException;
    }

    record Observation(String eligibilityState, boolean qualifiedAndCoordinated,
                       List<ScheduleActivationReview.AppointmentState> appointments,
                       List<ImpactAssessmentService.Schedule> effective) {}

    private final ScheduleActivationReview review = new ScheduleActivationReview();
    private final JdbcScheduleStore store = new JdbcScheduleStore();

    CommandRecovery bind(JdbcTransactions transactions, StaffAuthorization authorization, Clock clock,
                         AuditStore.EventIdAllocator auditIds) {
        return bind(transactions,authorization,clock,auditIds,false);
    }
    CommandRecovery bind(JdbcTransactions transactions, StaffAuthorization authorization, Clock clock,
                         AuditStore.EventIdAllocator auditIds,boolean courseUc22Narrow) {
        return new UcAuditService(transactions, authorization, new CommandRecovery.SuccessVerifier() {
            @Override
            public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved)
                    throws SQLException {
                if (!"UC22".equals(command.ucCode()) || !"ACTIVATE".equals(command.operation())) return false;
                var after = saved.path("after");
                if (!after.isObject() || !after.equals(saved.path("businessReference"))
                        || !after.path("scheduleId").equals(command.payload().path("scheduleId"))
                        || !after.path("versionNo").equals(command.payload().path("versionNo"))
                        || !command.payload().path("checkToken").equals(saved.path("verification").path("finalCheckToken"))) {
                    return false;
                }
                try {
                    long schedule = Long.parseLong(after.path("scheduleId").asText());
                    var versions = store.versions(c, schedule);
                    if (!matchesHistoricalVersion(after, versions, command.clinics(), false)) return false;
                    var before = saved.path("before");
                    return before.isNull() || (after.path("scheduleId").equals(before.path("scheduleId"))
                            && !after.path("versionNo").equals(before.path("versionNo"))
                            && matchesHistoricalVersion(before, versions, command.clinics(), true));
                } catch (IllegalArgumentException invalid) {
                    return false;
                }
            }

            @Override
            public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
                return true;
            }
        }, clock, java.util.Objects.requireNonNull(auditIds),courseUc22Narrow).commands();
    }

    CommandRecovery.Receipt lookup(JdbcTransactions tx, StaffAuthorization auth, String reference) {
        var parts = reference.split("/", -1);
        if (parts.length != 6 || !"UC".equals(parts[0]) || !"v1".equals(parts[1])
                || !"UC22".equals(parts[2]) || !"ACTIVATE".equals(parts[4])
                || !parts[3].matches("[a-f0-9]{32}")) throw new IllegalArgumentException("INVALID_ACTIVATION_REFERENCE");
        var key = java.util.UUID.fromString(parts[5]);
        String actor = auth.evaluate("UC22", Set.of()).actor();
        String owned = AuditDocuments.requestReference(actor, "UC", "ACTIVATE", key).replace("/v1/", "/v1/UC22/");
        if (!owned.equals(reference)) throw new SecurityException("REQUEST_OWNER_REQUIRED");
        return tx.execute(c -> {
            var events = new AuditStore(AuditStore.Family.UC).read(c, reference);
            if (events.isEmpty()) throw new java.util.NoSuchElementException("ACTIVATION_NOT_FOUND");
            var start = events.getFirst();
            if (!"START".equals(start.kind()) || start.sequence() != 1 || !actor.equals(start.actor())) {
                throw new IllegalArgumentException("INVALID_ACTIVATION_HISTORY");
            }
            Set<Long> clinics = new java.util.HashSet<>();
            try (var query = c.prepareStatement("""
                    SELECT authorization_scope FROM adc.operation_audit_event
                    WHERE request_reference=? AND event_sequence_no=1 AND uc_code='UC22' AND operation_name='ACTIVATE'
                    """)) {
                query.setString(1, reference);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new SecurityException("REQUEST_SCOPE_UNAVAILABLE");
                    var scope = AuditDocuments.parse(rows.getString(1));
                    scope.path("actualGrantedScope").forEach(grant -> {
                        if ("UC22".equals(grant.path("action").asText()) && "CLINICS".equals(grant.path("scope").asText())) {
                            grant.path("clinicIds").forEach(id -> {
                                if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0)
                                    throw new IllegalArgumentException("INVALID_ACTIVATION_SCOPE");
                                clinics.add(id.longValue());
                            });
                        }
                    });
                }
            }
            if (!actor.equals(auth.require("UC22", clinics).actor())) throw new SecurityException("REQUEST_OWNER_CHANGED");
            var terminal = events.stream().filter(event -> "TERMINAL".equals(event.kind())).toList();
            var unknown = new CommandRecovery.Receipt(reference, "UNKNOWN",
                    CommandRecovery.Persistence.CONFIRMED, null, true);
            if (terminal.size() != 1) return unknown;
            var event = terminal.getFirst();
            var digest = start.evidence().path("canonicalPayloadHash");
            if (!actor.equals(event.actor()) || !"ACTIVATE".equals(event.operation())
                    || !digest.asText().matches("[a-f0-9]{64}")
                    || !digest.equals(event.evidence().path("canonicalPayloadHash"))) return unknown;
            if ("FAILED".equals(event.outcome()) || "REJECTED".equals(event.outcome())) {
                if (!"NO".equals(event.evidence().path("confirmedCommit").asText())) return unknown;
                return new CommandRecovery.Receipt(reference, event.outcome(), CommandRecovery.Persistence.CONFIRMED,
                        null, true, null, event.evidence().path("observationReason").asText(null));
            }
            var saved = event.evidence().path("businessResultSnapshot");
            var after = saved.path("after");
            var before = saved.path("before");
            if (!"SUCCEEDED".equals(event.outcome()) || !"YES".equals(event.evidence().path("confirmedCommit").asText())
                    || !after.isObject() || !after.equals(saved.path("businessReference"))) return unknown;
            long schedule = Long.parseLong(AuditDocuments.text(after, "scheduleId"));
            var versions = store.versions(c, schedule);
            if (!matchesHistoricalVersion(after, versions, clinics, false)
                    || (!before.isNull() && (!after.path("scheduleId").equals(before.path("scheduleId"))
                    || after.path("versionNo").equals(before.path("versionNo"))
                    || !matchesHistoricalVersion(before, versions, clinics, true)))) return unknown;
            if (!actor.equals(auth.require("UC22", clinics).actor())) throw new SecurityException("REQUEST_OWNER_CHANGED");
            return new CommandRecovery.Receipt(reference, "SUCCEEDED", CommandRecovery.Persistence.CONFIRMED,
                    after, true, event.id(), null);
        });
    }

    private static boolean matchesHistoricalVersion(JsonNode snapshot, List<ScheduleStateToken.Version> versions,
                                                    Set<Long> clinics, boolean supersededOnly) {
        if (!snapshot.path("versionNo").isIntegralNumber() || !snapshot.path("versionNo").canConvertToInt()) return false;
        var activatedAt = StrictInputPolicy.instant(AuditDocuments.text(snapshot, "activatedAt"));
        return versions.stream().anyMatch(version -> version.versionNo() == snapshot.path("versionNo").intValue()
                && clinics.contains(version.clinicId()) && activatedAt.equals(version.activatedAt())
                && ("SUPERSEDED".equals(version.status()) || (!supersededOnly && "EFFECTIVE".equals(version.status()))));
    }

    CommandRecovery.Work work(ScheduleStateToken.Version requested, String token, StaffAuthorization auth,
                              FinalScan scan, Clock clock) {
        return work(requested, null, token, auth, scan, clock);
    }

    CommandRecovery.Work work(ScheduleStateToken.Version requested, String draftToken, String token,
                              StaffAuthorization auth, FinalScan scan, Clock clock) {
        return (c, locks) -> {
            locks.schedules(List.of(requested.scheduleId()));
            var versions = store.versions(c, requested.scheduleId());
            var previous = versions.stream().filter(v -> "EFFECTIVE".equals(v.status())).findFirst().orElse(null);
            var dentists = new java.util.HashSet<Long>();
            dentists.add(requested.dentistId());
            if (previous != null) dentists.add(previous.dentistId());
            locks.dentists(dentists);
            var references = new java.util.ArrayList<ResourceLockCoordinator.Version>();
            references.add(new ResourceLockCoordinator.Version(requested.scheduleId(), requested.versionNo()));
            if (previous != null) references.add(new ResourceLockCoordinator.Version(previous.scheduleId(), previous.versionNo()));
            locks.versions(references);
            var current = store.versions(c, requested.scheduleId());
            if (!"DRAFT".equals(requested.status()) || !current.contains(requested) || !versions.equals(current)) {
                throw new CommandRecovery.Rejection("ACTIVATION_DRAFT_STALE");
            }
            var clinicScope = new java.util.HashSet<Long>();
            clinicScope.add(requested.clinicId());
            if (previous != null) clinicScope.add(previous.clinicId());
            current.forEach(version -> clinicScope.add(version.clinicId()));
            auth.require("UC22", Set.copyOf(clinicScope));
            String currentDraftToken = ScheduleStateToken.of(requested, current);
            if (draftToken != null && !draftToken.equals(currentDraftToken)) {
                throw new CommandRecovery.Rejection("ACTIVATION_DRAFT_STALE");
            }
            var initial = scan.read(c, requested);
            locks.appointments(initial.appointments().stream().map(ScheduleActivationReview.AppointmentState::id).toList());
            var finalState = scan.read(c, requested);
            if (!initial.appointments().equals(finalState.appointments())) {
                throw new CommandRecovery.Rejection("ACTIVATION_CHECK_STALE");
            }
            String reviewedToken = token == null || draftToken != null
                    ? review.token(currentDraftToken, initial.eligibilityState(), initial.appointments()) : token;
            review.requireReady(reviewedToken, currentDraftToken, finalState.eligibilityState(),
                    new ImpactAssessmentService.Schedule(requested.scheduleId(), requested.versionNo(),
                            requested.clinicId(), requested.dentistId(),
                            new ImpactAssessmentService.Window(requested.startsAt(), requested.endsAt())),
                    finalState.effective(), finalState.appointments(), finalState.qualifiedAndCoordinated());
            auth.require("UC22", Set.copyOf(clinicScope));
            var activatedAt = StrictInputPolicy.eventTime(clock);
            if (previous != null) retire(c, previous);
            try (var p = c.prepareStatement("""
                    UPDATE adc.reception_schedule_version SET activation_status='EFFECTIVE',activated_at=?
                    WHERE schedule_id=? AND schedule_version_no=? AND activation_status='DRAFT'
                    """)) {
                p.setObject(1, activatedAt.atOffset(ZoneOffset.UTC));
                p.setLong(2, requested.scheduleId());
                p.setInt(3, requested.versionNo());
                requireOne(p.executeUpdate());
            }
            var before = previous == null ? AuditDocuments.JSON.nullNode()
                    : AuditDocuments.JSON.createObjectNode().put("scheduleId", Long.toString(previous.scheduleId()))
                    .put("versionNo", previous.versionNo()).put("activatedAt", previous.activatedAt().toString());
            var after = AuditDocuments.JSON.createObjectNode().put("scheduleId", Long.toString(requested.scheduleId()))
                    .put("versionNo", requested.versionNo()).put("activatedAt", activatedAt.toString());
            return new AuditDocuments.Change(after, before, after,
                    AuditDocuments.JSON.createObjectNode().put("finalCheckToken", token)
                            .put("reviewedStateToken", reviewedToken));
        };
    }

    private static void retire(Connection c, ScheduleStateToken.Version previous) throws SQLException {
        try (var p = c.prepareStatement("""
                UPDATE adc.reception_schedule_version SET activation_status='SUPERSEDED'
                WHERE schedule_id=? AND schedule_version_no=? AND activation_status='EFFECTIVE'
                """)) {
            p.setLong(1, previous.scheduleId());
            p.setInt(2, previous.versionNo());
            requireOne(p.executeUpdate());
        }
    }

    private static void requireOne(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("ACTIVATION_WRITE_UNCONFIRMED", "P0001");
    }
}
