package cn.adc.appointments;

import cn.adc.shared.audit.AuditStore.EventIdAllocator;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Clock;
import java.util.function.ToLongFunction;

/** Binds UC07 to the verified session, active clinic and trusted dentist identity. */
record VisitHealthAccess(JdbcTransactions transactions, StaffBindingAdapter bindings,
                         ToLongFunction<HttpServletRequest> activeClinic,
                         VisitHealthService.DentistIdentity dentists, FormalBookingService.AppointmentIds changeIds,
                         EventIdAllocator auditIds, Clock clock) implements VisitHealthController.Access {
    @Override
    public VisitHealthController.Scope open(HttpServletRequest request, String csrf) {
        var identity = new StaffSessionIdentityAdapter(() -> {
            var session = request.getSession(false);
            var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
            return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
        });
        identity.requireCsrf(csrf);
        var authorization = new StaffAuthorization(identity, bindings::bindings);
        return new VisitHealthController.Scope(activeClinic.applyAsLong(request),
                new VisitHealthService(transactions, authorization, dentists, changeIds, auditIds, clock));
    }
}
