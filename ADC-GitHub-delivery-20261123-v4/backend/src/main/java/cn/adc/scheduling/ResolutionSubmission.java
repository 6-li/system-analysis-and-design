package cn.adc.scheduling;

import cn.adc.appointments.AppointmentResolutionEvidence;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

final class ResolutionSubmission {
    private static final Set<String> FIELDS = Set.of("appointmentId", "expectedAppointmentToken", "subject",
            "claimedResult", "resolutionId", "expectedResolutionToken", "revisionReason", "formalEvidence",
            "basis", "pendingReason");
    private final JdbcTransactions tx;
    private final StaffAuthorization auth;
    private final Clock clock;
    private final AuditStore.EventIdAllocator resolutionIds;
    private final ResolutionHistoryService.InternalCommands commands;
    private final ResolutionRepository repository = new ResolutionRepository();

    ResolutionSubmission(JdbcTransactions tx, StaffAuthorization auth, Clock clock,
                         AuditStore.EventIdAllocator auditIds, AuditStore.EventIdAllocator resolutionIds) {
        this(tx,auth,clock,auditIds,resolutionIds,false);
    }
    ResolutionSubmission(JdbcTransactions tx, StaffAuthorization auth, Clock clock,
                         AuditStore.EventIdAllocator auditIds, AuditStore.EventIdAllocator resolutionIds,boolean courseUc22Narrow) {
        this.tx = tx;
        this.auth = auth;
        this.clock = clock;
        this.resolutionIds = resolutionIds;
        this.commands = new ResolutionHistoryService().bind(tx, auth, clock, auditIds,courseUc22Narrow);
    }

    CommandRecovery.Receipt save(long schedule, int version, String key, JsonNode input) {
        if (!input.isObject() || !FIELDS.containsAll(input.propertyNames())) throw new IllegalArgumentException();
        long appointment = positive(AuditDocuments.text(input, "appointmentId"));
        String token = AuditDocuments.text(input, "expectedAppointmentToken");
        String subject = AuditDocuments.text(input, "subject");
        AuditDocuments.text(input, "claimedResult");
        Long predecessor = input.has("resolutionId") ? positive(AuditDocuments.text(input, "resolutionId")) : null;
        String headToken = optional(input, "expectedResolutionToken");
        String reason = optional(input, "revisionReason");
        if (predecessor == null ? headToken != null || reason != null : headToken == null || reason == null) {
            throw new IllegalArgumentException("INVALID_RESOLUTION_REVISION");
        }
        String basis = optional(input, "basis");
        String pending = optional(input, "pendingReason");
        var fact = input.path("formalEvidence");
        if (!fact.isMissingNode() && !fact.isObject()) throw new IllegalArgumentException("INVALID_FORMAL_EVIDENCE");
        var target = tx.execute(c -> {
            var draft = new JdbcScheduleStore().versions(c, schedule).stream().filter(v -> v.versionNo() == version)
                    .findFirst().orElseThrow(NoSuchElementException::new);
            var actor = auth.require("UC22", Set.of(draft.clinicId())).actor();
            var previous = predecessor == null ? null : repository.terminalDocuments(c, schedule, version, appointment)
                    .stream().filter(d -> Long.toString(predecessor).equals(d.path("newResolutionId").asText()))
                    .findFirst().orElseThrow(NoSuchElementException::new);
            return new Target(draft, actor, previous);
        });
        Long root = target.previous() == null ? null : positive(target.previous().get("rootResolutionId").asText());
        var clientKey = UUID.nameUUIDFromBytes(("UC22/v1/idempotency/" + key).getBytes(StandardCharsets.UTF_8));
        return commands.submit(clientKey, input, target.draft(), appointment, root, headToken, (c, ref, hash) -> {
            long clinic = target.draft().clinicId();
            if (!target.actor().equals(auth.require("UC22", Set.of(clinic)).actor())) throw new SecurityException();
            if (new AppointmentResolutionEvidence().currentToken(c, clinic, appointment).filter(token::equals).isEmpty()) {
                throw new CommandRecovery.Rejection("RESOLUTION_APPOINTMENT_STALE");
            }
            long id = resolutionIds.next(c);
            if (id <= 0) throw new java.sql.SQLException("RESOLUTION_ID_UNAVAILABLE", "22003");
            String newId = Long.toString(id);
            String time = StrictInputPolicy.eventTime(clock).toString();
            var doc = AuditDocuments.JSON.createObjectNode().put("schemaVersion", 1)
                    .put("operationKind", root == null ? "RESOLUTION_CREATE" : "RESOLUTION_APPEND")
                    .put("requestReference", ref).put("canonicalPayloadHash", hash)
                    .put("rootResolutionId", root == null ? newId : root.toString()).put("newResolutionId", newId)
                    .put("predecessorResolutionId", predecessor == null ? null : predecessor.toString())
                    .put("revisionReason", reason);
            doc.putObject("targetDraftRef").put("scheduleId", Long.toString(schedule)).put("versionNo", version);
            if (target.previous() == null) doc.putNull("before");
            else doc.set("before", target.previous().get("after"));
            String conclusion = fact.isObject() ? "VERIFIED" : "PENDING";
            doc.putObject("after").put("resolution_id", newId)
                    .put("schedule_id", Long.toString(schedule)).put("schedule_version_no", version)
                    .put("appointment_id", Long.toString(appointment)).put("resolution_subject", subject)
                    .put("resolution_result", conclusion).put("resolution_basis", basis)
                    .put("responsible_party", target.actor()).put("pending_confirmation", pending)
                    .put("recorded_at", time).put("appointment_change_id", fact.path("appointmentChangeId").asText(null));
            var verification = doc.putObject("verification").put("evaluatedAt", time)
                    .put("appointmentFingerprint", token).put("conclusion", conclusion)
                    .put("pendingReason", pending);
            if (fact.isObject()) verification.set("formalEvidence", fact);
            else verification.putNull("formalEvidence");
            verification.putArray("supportReasons");
            return doc;
        });
    }

    private static String optional(JsonNode input, String field) {
        return input.has(field) ? AuditDocuments.text(input, field) : null;
    }

    private static long positive(String value) {
        long id = Long.parseLong(value);
        if (id <= 0 || !Long.toString(id).equals(value)) throw new IllegalArgumentException();
        return id;
    }

    private record Target(ScheduleStateToken.Version draft, String actor, JsonNode previous) {}
}
