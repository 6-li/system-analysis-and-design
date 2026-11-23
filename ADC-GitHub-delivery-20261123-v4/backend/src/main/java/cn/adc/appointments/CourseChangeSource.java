package cn.adc.appointments;

import cn.adc.shared.audit.CommandRecovery.Rejection;
import java.sql.*;
import java.util.UUID;

/** Account-owned course CHANGE requests; identity is resolved from the saved request, never supplied by staff. */
final class CourseChangeSource implements JdbcMiniChangeSource.Contexts {
    static void require(Connection c, String reference, long patient, Long appointment) throws SQLException {
        var source=CourseMiniBookingContext.parseExisting(reference);
        try(var p=c.prepareStatement("""
            SELECT r.associated_patient_id,r.submitted_content->>'originalAppointmentId'
            FROM adc_mini.preliminary_request r
            JOIN adc_mini.patient_account a ON r.applicant_account=('ACCOUNT:'||a.account_id::text)
            JOIN adc_mini.patient_account_binding b ON b.account_id=a.account_id AND b.patient_id=r.associated_patient_id
            JOIN adc.appointment x ON x.appointment_id::text=r.submitted_content->>'originalAppointmentId'
              AND x.patient_id=b.patient_id
            JOIN adc.reception_schedule_version s ON (s.schedule_id,s.schedule_version_no)=(x.schedule_id,x.schedule_version_no)
            WHERE r.request_id=? AND r.request_version=? AND NOT a.disabled
              AND r.submitted_content->>'type'='CHANGE' AND r.submitted_content->>'actingMode'='SELF'
              AND r.submitted_content->>'patientId'=b.patient_id::text AND s.clinic_id=81001
            """)) {
            p.setObject(1,source.requestId());p.setInt(2,source.requestVersion());
            try(var rows=p.executeQuery()) {
                if(!rows.next() || patient!=0&&rows.getLong(1)!=patient
                    || appointment!=null&&!Long.toString(appointment).equals(rows.getString(2)) || rows.next())
                    throw new Rejection("MINI_CHANGE_SOURCE_STALE");
            }
        }
    }
    @Override public JdbcMiniChangeSource.Context require(Connection c,String reference,AppointmentRepository.Appointment original,
        InitialBookingReview.AcceptedScheme scheme,String acceptance)throws SQLException {
        require(c,reference,original.patientId(),original.id());
        var value=CourseMiniBookingContext.parseExisting(reference);
        if(RequestDecisions.ready(c)){
            try(var p=c.prepareStatement("SELECT submitted_content::text FROM adc_mini.preliminary_request WHERE request_id=?")){
                p.setObject(1,value.requestId());try(var r=p.executeQuery()){if(!r.next())throw new Rejection("MINI_CHANGE_SOURCE_STALE");MiniChangeSelection.requireMatches(cn.adc.shared.audit.AuditDocuments.parse(r.getString(1)),original,scheme);}
            }
        }
        return new JdbcMiniChangeSource.Context(value.requestId(),value.requestVersion());
    }
    @Override public UUID originalRequest(Connection c,String reference){return CourseMiniBookingContext.parseExisting(reference).requestId();}
}
