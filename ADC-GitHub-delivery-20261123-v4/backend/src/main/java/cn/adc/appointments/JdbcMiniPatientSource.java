package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;

/** Uses the caller's UC01 transaction for the request association and extension audit. */
final class JdbcMiniPatientSource implements PatientMaintenanceService.MiniSource {
    interface Verification {
        void require(Connection c, String actor, long clinic, Long patient, JsonNode input) throws SQLException;
    }

    private final Verification verification;
    private final Clock clock;

    JdbcMiniPatientSource(Verification verification, Clock clock) {
        this.verification = java.util.Objects.requireNonNull(verification);
        this.clock = clock;
    }

    @Override
    public void requireCurrent(Connection c, String actor, long clinic, Long patient, JsonNode input) throws SQLException {
        var source = input.get("source");
        try (var p = c.prepareStatement("""
                SELECT 1 FROM adc_mini.preliminary_request
                WHERE request_id=? AND submitted_content->>'intendedClinicId'=?
                  AND request_version=? AND associated_patient_id IS NULL FOR UPDATE
                """)) {
            p.setObject(1, UUID.fromString(AuditDocuments.text(source, "requestId")));
            p.setString(2, Long.toString(clinic));
            p.setInt(3, source.path("expectedRequestVersion").intValue());
            try (var rows = p.executeQuery()) {
                if (!rows.next()) throw new CommandRecovery.Rejection("MINI_PATIENT_SOURCE_STALE");
            }
        }
        RequestDecisions.requireOpen(c,UUID.fromString(AuditDocuments.text(source,"requestId")));
        verification.require(c, actor, clinic, patient, input);
    }

    @Override
    public JsonNode associate(Connection c, String actor, long clinic, long patient, JsonNode input,
                              String originalRequestReference) throws SQLException {
        var source = input.get("source");
        UUID request = UUID.fromString(AuditDocuments.text(source, "requestId"));
        UUID event = UUID.randomUUID();
        int next = Math.addExact(source.path("expectedRequestVersion").intValue(), 1);
        var time = StrictInputPolicy.eventTime(clock);
        try (var p = c.prepareStatement("""
                UPDATE adc_mini.preliminary_request SET associated_patient_id=?, request_version=?
                WHERE request_id=? AND request_version=? AND associated_patient_id IS NULL
                  AND submitted_content->>'intendedClinicId'=?
                """)) {
            p.setLong(1, patient);
            p.setInt(2, next);
            p.setObject(3, request);
            p.setInt(4, next - 1);
            p.setString(5, Long.toString(clinic));
            if (p.executeUpdate() != 1) throw new CommandRecovery.Rejection("MINI_PATIENT_SOURCE_STALE");
        }
        try (var p = c.prepareStatement("""
                INSERT INTO adc_mini.operation_audit_event
                    (request_id, operation, event_at, event_id, original_request_reference)
                VALUES (?, 'UC01_ASSOCIATED', ?, ?, ?)
                """)) {
            p.setObject(1, request);
            p.setObject(2, time.atOffset(java.time.ZoneOffset.UTC));
            p.setObject(3, event);
            p.setString(4, originalRequestReference);
            if (p.executeUpdate() != 1) throw new SQLException("MINI_ASSOCIATION_AUDIT_UNCONFIRMED", "P0001");
        }
        var result = AuditDocuments.JSON.createObjectNode();
        result.putObject("sourceAssociation").put("requestId", request.toString()).put("patientId", Long.toString(patient))
                .put("associationReference", event.toString()).put("version", next)
                .put("patientAccessGrantedByAssociation", false).putObject("associatedBy")
                .put("principalReference", actor).put("actingMode", "STAFF").put("occurredAt", time.toString());
        result.putObject("extensionResultEvent").put("source", "MINI_EXTENSION").put("role", "COMMITTED_RESULT")
                .put("eventId", event.toString()).put("commandReference", originalRequestReference);
        return result;
    }

    @Override
    public boolean matchesCommitted(Connection c, String actor, long clinic, long patient, JsonNode input,
                                    JsonNode result) throws SQLException {
        verification.require(c, actor, clinic, patient, input);
        var association = result.path("sourceAssociation");
        var event = result.path("extensionResultEvent");
        if (!input.path("source").path("requestId").equals(association.path("requestId"))
                || !Long.toString(patient).equals(association.path("patientId").asText())
                || !actor.equals(association.path("associatedBy").path("principalReference").asText())
                || !association.path("associationReference").equals(event.path("eventId"))) return false;
        try (var p = c.prepareStatement("""
                SELECT 1 FROM adc_mini.preliminary_request r
                JOIN adc_mini.operation_audit_event a ON a.request_id=r.request_id
                WHERE r.request_id=? AND r.associated_patient_id=? AND r.request_version>=?
                  AND r.submitted_content->>'intendedClinicId'=? AND a.operation='UC01_ASSOCIATED'
                  AND a.event_id=? AND a.original_request_reference=?
                """)) {
            p.setObject(1, UUID.fromString(association.path("requestId").asText()));
            p.setLong(2, patient);
            p.setInt(3, association.path("version").asInt());
            p.setString(4, Long.toString(clinic));
            p.setObject(5, UUID.fromString(event.path("eventId").asText()));
            p.setString(6, event.path("commandReference").asText());
            try (var rows = p.executeQuery()) { return rows.next(); }
        }
    }
}
