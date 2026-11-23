package cn.adc.scheduling;

import cn.adc.appointments.AppointmentResolutionEvidence;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Internal append operation. The command journal persists the terminal result in the same transaction. */
final class ResolutionHistoryService {
    private final ResolutionChain chains = new ResolutionChain();
    private final ResolutionRepository repository = new ResolutionRepository();

    InternalCommands bind(JdbcTransactions transactions, StaffAuthorization authorization, Clock clock,
                          AuditStore.EventIdAllocator auditIds) {
        return bind(transactions,authorization,clock,auditIds,false);
    }
    InternalCommands bind(JdbcTransactions transactions, StaffAuthorization authorization, Clock clock,
                          AuditStore.EventIdAllocator auditIds,boolean courseUc22Narrow) {
        return new InternalCommands(new UcAuditService(transactions, authorization, recoveryVerifier(), clock,
                Objects.requireNonNull(auditIds),courseUc22Narrow).commands());
    }

    final class InternalCommands {
        private final CommandRecovery journal;

        private InternalCommands(CommandRecovery journal) {
            this.journal = journal;
        }

        CommandRecovery.Receipt submit(UUID key, JsonNode input, ScheduleStateToken.Version draft,
                                       long appointment, Long root, String expectedHeadFingerprint,
                                       DocumentFactory documents) {
            return execute(journal, command(key, input, draft, appointment, root, expectedHeadFingerprint),
                    draft, appointment, root, expectedHeadFingerprint, documents);
        }

        CommandRecovery.Receipt recover(UUID key, JsonNode input, ScheduleStateToken.Version draft,
                                        long appointment, Long root, String expectedHeadFingerprint) {
            return journal.recover(command(key, input, draft, appointment, root, expectedHeadFingerprint), false);
        }
    }

    private static CommandRecovery.Command command(UUID key, JsonNode input, ScheduleStateToken.Version draft,
                                                   long appointment, Long root, String expectedHeadFingerprint) {
        var payload = AuditDocuments.JSON.createObjectNode()
                .put("scheduleId", Long.toString(draft.scheduleId())).put("versionNo", draft.versionNo())
                .put("appointmentId", Long.toString(appointment))
                .put("rootResolutionId", root == null ? null : root.toString())
                .put("expectedHeadFingerprint", expectedHeadFingerprint);
        payload.set("input", Objects.requireNonNull(input));
        return new CommandRecovery.Command(root == null ? "RESOLUTION_CREATE" : "RESOLUTION_APPEND",
                "UC22", key, payload, Set.of(draft.clinicId()));
    }

    CommandRecovery.SuccessVerifier recoveryVerifier() {
        return new CommandRecovery.SuccessVerifier() {
            @Override
            public boolean matchesCommitted(Connection connection, CommandRecovery.Command command,
                                            JsonNode savedChange) throws SQLException {
                if (!"UC22".equals(command.ucCode()) || savedChange == null) return false;
                try {
                    var revision = savedChange.path("verification");
                    AuditDocuments.revision(revision);
                    var after = revision.get("after");
                    var reference = savedChange.path("businessReference");
                    long schedule = Long.parseLong(after.get("schedule_id").asText());
                    int version = after.get("schedule_version_no").intValue();
                    long appointment = Long.parseLong(after.get("appointment_id").asText());
                    if (!command.operation().equals(revision.path("operationKind").asText())
                            || !after.get("schedule_id").equals(command.payload().path("scheduleId"))
                            || !after.get("schedule_version_no").equals(command.payload().path("versionNo"))
                            || !after.get("appointment_id").equals(command.payload().path("appointmentId"))
                            || ("RESOLUTION_APPEND".equals(command.operation())
                                && !revision.get("rootResolutionId").equals(command.payload().path("rootResolutionId")))
                            || !after.equals(savedChange.path("after"))
                            || !revision.get("before").equals(savedChange.path("before"))
                            || !after.get("resolution_id").equals(reference.path("resolutionId"))
                            || !after.get("schedule_id").equals(reference.path("scheduleId"))
                            || !after.get("schedule_version_no").equals(reference.path("versionNo"))
                            || new JdbcScheduleStore().versions(connection, schedule).stream()
                            .noneMatch(v -> v.versionNo() == version && command.clinics().contains(v.clinicId()))) return false;
                    var rows = repository.forAppointment(connection, schedule, version, appointment);
                    var documents = repository.terminalDocuments(connection, schedule, version, appointment);
                    return historyContains(revision, rows, documents);
                } catch (IllegalArgumentException invalidEvidence) {
                    return false;
                }
            }

            @Override
            public boolean hasContradictorySuccess(Connection connection, CommandRecovery.Command command,
                                                   String requestReference) {
                // Never close an uncertain UC request based on the absence of a row.
                return true;
            }
        };
    }

    boolean historyContains(JsonNode revision, List<JsonNode> rows, List<JsonNode> documents) {
        if (!documents.contains(revision)) return false;
        return chains.verifiedRevisions(rows, documents).stream()
                .anyMatch(entry -> entry.snapshot().equals(revision.get("after")));
    }

    @FunctionalInterface
    interface DocumentFactory {
        JsonNode verifyAndBuild(Connection connection, String requestReference, String payloadHash) throws SQLException;
    }

