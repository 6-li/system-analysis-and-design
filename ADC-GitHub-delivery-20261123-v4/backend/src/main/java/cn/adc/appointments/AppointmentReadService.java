package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static cn.adc.shared.audit.AuditDocuments.hash;
import static cn.adc.shared.audit.AuditDocuments.parse;

/** Read-only projections for 012/018. No component registration or public route. */
final class AppointmentReadService {
    private static final List<String> ACTIONS = List.of("UC02", "UC03", "UC04", "UC05", "UC06", "UC21", "UC22");
    private final JdbcTransactions transactions;
    private final StaffAuthorization authorization;
    private final AppointmentRepository repository = new AppointmentRepository();
    private final Clock clock;

    AppointmentReadService(JdbcTransactions transactions, StaffAuthorization authorization, Clock clock) {
        this.transactions = transactions;
        this.authorization = authorization;
        this.clock = clock;
    }

    Optional<JsonNode> support(long clinic, long id) {
        return read(clinic, id, false);
    }

    Optional<JsonNode> detail(long clinic, long id) {
        return read(clinic, id, true);
    }

    private Optional<JsonNode> read(long clinic, long id, boolean detail) {
        // Clinic selection is supplied by the trusted staff bridge; the binding is checked again here.
        var grant = grant(clinic);
        return transactions.execute(c -> {
            var snapshot = repository.readSnapshot(c, id, clinic);
            var after = authorization.require(grant.action(), Set.of(clinic));
            if (!after.actor().equals(grant.actor())) throw new SecurityException("REQUEST_ACTOR_CHANGED");
            return snapshot.map(row -> detail ? detail(row, clinic) : support(row));
        });
    }

    private StaffAuthorization.Context grant(long clinic) {
        for (var action : ACTIONS) {
            var candidate = authorization.evaluate(action, Set.of(clinic));
            if (candidate.allowed()) return candidate;
        }
        throw new SecurityException("ACTION_OR_SCOPE_DENIED");
    }

    private ObjectNode support(AppointmentRepository.ReadSnapshot row) {
        var a = row.appointment();
        var result = JSON.createObjectNode().put("appointmentId", Long.toString(a.id()))
                .put("evaluatedAt", StrictInputPolicy.eventTime(clock).toString());
        result.set("originScheduleRef", ref(a.terms().schedule()));
        var reasons = result.putArray("reasons");
        if (row.candidates().size() != 1) {
            reasons.add(row.candidates().isEmpty() ? "NO_EFFECTIVE_VERSION" : "MULTIPLE_EFFECTIVE_VERSIONS");
            return result.put("result", "UNRESOLVED");
        }
        var candidate = row.candidates().getFirst();
        if (!candidate.sameResource()) reasons.add("RESOURCE_CHANGED");
        if (!candidate.covers()) reasons.add("WINDOW_NOT_COVERED");
        if (candidate.scheduleConflict() || candidate.bookingConflict()) reasons.add("RESOURCE_CONFLICT");
        if (!reasons.isEmpty()) return result.put("result", "NOT_SUPPORTED");
        if (candidate.uncertainBooking()) {
            reasons.add("OCCUPANCY_NOT_VERIFIED");
            return result.put("result", "UNRESOLVED");
        }
        result.set("effectiveScheduleRef", ref(candidate.version()));
        return result.put("result", "SUPPORTED");
    }

    private JsonNode detail(AppointmentRepository.ReadSnapshot row, long clinic) {
        var a = row.appointment();
        var terms = a.terms();
        var result = JSON.createObjectNode().put("appointmentId", Long.toString(a.id()))
                .put("patientId", Long.toString(a.patientId())).put("statusLabel", a.status())
                .put("startsAt", terms.startsAt().toString()).put("endsAt", terms.endsAt().toString())
                .put("stateToken", token(a));
        result.set("originScheduleRef", ref(terms.schedule()));
        result.set("currentSupport", support(row));
        // Free-text verification/reminder notes can include unrelated personal information.
        // Return only required fields and the verified, allowlisted frozen duration basis.
        try {
            var frozen = AppointmentEvidenceCodec.readStructured(terms.treatmentRequest());
            if (frozen.clinicId() == clinic && frozen.startsAt().equals(terms.startsAt())
                    && frozen.endsAt().equals(terms.endsAt()) && frozen.basis().projectId() > 0) {
                var basis = parse(frozen.text()).path("bookingDurationBasis");
                result.putObject("durationBasis").put("schemaVersion", 1)
                        .put("primaryProjectId", Long.toString(frozen.basis().projectId()))
                        .put("projectVersion", frozen.basis().versionNo())
                        .put("adoptedDurationMinutes", frozen.basis().minutes())
                        .put("basisMode", basis.path("basisMode").asText());
            }
        } catch (IllegalArgumentException ignored) {
            // Historical opaque or invalid evidence does not acquire a synthetic duration.
        }
        return result;
    }

    static String token(AppointmentRepository.Appointment a) {
        var t = a.terms();
        var snapshot = JSON.createArrayNode().add("appointment-v1").add(a.id()).add(a.patientId())
                .add(t.schedule().scheduleId()).add(t.schedule().versionNo()).add(t.startsAt().toString())
                .add(t.endsAt().toString()).add(t.treatmentRequest()).add(t.reminder())
                .add(a.status()).add(a.acceptance()).add(a.noShow()).add(a.cancellation())
                .add(a.actor()).add(a.operatedAt().toString()).add(a.representative()).add(a.pendingArrival());
        return hash(snapshot);
    }

    private static JsonNode ref(Version version) {
        return JSON.createObjectNode().put("scheduleId", Long.toString(version.scheduleId()))
                .put("versionNo", version.versionNo());
    }
}
