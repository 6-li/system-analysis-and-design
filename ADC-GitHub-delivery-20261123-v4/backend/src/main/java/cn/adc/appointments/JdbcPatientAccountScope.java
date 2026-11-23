package cn.adc.appointments;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Only an explicit verified binding grants SELF reads, never a submitted request. */
final class JdbcPatientAccountScope implements MiniAppointmentReadController.PatientScope {
    @Override public boolean mayRead(Connection c,String account,long patient) throws SQLException {
        return authorizations(c,account).stream().anyMatch(a -> a.patientId()==patient);
    }
    @Override public List<MiniAppointmentReadController.Authorization> authorizations(Connection c,String account) throws SQLException {
        UUID id=accountId(account);
        if(id==null)return List.of();
        try(var q=c.prepareStatement("""
            SELECT b.patient_id FROM adc_mini.patient_account_binding b
            JOIN adc_mini.patient_account a ON a.account_id=b.account_id
            WHERE a.account_id=? AND NOT a.disabled
            """)) {
            q.setObject(1,id);
            try(var r=q.executeQuery()) {
                if(!r.next())return List.of();
                long patient=r.getLong(1);
                return List.of(new MiniAppointmentReadController.Authorization("ACCOUNT-SELF:"+id,patient,
                    MiniAppointmentReadController.Authorization.Kind.SELF,
                    List.of("APPOINTMENT_READ",MiniAppointmentReadController.VISIT_HISTORY_READ,MiniPatientProfileController.PERMISSION),1));
            }
        }
    }
    static UUID accountId(String account) {
        if(account==null||!account.startsWith("ACCOUNT:"))return null;
        try { UUID id=UUID.fromString(account.substring(8));return PatientAccounts.principal(id).equals(account)?id:null; }
        catch(IllegalArgumentException invalid){return null;}
    }
}
