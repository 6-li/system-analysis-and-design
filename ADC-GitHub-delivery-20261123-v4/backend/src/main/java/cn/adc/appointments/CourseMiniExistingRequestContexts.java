package cn.adc.appointments;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import java.sql.*;
import java.util.UUID;

/** Synthetic adapter only. Actual patient acceptance/cancellation verification remains in each UC service. */
final class CourseMiniExistingRequestContexts implements JdbcMiniChangeSource.Contexts,JdbcMiniCancellationSource.Contexts {
    private final String account;
    CourseMiniExistingRequestContexts(String account){
        if(account==null||!account.startsWith("SYNTHETIC"))throw new IllegalArgumentException("SYNTHETIC_ACCOUNT_REQUIRED");
        this.account=account;
    }
    private JdbcMiniBookingSource.Context require(Connection c,String ref,String type,AppointmentRepository.Appointment original)throws SQLException{
        var source=CourseMiniBookingContext.parseExisting(ref);
        try(var p=c.prepareStatement("""
          SELECT 1 FROM adc_mini.preliminary_request r WHERE request_id=? AND request_version=? AND applicant_account=?
            AND associated_patient_id=? AND submitted_content->>'type'=?
            AND submitted_content->>'patientId'=? AND submitted_content->>'originalAppointmentId'=?
          """)){
            p.setObject(1,source.requestId());p.setInt(2,source.requestVersion());p.setString(3,account);
            p.setLong(4,original.patientId());p.setString(5,type);p.setString(6,Long.toString(original.patientId()));p.setString(7,Long.toString(original.id()));
            try(var rows=p.executeQuery()){if(!rows.next()||rows.next())throw new Rejection("MINI_REQUEST_SOURCE_STALE");}
        }
        return source;
    }
    @Override public JdbcMiniChangeSource.Context require(Connection c,String ref,AppointmentRepository.Appointment original,
      InitialBookingReview.AcceptedScheme scheme,String acceptance)throws SQLException{
        var value=require(c,ref,"CHANGE",original);return new JdbcMiniChangeSource.Context(value.requestId(),value.requestVersion());
    }
    @Override public JdbcMiniCancellationSource.Context require(Connection c,String ref,AppointmentRepository.Appointment original,String verified)throws SQLException{
        var value=require(c,ref,"CANCEL",original);return new JdbcMiniCancellationSource.Context(value.requestId(),value.requestVersion());
    }
    @Override public UUID originalRequest(Connection c,String ref){return CourseMiniBookingContext.parseExisting(ref).requestId();}
}
