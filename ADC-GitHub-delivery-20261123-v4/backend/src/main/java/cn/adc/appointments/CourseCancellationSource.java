package cn.adc.appointments;
import java.sql.*;
import java.util.UUID;
import cn.adc.shared.audit.CommandRecovery.Rejection;
final class CourseCancellationSource implements JdbcMiniCancellationSource.Contexts {
    public JdbcMiniCancellationSource.Context require(Connection c,String reference,AppointmentRepository.Appointment original,String verified)throws SQLException{
        var source=CourseMiniBookingContext.parseExisting(reference);
        try(var q=c.prepareStatement("""
            SELECT 1 FROM adc_mini.preliminary_request r
            JOIN adc_mini.patient_account a ON r.applicant_account=('ACCOUNT:'||a.account_id::text)
            JOIN adc_mini.patient_account_binding b ON b.account_id=a.account_id AND b.patient_id=r.associated_patient_id
            WHERE r.request_id=? AND r.request_version=? AND r.associated_patient_id=? AND NOT a.disabled
              AND r.submitted_content->>'type'='CANCEL' AND r.submitted_content->>'actingMode'='SELF'
              AND r.submitted_content->>'originalAppointmentId'=? AND r.submitted_content->>'patientId'=b.patient_id::text
            """)){
            q.setObject(1,source.requestId());q.setInt(2,source.requestVersion());q.setLong(3,original.patientId());q.setString(4,Long.toString(original.id()));
            try(var r=q.executeQuery()){if(!r.next()||r.next())throw new Rejection("MINI_CANCEL_SOURCE_STALE");}
        }
        return new JdbcMiniCancellationSource.Context(source.requestId(),source.requestVersion());
    }
    public UUID originalRequest(Connection c,String reference){return CourseMiniBookingContext.parseExisting(reference).requestId();}
}
