package cn.adc.appointments;

import cn.adc.shared.security.StaffAuthorization;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

final class TreatingDentistAccess {
    private TreatingDentistAccess() {}

    static long require(Connection c, StaffAuthorization authorization, VisitHealthService.DentistIdentity dentists,
                        String action, long clinic, long patient, long visit) throws SQLException {
        String actor = authorization.require(action, Set.of(clinic)).actor();
        long dentist = dentists.requireDentist(actor, clinic);
        if (dentist <= 0) throw new SecurityException("VISIT_ACCESS_DENIED");
        try (var query = c.prepareStatement("""
                SELECT 1 FROM adc.visit v JOIN adc.appointment a ON a.appointment_id=v.appointment_id
                WHERE v.visit_id=? AND v.clinic_id=? AND v.actual_dentist_id=?
                  AND a.patient_id=? AND a.appointment_status='已到诊'
                """)) {
            query.setLong(1, visit);
            query.setLong(2, clinic);
            query.setLong(3, dentist);
            query.setLong(4, patient);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new SecurityException("VISIT_ACCESS_DENIED");
            }
        }
        return dentist;
    }
}
