package cn.adc.appointments;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Called only inside the reception-authorized transaction after explicit identity verification. */
final class PatientAccountBinding {
    void bind(Connection c,UUID request,int version,long clinic,long patient,String actor,String basis) throws SQLException {
        if(!"SYNTHETIC reception".equals(actor)||basis==null||basis.isBlank()||basis.length()>2000
                ||clinic<=0||patient<=0||version<1)throw new SecurityException("ACCOUNT_BINDING_VERIFICATION_REQUIRED");
        UUID account;
        try(var q=c.prepareStatement("""
            SELECT r.applicant_account FROM adc_mini.preliminary_request r
            WHERE r.request_id=? AND r.request_version=? AND r.associated_patient_id=?
              AND r.submitted_content->>'intendedClinicId'=? AND r.submitted_content->>'type'='NEW'
              AND EXISTS(SELECT 1 FROM adc_mini.operation_audit_event e
                JOIN adc.operation_audit_event uc ON uc.request_reference=e.original_request_reference
                WHERE e.request_id=r.request_id AND e.operation='UC01_ASSOCIATED'
                  AND e.event_id IS NOT NULL AND uc.uc_code='UC01' AND uc.outcome='SUCCESS')
            FOR UPDATE OF r
            """)) {
            q.setObject(1,request);q.setInt(2,version);q.setLong(3,patient);q.setString(4,Long.toString(clinic));
            try(var r=q.executeQuery()) {
                if(!r.next()||(account=JdbcPatientAccountScope.accountId(r.getString(1)))==null)
                    throw new SecurityException("ACCOUNT_BINDING_SOURCE_UNVERIFIED");
            }
        }
        try(var q=c.prepareStatement("""
            INSERT INTO adc_mini.patient_account_binding
              (account_id,patient_id,verified_staff,verification_basis,verified_request_id)
            SELECT account_id,?,?,?,? FROM adc_mini.patient_account WHERE account_id=? AND NOT disabled
            ON CONFLICT (account_id) DO NOTHING
            """)) {
            q.setLong(1,patient);q.setString(2,actor);q.setString(3,basis);q.setObject(4,request);q.setObject(5,account);
            if(q.executeUpdate()==1)return;
        }
        try(var q=c.prepareStatement("""
            SELECT 1 FROM adc_mini.patient_account_binding b JOIN adc_mini.patient_account a USING(account_id)
            WHERE b.account_id=? AND b.patient_id=? AND b.verified_request_id=? AND NOT a.disabled
            """)) {
            q.setObject(1,account);q.setLong(2,patient);q.setObject(3,request);
            try(var r=q.executeQuery()){if(!r.next())throw new SecurityException("ACCOUNT_BINDING_CONFLICT");}
        }
    }
}
