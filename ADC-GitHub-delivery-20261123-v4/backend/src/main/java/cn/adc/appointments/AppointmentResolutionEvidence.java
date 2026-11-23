package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;

/** Revalidates persisted UC03/04 evidence without exposing appointments outside the authorized clinic. */
public final class AppointmentResolutionEvidence {
    private final AppointmentRepository appointments = new AppointmentRepository();

    public record ActivationState(String token, boolean ordinaryPendingArrival) {}

    public java.util.Optional<ActivationState> activationState(Connection connection, long clinic, long appointmentId)
            throws SQLException {
        var found = appointments.findAppointment(connection, appointmentId, clinic);
        if (found.isEmpty()) return java.util.Optional.empty();
        var row = found.orElseThrow();
        return java.util.Optional.of(new ActivationState(AppointmentReadService.token(row),
                "待到诊".equals(row.status()) && !appointments.hasVisit(connection, row)));
    }

    public java.util.Optional<String> currentToken(Connection connection, long clinic, long appointmentId)
            throws SQLException {
        return appointments.findAppointment(connection, appointmentId, clinic).map(AppointmentReadService::token);
    }

    public boolean confirms(Connection connection, long clinic, long appointmentId, String expectedToken,
                            long eventId, String uc, Long changeId, String cancellationReference) throws SQLException {
        if (!"UC03".equals(uc) && !"UC04".equals(uc)) return false;
        var appointment = appointments.findAppointment(connection, appointmentId, clinic);
        if (appointment.isEmpty()) return false;
        var row = appointment.orElseThrow();
        if (!AppointmentReadService.token(row).equals(expectedToken)
                || appointments.hasVisit(connection, row)) return false;

        try (var query = connection.prepareStatement("""
                SELECT request_reference, operation_name, outcome, evidence, actor_reference
                FROM adc.operation_audit_event WHERE audit_event_id=? AND uc_code=?
                """)) {
            query.setLong(1, eventId);
            query.setString(2, uc);
            try (var event = query.executeQuery()) {
                if (!event.next()) return false;
                var reference = event.getString(1);
                var operation = event.getString(2);
                var actor = event.getString(5);
                if (!"SUCCESS".equals(event.getString(3)) || reference == null) return false;
                var document = AuditDocuments.parse(event.getString(4));
                if (!isCommittedResult(document)) return false;
                var saved = document.path("businessResultSnapshot");
                if (!Long.toString(appointmentId).equals(saved.path("after").path("appointmentId").asText())
                        || !saved.path("after").equals(saved.path("businessReference"))) return false;
                if ("UC04".equals(uc)) {
                    return changeId == null && "APPOINTMENT_CANCEL".equals(operation)
                            && reference.equals(cancellationReference)
                            && row.actor().equals(actor)
                            && AppointmentCancellationService.matchesSnapshot(row, saved, reference);
                }
                if (!"APPOINTMENT_CHANGE".equals(operation) || changeId == null || !"待到诊".equals(row.status())
                        || !Long.toString(changeId).equals(saved.path("after").path("appointmentChangeId").asText())) return false;
                var history = appointments.findChange(connection, row, changeId);
                return history.filter(change -> reference.equals(change.evidence().requestReference())
                        && change.evidence().actor().equals(row.actor())
                        && change.evidence().actor().equals(actor)
                        && change.evidence().changedAt().equals(row.operatedAt())
                        && change.evidence().acceptance().equals(row.acceptance())
                        && change.after().equals(row.terms())
                        && AppointmentChangeService.fingerprint(change)
                                .equals(saved.path("verification").path("historyFingerprint").asText())).isPresent();
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
    }

    static boolean isCommittedResult(JsonNode document) {
        return document != null && "BUSINESS_RESULT".equals(document.path("eventPhase").asText())
                && "YES".equals(document.path("confirmedCommit").asText());
    }
}
