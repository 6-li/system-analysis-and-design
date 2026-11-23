package cn.adc.appointments;

import cn.adc.shared.audit.AuditStore;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.UUID;
import java.util.function.ToLongFunction;

/** Binds UC01 commands to a server-verified staff session and its active clinic. */
final class PatientMaintenanceAccess implements PatientMaintenanceController.Access {
    private final JdbcTransactions tx;
    private final StaffBindingAdapter bindings;
    private final ToLongFunction<HttpServletRequest> clinic;
    private final PatientDirectoryQuery.VerifiedIdentity identity;
    private final PatientMaintenanceService.VerifiedContext pure;
    private final PatientMaintenanceService.MiniSource mini;
    private final AuditStore.EventIdAllocator patientIds;
    private final AuditStore.EventIdAllocator auditIds;
    private final Clock clock;

    PatientMaintenanceAccess(JdbcTransactions tx, StaffBindingAdapter bindings, ToLongFunction<HttpServletRequest> clinic,
                             PatientDirectoryQuery.VerifiedIdentity identity, PatientMaintenanceService.VerifiedContext pure,
                             PatientMaintenanceService.MiniSource mini, AuditStore.EventIdAllocator patientIds,
                             AuditStore.EventIdAllocator auditIds, Clock clock) {
        this.tx = tx;
        this.bindings = bindings;
        this.clinic = clinic;
        this.identity = identity;
        this.pure = pure;
        this.mini = mini;
        this.patientIds = patientIds;
        this.auditIds = auditIds;
        this.clock = clock;
    }

    @Override
    public JsonNode query(HttpServletRequest request, String csrf, JsonNode input) {
        return new PatientDirectoryQuery(tx, authorization(request, csrf), identity).query(input);
    }

    @Override
    public CommandRecovery.Receipt save(HttpServletRequest request, String csrf, UUID key, Long patient, JsonNode input) {
        var auth = authorization(request, csrf);
        return new PatientMaintenanceService(tx, auth, pure, patientIds, auditIds, clock, mini)
                .save(clinic.applyAsLong(request), patient, key, input);
    }

    private StaffAuthorization authorization(HttpServletRequest request, String csrf) {
        var session = new StaffSessionIdentityAdapter(() -> {
            var current = request.getSession(false);
            Object verified = current == null ? null : current.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
            return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
        });
        session.requireCsrf(csrf);
        return new StaffAuthorization(session, bindings::bindings);
    }
}
