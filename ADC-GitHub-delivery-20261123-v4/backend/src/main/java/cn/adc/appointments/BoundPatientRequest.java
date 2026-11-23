package cn.adc.appointments;
import java.sql.*;
import tools.jackson.databind.JsonNode;
/** Reuses an explicitly verified SELF file; never guesses by submitted name/contact. */
final class BoundPatientRequest {
    static Long resolve(Connection c,String account,JsonNode input)throws SQLException {
        var id=JdbcPatientAccountScope.accountId(account);
        if(id==null)throw new SecurityException("ACCOUNT_SESSION_REQUIRED");
        try(var q=c.prepareStatement("""
            SELECT b.patient_id,p.identity_information FROM adc_mini.patient_account_binding b
            JOIN adc_mini.patient_account a USING(account_id) JOIN adc.patient p ON p.patient_id=b.patient_id
            WHERE a.account_id=? AND NOT a.disabled
            """)) {
            q.setObject(1,id);
            try(var r=q.executeQuery()) {
                if(!r.next())return null;
                if(!r.getString(2).equals(input.path("identity").path("patientIdentification").asText()))
                    throw new SecurityException("BOUND_PATIENT_IDENTITY_MISMATCH");
                return r.getLong(1);
            }
        }
    }
}
