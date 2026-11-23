package cn.adc.appointments;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/** Explicit server-provided course grants, independent of intake and staff association. */
final class CourseMiniPatientScope implements MiniAppointmentReadController.PatientScope {
    private final Map<String, List<MiniAppointmentReadController.Authorization>> grants;

    CourseMiniPatientScope(Map<String, List<MiniAppointmentReadController.Authorization>> grants) {
        var checked = new java.util.HashMap<String, List<MiniAppointmentReadController.Authorization>>();
        grants.forEach((account, entries) -> {
            if (account == null || account.isBlank()) throw new IllegalArgumentException("COURSE_ACCOUNT_REQUIRED");
            for (var entry : entries) {
                if (entry.patientId() <= 0 || entry.version() <= 0 || entry.id() == null || entry.id().isBlank()
                        || entry.kind() == null || entry.permissions() == null
                        || entry.permissions().stream().anyMatch(permission -> !java.util.Set.of(
                                "APPOINTMENT_READ", MiniAppointmentReadController.VISIT_HISTORY_READ,
                                MiniPatientProfileController.PERMISSION).contains(permission))) {
                    throw new IllegalArgumentException("COURSE_PATIENT_GRANT_INVALID");
                }
            }
            checked.put(account, entries.stream().map(entry -> new MiniAppointmentReadController.Authorization(
                    entry.id(), entry.patientId(), entry.kind(), List.copyOf(entry.permissions()), entry.version())).toList());
        });
        this.grants = Map.copyOf(checked);
    }

    @Override public boolean mayRead(Connection c, String account, long patient) {
        return authorizations(c, account).stream().anyMatch(grant -> grant.patientId() == patient
                && grant.permissions().contains("APPOINTMENT_READ"));
    }

    @Override public List<MiniAppointmentReadController.Authorization> authorizations(Connection c, String account) {
        return grants.getOrDefault(account, List.of());
    }
}