    CommandRecovery.Receipt execute(CommandRecovery commands, CommandRecovery.Command command,
                                    ScheduleStateToken.Version draft, long appointment, Long root,
                                    String expectedHeadFingerprint, DocumentFactory documents) {
        var operation = root == null ? "RESOLUTION_CREATE" : "RESOLUTION_APPEND";
        if (!"UC22".equals(command.ucCode()) || !operation.equals(command.operation())
                || !command.clinics().contains(draft.clinicId())) {
            throw new IllegalArgumentException("INVALID_RESOLUTION_COMMAND");
        }
        try {
            var ticket = commands.begin(command);
            return commands.perform(ticket, (connection, locks) -> {
                locks.schedules(List.of(draft.scheduleId()));
                locks.dentists(List.of(draft.dentistId()));
                locks.versions(List.of(new ResourceLockCoordinator.Version(draft.scheduleId(), draft.versionNo())));
                locks.appointments(List.of(appointment));
                if (root != null) locks.roots(List.of(root));
                var current = new JdbcScheduleStore().versions(connection, draft.scheduleId());
                if (!"DRAFT".equals(draft.status()) || !current.contains(draft)) {
                    throw new CommandRecovery.Rejection("RESOLUTION_DRAFT_STALE");
                }
                var revision = documents.verifyAndBuild(connection, ticket.requestReference(), ticket.canonicalPayloadHash());
                AuditDocuments.revision(revision);
                var after = revision.get("after");
                if (!operation.equals(revision.get("operationKind").asText())
                        || !ticket.requestReference().equals(revision.get("requestReference").asText())
                        || !ticket.canonicalPayloadHash().equals(revision.get("canonicalPayloadHash").asText())
                        || !Long.toString(draft.scheduleId()).equals(after.get("schedule_id").asText())
                        || draft.versionNo() != after.get("schedule_version_no").intValue()
                        || !Long.toString(appointment).equals(after.get("appointment_id").asText())
                        || (root != null && !Objects.equals(root.toString(), revision.get("rootResolutionId").asText()))) {
                    throw new IllegalArgumentException("RESOLUTION_COMMAND_DOCUMENT_MISMATCH");
                }
                verifyFormalEvidence(connection, draft.clinicId(), appointment, revision);
                return appendLocked(connection, revision, expectedHeadFingerprint);
            });
        } catch (CommandRecovery.Unavailable unavailable) {
            return unavailable.receipt();
        }
    }

    void verifyFormalEvidence(Connection connection, long clinic, long appointment, JsonNode revision)
            throws SQLException {
        var verification = revision.get("verification");
        var evidence = verification.get("formalEvidence");
        var linkedChange = revision.get("after").get("appointment_change_id");
        if (evidence.isNull()) {
            if (!linkedChange.isNull()) throw new CommandRecovery.Rejection("RESOLUTION_FORMAL_EVIDENCE_MISMATCH");
            return;
        }
        String uc = AuditDocuments.text(evidence, "ucCode");
        long event = Long.parseLong(AuditDocuments.text(evidence, "auditSuccessEventId"));
        Long change = null;
        String cancellation = null;
        if ("UC03".equals(uc)) {
            AuditDocuments.fields(evidence, "ucCode", "auditSuccessEventId", "appointmentChangeId");
            change = Long.parseLong(AuditDocuments.text(evidence, "appointmentChangeId"));
            if (change <= 0 || !Long.toString(change).equals(linkedChange.asText())) {
                throw new CommandRecovery.Rejection("RESOLUTION_FORMAL_EVIDENCE_MISMATCH");
            }
        } else if ("UC04".equals(uc) && linkedChange.isNull()) {
            AuditDocuments.fields(evidence, "ucCode", "auditSuccessEventId", "cancellationBasisReference");
            cancellation = AuditDocuments.text(evidence, "cancellationBasisReference");
        } else {
            throw new CommandRecovery.Rejection("RESOLUTION_FORMAL_EVIDENCE_MISMATCH");
        }
        if (event <= 0 || !new AppointmentResolutionEvidence().confirms(connection, clinic, appointment,
                verification.get("appointmentFingerprint").asText(), event, uc, change, cancellation)) {
            throw new CommandRecovery.Rejection("RESOLUTION_FORMAL_EVIDENCE_UNCONFIRMED");
        }
    }

    AuditDocuments.Change appendLocked(Connection connection, JsonNode revision,
                                      String expectedHeadFingerprint) throws SQLException {
        AuditDocuments.revision(revision);
        var after = revision.get("after");
        var before = revision.get("before");
        long id = Long.parseLong(after.get("resolution_id").asText());
        long root = Long.parseLong(revision.get("rootResolutionId").asText());
        long schedule = Long.parseLong(after.get("schedule_id").asText());
        int version = after.get("schedule_version_no").intValue();
        long appointment = Long.parseLong(after.get("appointment_id").asText());
        if (id <= 0 || schedule <= 0 || version <= 0 || appointment <= 0 || root <= 0) {
            throw new IllegalArgumentException("INVALID_RESOLUTION_REFERENCE");
        }
        var rows = repository.forAppointment(connection, schedule, version, appointment);
        var history = chains.verifiedRevisions(rows, repository.terminalDocuments(connection, schedule, version, appointment));
        if (!before.isNull()) {
            long predecessor = Long.parseLong(revision.get("predecessorResolutionId").asText());
            var head = chains.expectedHead(history, root, predecessor, expectedHeadFingerprint);
            if (head.isEmpty() || !head.orElseThrow().snapshot().equals(before)) {
                throw new CommandRecovery.Rejection("RESOLUTION_HEAD_STALE");
            }
        } else if (expectedHeadFingerprint != null) {
            throw new IllegalArgumentException("ORIGINAL_HAS_HEAD_TOKEN");
        }
        if (history.stream().anyMatch(entry -> entry.id() == id)) {
            throw new CommandRecovery.Rejection("RESOLUTION_ID_ALREADY_USED");
        }
        repository.append(connection, after);
        var reference = AuditDocuments.JSON.createObjectNode().put("resolutionId", Long.toString(id))
                .put("scheduleId", Long.toString(schedule)).put("versionNo", version);
        return new AuditDocuments.Change(reference, before, after, revision);
    }
}
