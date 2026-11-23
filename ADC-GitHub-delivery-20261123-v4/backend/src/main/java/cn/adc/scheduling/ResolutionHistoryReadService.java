package cn.adc.scheduling;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Internal history projection. A head is emitted only from a complete, verified chain. */
final class ResolutionHistoryReadService {
    record History(long root, long head, String headFingerprint, List<JsonNode> revisions,
                   String currentAssessment, List<JsonNode> formalFacts) {
        History(long root, long head, String headFingerprint, List<JsonNode> revisions) {
            this(root, head, headFingerprint, revisions, "PENDING", List.of());
        }
    }

    private final ResolutionRepository repository = new ResolutionRepository();
    private final ResolutionChain chain = new ResolutionChain();
    private final JdbcTransactions transactions;
    private final StaffAuthorization authorization;

    ResolutionHistoryReadService(JdbcTransactions transactions, StaffAuthorization authorization) {
        this.transactions = transactions;
        this.authorization = authorization;
    }

    List<History> forVersion(long schedule, int version) {
        return transactions.execute(c -> {
            new ResourceLockCoordinator().scope(c).schedules(List.of(schedule));
            var target = new JdbcScheduleStore().versions(c, schedule).stream()
                    .filter(row -> row.versionNo() == version).findFirst()
                    .orElseThrow(() -> new java.util.NoSuchElementException("RESOLUTION_VERSION_NOT_FOUND"));
            var scope = Set.of(target.clinicId());
            var actor = authorization.require("UC22", scope).actor();
            var histories = new ArrayList<History>();
            try (var query = c.prepareStatement("""
                    SELECT DISTINCT r.appointment_id FROM adc.appointment_resolution r
                    JOIN adc.appointment a ON a.appointment_id=r.appointment_id
                    JOIN adc.reception_schedule_version booked
                      ON (booked.schedule_id,booked.schedule_version_no)=(a.schedule_id,a.schedule_version_no)
                    WHERE r.schedule_id=? AND r.schedule_version_no=? AND booked.clinic_id=?
                    ORDER BY r.appointment_id
                    """)) {
                query.setLong(1, schedule);
                query.setInt(2, version);
                query.setLong(3, target.clinicId());
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        long appointment = rows.getLong(1);
                        histories.addAll(assess(c, target.clinicId(), appointment, repository.forAppointment(c, schedule, version, appointment),
                                repository.terminalDocuments(c, schedule, version, appointment)));
                    }
                }
            }
            if (!actor.equals(authorization.require("UC22", scope).actor())) {
                throw new SecurityException("RESOLUTION_ACTOR_CHANGED");
            }
            return List.copyOf(histories);
        });
    }

    List<History> forAppointment(long clinic, long schedule, int version, long appointment) {
        var scope = Set.of(clinic);
        var actor = authorization.require("UC22", scope).actor();
        return transactions.execute(c -> {
            new ResourceLockCoordinator().scope(c).schedules(List.of(schedule));
            try (var query = c.prepareStatement("""
                    SELECT 1 FROM adc.reception_schedule_version draft
                    JOIN adc.appointment a ON a.appointment_id=?
                    JOIN adc.reception_schedule_version booked
                      ON (booked.schedule_id,booked.schedule_version_no)=(a.schedule_id,a.schedule_version_no)
                    WHERE draft.schedule_id=? AND draft.schedule_version_no=?
                      AND draft.clinic_id=? AND booked.clinic_id=draft.clinic_id
                    """)) {
                query.setLong(1, appointment);
                query.setLong(2, schedule);
                query.setInt(3, version);
                query.setLong(4, clinic);
                try (var result = query.executeQuery()) {
                    if (!result.next()) throw new SecurityException("RESOLUTION_TARGET_OUT_OF_SCOPE");
                }
            }
            var rows = repository.forAppointment(c, schedule, version, appointment);
            var docs = repository.terminalDocuments(c, schedule, version, appointment);
            var result = assess(c, clinic, appointment, rows, docs);
            if (!actor.equals(authorization.require("UC22", scope).actor())) {
                throw new SecurityException("RESOLUTION_ACTOR_CHANGED");
            }
            return result;
        });
    }

    private List<History> assess(java.sql.Connection connection, long clinic, long appointment,
                                 List<JsonNode> rows, List<JsonNode> documents) throws java.sql.SQLException {
        var result = new ArrayList<History>();
        var verifier = new ResolutionHistoryService();
        for (var history : completeHistory(rows, documents)) {
            var facts = documents.stream()
                    .filter(doc -> Long.toString(history.root()).equals(doc.path("rootResolutionId").asText()))
                    .map(doc -> doc.path("verification"))
                    .filter(doc -> "VERIFIED".equals(doc.path("conclusion").asText()))
                    .map(doc -> doc.path("formalEvidence")).filter(JsonNode::isObject).distinct().toList();
            var head = documents.stream()
                    .filter(doc -> Long.toString(history.head()).equals(doc.path("newResolutionId").asText()))
                    .findFirst().orElseThrow();
            String assessment = "PENDING";
            if ("VERIFIED".equals(head.path("verification").path("conclusion").asText())
                    && head.path("verification").path("formalEvidence").isObject()) {
                try {
                    verifier.verifyFormalEvidence(connection, clinic, appointment, head);
                    assessment = "VERIFIED";
                } catch (CommandRecovery.Rejection stale) {
                    assessment = "STALE";
                }
            }
            result.add(new History(history.root(), history.head(), history.headFingerprint(), history.revisions(),
                    assessment, facts));
        }
        return List.copyOf(result);
    }

    List<History> completeHistory(List<JsonNode> rows, List<JsonNode> documents) {
        var revisions = chain.verifiedRevisions(rows, documents);
        return chain.heads(revisions).values().stream().sorted(Comparator.comparingLong(ResolutionChain.Revision::root))
                .map(head -> {
                    var linked = new java.util.HashMap<Long, ResolutionChain.Revision>();
                    revisions.stream().filter(r -> r.root() == head.root()).forEach(r -> linked.put(r.id(), r));
                    var ordered = new java.util.ArrayList<JsonNode>();
                    for (var cursor = head; cursor != null; cursor = linked.get(cursor.predecessor())) {
                        ordered.add(cursor.snapshot());
                    }
                    return new History(head.root(), head.id(), AuditDocuments.hash(head.snapshot()),
                            List.copyOf(ordered.reversed()));
                }).toList();
    }
}
