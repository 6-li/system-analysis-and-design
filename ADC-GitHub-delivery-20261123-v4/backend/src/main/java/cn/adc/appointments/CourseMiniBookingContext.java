package cn.adc.appointments;

import cn.adc.shared.audit.CommandRecovery.Rejection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Resolves a synthetic request only after staff association. It confers no patient read grant. */
final class CourseMiniBookingContext implements JdbcMiniBookingSource.Contexts {
    static final String PREFIX = "SYNTHETIC-course-request:";
    private final String account;
    private boolean patientAccounts;
    CourseMiniBookingContext withPatientAccounts(boolean enabled){patientAccounts=enabled;return this;}
    CourseMiniBookingContext(){this.account=null;}
    CourseMiniBookingContext(String account){
        if(account==null||!account.startsWith("SYNTHETIC"))throw new IllegalArgumentException("SYNTHETIC_ACCOUNT_REQUIRED");
        this.account=account;
    }

    @Override
    public JdbcMiniBookingSource.Context require(Connection c, String reference,
                                                 InitialBookingReview.AcceptedScheme scheme) throws SQLException {
        var context = parse(reference);
        try (var query = c.prepareStatement("""
                SELECT r.submitted_content::text FROM adc_mini.preliminary_request r
                WHERE r.request_id=? AND r.request_version=? AND r.associated_patient_id=?
                  AND r.submitted_content->>'type'='NEW'
                  AND r.submitted_content->>'intendedClinicId'=?
                  AND (CAST(? AS text) IS NULL OR r.applicant_account=?)
                  AND (EXISTS (SELECT 1 FROM adc_mini.operation_audit_event e
                      WHERE e.request_id=r.request_id AND e.operation='UC01_ASSOCIATED'
                        AND e.event_id IS NOT NULL AND e.original_request_reference IS NOT NULL)
                    %s)
                """.formatted(patientAccounts?"""
                    OR EXISTS (SELECT 1 FROM adc_mini.patient_account_binding b
                      JOIN adc_mini.patient_account a USING(account_id)
                      WHERE r.applicant_account=('ACCOUNT:' || a.account_id::text)
                        AND b.patient_id=r.associated_patient_id AND NOT a.disabled)
                """:""))) {
            query.setObject(1, context.requestId());
            query.setInt(2, context.requestVersion());
            query.setLong(3, scheme.patientId());
            query.setString(4, Long.toString(scheme.clinicId()));
            query.setString(5,account);
            query.setString(6,account);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new Rejection("MINI_BOOKING_SOURCE_STALE");
                MiniSelectedScheme.requireMatches(cn.adc.shared.audit.AuditDocuments.parse(rows.getString(1)),scheme);
                if(rows.next())throw new Rejection("MINI_BOOKING_SOURCE_STALE");
            }
        }
        return context;
    }

    @Override
    public UUID originalRequest(Connection c, String reference) {
        return parse(reference).requestId();
    }

    static JdbcMiniBookingSource.Context parse(String reference) {
        return parse(reference,false);
    }
    static JdbcMiniBookingSource.Context parseExisting(String reference){return parse(reference,true);}
    private static JdbcMiniBookingSource.Context parse(String reference,boolean zeroAllowed) {
        if (reference == null || !reference.startsWith(PREFIX)) throw new Rejection("INVALID_MINI_BOOKING_SOURCE");
        String[] parts = reference.substring(PREFIX.length()).split(":", -1);
        try {
            if (parts.length != 2) throw new IllegalArgumentException();
            UUID id = UUID.fromString(parts[0]);
            int version = Integer.parseInt(parts[1]);
            if (!id.toString().equals(parts[0]) || version < (zeroAllowed?0:1) || !Integer.toString(version).equals(parts[1])) {
                throw new IllegalArgumentException();
            }
            return new JdbcMiniBookingSource.Context(id, version);
        } catch (IllegalArgumentException invalid) {
            throw new Rejection("INVALID_MINI_BOOKING_SOURCE");
        }
    }
}
