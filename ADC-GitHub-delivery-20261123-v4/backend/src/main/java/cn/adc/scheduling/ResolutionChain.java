package cn.adc.scheduling;

import cn.adc.shared.audit.AuditDocuments;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Verifies a complete persisted revision set by links and fingerprints. */
final class ResolutionChain {
    record Revision(long id, long root, Long predecessor, JsonNode snapshot, String previousFingerprint) {}

    /** Joins all persisted rows with their already authenticated terminal audit documents. */
    List<Revision> verifiedRevisions(List<JsonNode> rows, List<JsonNode> terminalDocuments) {
        var byId = new HashMap<String, JsonNode>();
        for (var row : rows) {
            AuditDocuments.resolutionSnapshot(row);
            if (byId.putIfAbsent(row.get("resolution_id").asText(), row) != null) throw invalid();
        }
        var seen = new HashSet<String>();
        var revisions = new ArrayList<Revision>();
        for (var document : terminalDocuments) {
            AuditDocuments.revision(document);
            var id = document.get("newResolutionId").asText();
            if (!seen.add(id) || !document.get("after").equals(byId.get(id))) throw invalid();
            var predecessor = document.get("predecessorResolutionId");
            var before = document.get("before");
            revisions.add(new Revision(Long.parseLong(id), Long.parseLong(document.get("rootResolutionId").asText()),
                    predecessor.isNull() ? null : Long.parseLong(predecessor.asText()), byId.get(id).deepCopy(),
                    before.isNull() ? null : AuditDocuments.hash(before)));
        }
        if (seen.size() != byId.size()) throw invalid();
        heads(revisions);
        return List.copyOf(revisions);
    }

    Map<Long, Revision> heads(List<Revision> complete) {
        var byId = new HashMap<Long, Revision>();
        var next = new HashMap<Long, Revision>();
        for (var row : complete) {
            if (row.id() <= 0 || row.root() <= 0 || row.snapshot() == null || !row.snapshot().isObject()
                    || byId.putIfAbsent(row.id(), row) != null) throw invalid();
            if (row.predecessor() == null) {
                if (row.id() != row.root() || row.previousFingerprint() != null) throw invalid();
            } else if (next.putIfAbsent(row.predecessor(), row) != null) throw invalid();
        }
        for (var row : complete) {
            var root = byId.get(row.root());
            if (root == null || root.predecessor() != null) throw invalid();
            if (row.predecessor() == null) continue;
            var prior = byId.get(row.predecessor());
            if (prior == null || prior.root() != row.root()
                    || !AuditDocuments.hash(prior.snapshot()).equals(row.previousFingerprint())) throw invalid();
            for (String field : List.of("schedule_id", "schedule_version_no", "appointment_id")) {
                if (row.snapshot().path(field).isMissingNode()
                        || !row.snapshot().path(field).equals(prior.snapshot().path(field))) throw invalid();
            }
        }
        var visited = new HashSet<Long>();
        var heads = new HashMap<Long, Revision>();
        for (var row : complete) {
            if (row.predecessor() != null) continue;
            var cursor = row;
            while (true) {
                if (!visited.add(cursor.id())) throw invalid();
                var child = next.get(cursor.id());
                if (child == null) break;
                cursor = child;
            }
            heads.put(row.id(), cursor);
        }
        if (visited.size() != complete.size()) throw invalid();
        return Map.copyOf(heads);
    }

    Optional<Revision> expectedHead(List<Revision> complete, long root, long expectedId, String expectedFingerprint) {
        var head = heads(complete).get(root);
        return head != null && head.id() == expectedId && AuditDocuments.hash(head.snapshot()).equals(expectedFingerprint)
                ? Optional.of(head) : Optional.empty();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_RESOLUTION_CHAIN");
    }
}
