package cn.adc.scheduling;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Reads complete table 28 chains and persists new records only. */
final class ResolutionRepository {
    private static final String FIELDS = """
            resolution_id,schedule_id,schedule_version_no,appointment_id,resolution_subject,resolution_result,
            resolution_basis,responsible_party,pending_confirmation,recorded_at,appointment_change_id
            """;

    List<JsonNode> forAppointment(Connection c, long schedule, int version, long appointment) throws SQLException {
        try (var p = c.prepareStatement("SELECT " + FIELDS + """
                FROM adc.appointment_resolution
                WHERE schedule_id=? AND schedule_version_no=? AND appointment_id=?
                """)) {
            p.setLong(1, schedule);
            p.setInt(2, version);
            p.setLong(3, appointment);
            var result = new ArrayList<JsonNode>();
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    var row = AuditDocuments.JSON.createObjectNode()
                            .put("resolution_id", r.getString(1)).put("schedule_id", r.getString(2))
                            .put("schedule_version_no", r.getInt(3)).put("appointment_id", r.getString(4))
                            .put("resolution_subject", r.getString(5)).put("resolution_result", r.getString(6))
                            .put("resolution_basis", r.getString(7)).put("responsible_party", r.getString(8))
                            .put("pending_confirmation", r.getString(9))
                            .put("recorded_at", r.getObject(10, OffsetDateTime.class).toInstant().toString())
                            .put("appointment_change_id", r.getString(11));
                    AuditDocuments.resolutionSnapshot(row);
                    result.add(row);
                }
            }
            return List.copyOf(result);
        }
    }

    /** Reads committed terminal documents by persisted target, within the caller's locked transaction. */
    List<JsonNode> terminalDocuments(Connection c, long schedule, int version, long appointment) throws SQLException {
        try (var p = c.prepareStatement("""
                SELECT evidence, request_reference, operation_name, business_reference FROM adc.operation_audit_event
                WHERE uc_code='UC22' AND outcome='SUCCESS'
                  AND operation_name IN ('RESOLUTION_CREATE','RESOLUTION_APPEND')
                  AND business_reference::jsonb ->> 'scheduleId'=?
                  AND business_reference::jsonb ->> 'versionNo'=?
                """)) {
            p.setString(1, Long.toString(schedule));
            p.setString(2, Integer.toString(version));
            var documents = new ArrayList<JsonNode>();
            try (var result = p.executeQuery()) {
                while (result.next()) {
                    var evidence = AuditDocuments.parse(result.getString(1));
                    var document = terminalDocument(evidence, result.getString(2), result.getString(3),
                            AuditDocuments.parse(result.getString(4)));
                    if (document.path("after").path("appointment_id").asText().equals(Long.toString(appointment))) {
                        documents.add(document);
                    }
                }
            }
            return List.copyOf(documents);
        }
    }

    static JsonNode terminalDocument(JsonNode evidence, String request, String operation, JsonNode reference) {
        boolean wrapped = evidence.has("eventPhase");
        var snapshot = evidence.path("businessResultSnapshot");
        var document = wrapped ? snapshot.path("verification") : evidence;
        AuditDocuments.revision(document);
        var draft = document.get("targetDraftRef");
        if (!document.get("requestReference").asText().equals(request)
                || !document.get("operationKind").asText().equals(operation)
                || !draft.get("scheduleId").equals(reference.path("scheduleId"))
                || !draft.get("versionNo").equals(reference.path("versionNo"))
                || (wrapped && (!"BUSINESS_RESULT".equals(evidence.path("eventPhase").asText())
                    || !"YES".equals(evidence.path("confirmedCommit").asText())
                    || !evidence.path("canonicalPayloadHash").equals(document.get("canonicalPayloadHash"))
                    || !evidence.path("operationKind").equals(document.get("operationKind"))
                    || !document.get("after").equals(snapshot.path("after"))
                    || !document.get("before").equals(snapshot.path("before"))
                    || !reference.equals(snapshot.path("businessReference"))
                    || !document.get("newResolutionId").equals(reference.path("resolutionId"))))) {
            throw new IllegalArgumentException("INVALID_RESOLUTION_TERMINAL");
        }
        return document;
    }

    void append(Connection c, JsonNode row) throws SQLException {
        if (c.getAutoCommit()) throw new SQLException("TRANSACTION_REQUIRED", "25000");
        AuditDocuments.resolutionSnapshot(row);
        try (var p = c.prepareStatement("INSERT INTO adc.appointment_resolution (" + FIELDS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            p.setLong(1, Long.parseLong(row.get("resolution_id").asText()));
            p.setLong(2, Long.parseLong(row.get("schedule_id").asText()));
            p.setInt(3, row.get("schedule_version_no").intValue());
            p.setLong(4, Long.parseLong(row.get("appointment_id").asText()));
            p.setString(5, row.get("resolution_subject").asText());
            p.setString(6, row.get("resolution_result").asText());
            p.setString(7, row.get("resolution_basis").asText(null));
            p.setString(8, row.get("responsible_party").asText());
            p.setString(9, row.get("pending_confirmation").asText(null));
            p.setObject(10, StrictInputPolicy.instant(row.get("recorded_at").asText()).atOffset(ZoneOffset.UTC));
            if (row.get("appointment_change_id").isNull()) p.setNull(11, Types.BIGINT);
            else p.setLong(11, Long.parseLong(row.get("appointment_change_id").asText()));
            if (p.executeUpdate() != 1) throw new SQLException("RESOLUTION_APPEND_NOT_CONFIRMED", "P0001");
        }
    }
}
