package cn.adc.appointments;

import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.time.StrictInputPolicy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/** Candidate mini request adapter for isolated UC03 verification. */
final class JdbcMiniChangeSource implements AppointmentChangeService.MiniSource {
    record Context(UUID requestId, int version) {}

    interface Contexts {
        Context require(Connection connection, String reference, AppointmentRepository.Appointment original,
                        InitialBookingReview.AcceptedScheme scheme, String acceptanceReference) throws SQLException;
        UUID originalRequest(Connection connection, String reference) throws SQLException;
    }

    private final Contexts contexts;
    private final Clock clock;

    JdbcMiniChangeSource(Contexts contexts, Clock clock) {
        this.contexts = Objects.requireNonNull(contexts);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void requireCurrent(Connection c, String reference, AppointmentRepository.Appointment original,
                               InitialBookingReview.AcceptedScheme scheme, String acceptanceReference) throws SQLException {
        var context = contexts.require(c, reference, original, scheme, acceptanceReference);
        try (var query = c.prepareStatement("""
                SELECT 1 FROM adc_mini.preliminary_request
                WHERE request_id=? AND request_version=? AND associated_patient_id=?
                  AND submitted_content->>'type'='CHANGE'
                  AND submitted_content->>'originalAppointmentId'=?
                FOR UPDATE
                """)) {
            query.setObject(1, context.requestId());
            query.setInt(2, context.version());
            query.setLong(3, original.patientId());
            query.setString(4, Long.toString(original.id()));
            try (var result = query.executeQuery()) {
                if (!result.next()) throw new Rejection("MINI_CHANGE_SOURCE_STALE");
            }
        }
        RequestDecisions.requireOpen(c,context.requestId());
        if (!context.equals(contexts.require(c, reference, original, scheme, acceptanceReference))
                || !context.requestId().equals(contexts.originalRequest(c, reference))) {
            throw new Rejection("MINI_CHANGE_SOURCE_STALE");
        }
    }

    @Override
    public void linkCommittedResult(Connection c, String reference, AppointmentRepository.Change change) throws SQLException {
        try (var insert = c.prepareStatement("""
                INSERT INTO adc_mini.operation_audit_event
                    (request_id, operation, event_at, event_id, original_request_reference)
                VALUES (?, 'UC03_CHANGED', ?, ?, ?)
                """)) {
            insert.setObject(1, contexts.originalRequest(c, reference));
            insert.setObject(2, StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC));
            insert.setObject(3, UUID.randomUUID());
            insert.setString(4, change.evidence().requestReference());
            if (insert.executeUpdate() != 1) throw new SQLException("MINI_CHANGE_LINK_UNCONFIRMED", "P0001");
        }
    }

    @Override
    public boolean matchesCommitted(Connection c, String reference, AppointmentRepository.Change change) throws SQLException {
        try (var query = c.prepareStatement("""
                SELECT 1 FROM adc_mini.operation_audit_event a
                JOIN adc.operation_audit_event uc ON uc.request_reference=a.original_request_reference
                WHERE a.request_id=? AND a.operation='UC03_CHANGED' AND a.event_id IS NOT NULL
                  AND uc.request_reference=? AND uc.uc_code='UC03'
                  AND uc.operation_name='APPOINTMENT_CHANGE' AND uc.outcome='SUCCESS'
                  AND uc.evidence::jsonb #>> '{businessResultSnapshot,businessReference,appointmentId}'=?
                  AND uc.evidence::jsonb #>> '{businessResultSnapshot,businessReference,appointmentChangeId}'=?
                """)) {
            query.setObject(1, contexts.originalRequest(c, reference));
            query.setString(2, change.evidence().requestReference());
            query.setString(3, Long.toString(change.appointmentId()));
            query.setString(4, Long.toString(change.evidence().id()));
            try (var result = query.executeQuery()) { return result.next() && !result.next(); }
        }
    }
}
