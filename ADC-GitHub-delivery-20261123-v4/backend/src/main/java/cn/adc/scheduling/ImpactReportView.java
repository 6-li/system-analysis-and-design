package cn.adc.scheduling;

import cn.adc.shared.audit.AuditDocuments;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

final class ImpactReportView {
    private ImpactReportView() {}

    static JsonNode of(ImpactAssessmentService.Preview preview, Instant evaluatedAt) {
        var target = preview.target();
        var report = AuditDocuments.JSON.createObjectNode();
        report.set("targetRef", reference(target.id(), target.version()));
        var items = report.putArray("items");
        for (var observed : preview.items()) {
            var impact = observed.impact();
            String id = Long.toString(impact.appointmentId());
            boolean unsupported = impact.assessment() == ImpactAssessmentService.Assessment.REQUIRES_PROCESSING;
            String reason = unsupported ? "DRAFT_DOES_NOT_COVER_APPOINTMENT" : "DRAFT_COVERAGE_PENDING_ACTIVATION";
            var item = items.addObject().put("appointmentId", id)
                    .put("observedStateToken", observed.appointmentToken())
                    .put("handlingStatus", unsupported ? "REQUIRES_HANDLING" : "PENDING_VERIFICATION");
            var support = item.putObject("support").put("appointmentId", id)
                    .put("result", unsupported ? "NOT_SUPPORTED" : "UNRESOLVED");
            support.set("originScheduleRef", reference(impact.historicalScheduleId(), impact.historicalVersion()));
            support.putArray("reasons").add(reason);
            item.putArray("reasons").add(reason);
        }
        var conflicts = report.putArray("hiddenConflicts");
        if (preview.hiddenConflict()) conflicts.addObject().put("code", "CROSS_CLINIC_CONFLICT")
                .put("message", "同一牙医存在跨店排班冲突");
        report.put("policyStatus", "UNRESOLVED");
        report.put("checkToken", AuditDocuments.hash(report));
        report.put("asOf", evaluatedAt.toString());
        items.forEach(item -> ((tools.jackson.databind.node.ObjectNode) item.get("support"))
                .put("evaluatedAt", evaluatedAt.toString()));
        return report;
    }

    private static JsonNode reference(long id, int version) {
        return AuditDocuments.JSON.createObjectNode().put("scheduleId", Long.toString(id)).put("versionNo", version);
    }
}
