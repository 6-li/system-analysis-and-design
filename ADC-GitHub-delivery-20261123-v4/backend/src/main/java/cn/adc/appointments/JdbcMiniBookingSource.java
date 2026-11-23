package cn.adc.appointments;

import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.time.StrictInputPolicy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/** Adapter for the candidate mini schema used in isolated validation. No application registration. */
final class JdbcMiniBookingSource implements FormalBookingService.MiniSource {
    record Context(UUID requestId, int requestVersion) {}

    interface Contexts {
        /** Resolve the opaque reference to the verified request, including category and accepted scheme. */
        Context require(Connection c, String reference, InitialBookingReview.AcceptedScheme scheme)
                throws SQLException;

        /** Resolve the original reference even after an unrelated later revision. */
        UUID originalRequest(Connection c, String reference) throws SQLException;
    }

    private final Contexts contexts;
    private final Clock clock;

    JdbcMiniBookingSource(Contexts contexts, Clock clock) {
        this.contexts = Objects.requireNonNull(contexts);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void requireCurrent(Connection c, String reference, InitialBookingReview.AcceptedScheme scheme)
            throws SQLException {
        var context = contexts.require(c, reference, scheme);
        try (var p = c.prepareStatement("""
                SELECT 1 FROM adc_mini.preliminary_request r
                WHERE r.request_id=? AND r.request_version=? AND r.associated_patient_id=?
                  AND r.submitted_content->>'type'='NEW'
                  AND r.submitted_content->>'intendedClinicId'=?
                  AND NOT EXISTS (SELECT 1 FROM adc_mini.operation_audit_event a
                                   WHERE a.request_id=r.request_id AND a.operation='UC02_BOOKED')
                FOR UPDATE OF r
                """)) {
            p.setObject(1, context.requestId());
            p.setInt(2, context.requestVersion());
            p.setLong(3, scheme.patientId());
            p.setString(4, Long.toString(scheme.clinicId()));
            try (var rows = p.executeQuery()) {
                if (!rows.next()) throw new CommandRecovery.Rejection("MINI_BOOKING_SOURCE_STALE");
            }
        }
        RequestDecisions.requireOpen(c,context.requestId());
        if (!context.equals(contexts.require(c, reference, scheme))) {
            throw new CommandRecovery.Rejection("MINI_BOOKING_SOURCE_STALE");
        }
    }

    @Override
    public void linkCommittedResult(Connection c, String reference, AppointmentRepository.Appointment appointment,
                                    String requestReference) throws SQLException {
        try (var p = c.prepareStatement("""
                INSERT INTO adc_mini.operation_audit_event
                    (request_id, operation, event_at, event_id, original_request_reference)
                VALUES (?, 'UC02_BOOKED', ?, ?, ?)
                """)) {
            p.setObject(1, contexts.originalRequest(c, reference));
            p.setObject(2, StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC));
            p.setObject(3, UUID.randomUUID());
            p.setString(4, requestReference);
            if (p.executeUpdate() != 1) throw new SQLException("MINI_BOOKING_LINK_UNCONFIRMED", "P0001");
        }
    }

    @Override
    public boolean matchesCommitted(Connection c, String reference, long appointmentId, String requestReference)
            throws SQLException {
        try (var p = c.prepareStatement("""
                SELECT 1 FROM adc_mini.operation_audit_event a
                JOIN adc.operation_audit_event uc ON uc.request_reference=a.original_request_reference
                WHERE a.request_id=? AND a.operation='UC02_BOOKED' AND a.event_id IS NOT NULL
                  AND uc.request_reference=? AND uc.uc_code='UC02'
                  AND uc.operation_name='APPOINTMENT_CREATE' AND uc.outcome='SUCCESS'
                  AND uc.evidence::jsonb #>> '{businessResultSnapshot,businessReference,appointmentId}'=?
                """)) {
            p.setObject(1, contexts.originalRequest(c, reference));
            p.setString(2, requestReference);
            p.setString(3, Long.toString(appointmentId));
            try (var rows = p.executeQuery()) { return rows.next() && !rows.next(); }
        }
    }
}
