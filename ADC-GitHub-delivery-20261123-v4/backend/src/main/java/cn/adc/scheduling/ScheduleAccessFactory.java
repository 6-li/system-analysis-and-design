package cn.adc.scheduling;

import cn.adc.shared.config.*;
import cn.adc.shared.audit.AuditStore;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** No default bean. Neither the technical profile nor a test DataSource alone opens UC21. */
public final class ScheduleAccessFactory implements ScheduleAccess, ResolutionController.HistoryAccess {
    private static final String REQUEST_IDENTITY=ScheduleAccessFactory.class.getName()+".identity";
    public record Conflict(long scheduleId,int versionNo){}
    @FunctionalInterface public interface VerifiedCoordination {
        Optional<String> confirm(long clinic,long dentist,Instant start,Instant end,String submitted,List<Conflict> conflicts);
    }
    private final JdbcTransactions tx;
    private final StaffBindingAdapter bindings;
    private final Supplier<SourceStamp> activation;
    private final ClinicDirectoryProvider clinics;
    private final DentistEligibilityProvider dentists;
    private final VerifiedCoordination coordination;
    private final Clock clock;
    private final AuditStore.EventIdAllocator auditIds;
    private final AuditStore.EventIdAllocator resolutionIds;
    private final boolean courseUc22Narrow;

    public ScheduleAccessFactory(JdbcTransactions tx,StaffBindingAdapter bindings,Supplier<SourceStamp> activation,
                                 ClinicDirectoryProvider clinics,DentistEligibilityProvider dentists,
                                 VerifiedCoordination coordination,Clock clock,AuditStore.EventIdAllocator auditIds){
        this(tx, bindings, activation, clinics, dentists, coordination, clock, auditIds, null);
    }

    public ScheduleAccessFactory(JdbcTransactions tx,StaffBindingAdapter bindings,Supplier<SourceStamp> activation,
                                 ClinicDirectoryProvider clinics,DentistEligibilityProvider dentists,
                                 VerifiedCoordination coordination,Clock clock,AuditStore.EventIdAllocator auditIds,
                                 AuditStore.EventIdAllocator resolutionIds){
        this(tx,bindings,activation,clinics,dentists,coordination,clock,auditIds,resolutionIds,false);
    }
    public ScheduleAccessFactory(JdbcTransactions tx,StaffBindingAdapter bindings,Supplier<SourceStamp> activation,
                                 ClinicDirectoryProvider clinics,DentistEligibilityProvider dentists,
                                 VerifiedCoordination coordination,Clock clock,AuditStore.EventIdAllocator auditIds,
                                 AuditStore.EventIdAllocator resolutionIds,boolean courseUc22Narrow){
        this.tx=Objects.requireNonNull(tx);this.bindings=Objects.requireNonNull(bindings);
        this.activation=Objects.requireNonNull(activation);this.clinics=Objects.requireNonNull(clinics);
        this.dentists=Objects.requireNonNull(dentists);this.coordination=Objects.requireNonNull(coordination);
        this.clock=Objects.requireNonNull(clock);
        this.auditIds=Objects.requireNonNull(auditIds);
        this.resolutionIds=resolutionIds;
        this.courseUc22Narrow=courseUc22Narrow;
    }

    @Override
    public cn.adc.shared.audit.CommandRecovery.Receipt save(HttpServletRequest request, long schedule, int version,
                                                            String key, tools.jackson.databind.JsonNode input) {
        var authorization = resolutionAuthorization(request);
        if (resolutionIds == null) throw new JdbcTransactions.Failure(false, "RESOLUTION_ID_UNAVAILABLE");
        return new ResolutionSubmission(tx, authorization, clock, auditIds, resolutionIds,courseUc22Narrow)
                .save(schedule, version, key, input);
    }
    public StaffSessionIdentityAdapter identity(HttpServletRequest request){
        if(request.getAttribute(REQUEST_IDENTITY) instanceof StaffSessionIdentityAdapter adapter)return adapter;
        var adapter=new StaffSessionIdentityAdapter(()->{
            var s=request.getSession(false);
            var value=s==null?null:s.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
            return value instanceof StaffSessionIdentityAdapter.VerifiedSession verified?verified:null;
        });
        request.setAttribute(REQUEST_IDENTITY,adapter);
        return adapter;
    }
    public ScheduleOperations service(HttpServletRequest request){
        try{activation.get().requireUsable();}catch(RuntimeException e){throw new ConfigurationUnavailable();}
        var identity=identity(request);
        if(identity.current().isEmpty())throw new SecurityException("AUTHENTICATION_REQUIRED");
        return new ScheduleDraftService(tx,new StaffAuthorization(identity,bindings::bindings),clinics,dentists,
                (clinic,dentist,w,submitted,refs)->coordination.confirm(clinic,dentist,w.start(),w.end(),submitted,
                        refs.stream().map(r->new Conflict(r.id(),r.version())).toList()),clock,auditIds);
    }

    @Override
    public tools.jackson.databind.JsonNode read(HttpServletRequest request, long schedule, int version) {
        return ResolutionController.historyBody(
                new ResolutionHistoryReadService(tx, resolutionAuthorization(request)).forVersion(schedule, version));
    }

    @Override
    public void requireCsrf(HttpServletRequest request, String token) {
        var identity = identity(request);
        if (identity.current().isEmpty()) throw new SecurityException("AUTHENTICATION_REQUIRED");
        identity.requireCsrf(token);
    }

    @Override
    public cn.adc.shared.audit.CommandRecovery.Receipt activationResult(HttpServletRequest request, String reference) {
        return new ScheduleActivationService().lookup(tx, resolutionAuthorization(request), reference);
    }

    @Override
    public cn.adc.shared.audit.CommandRecovery.Receipt activate(HttpServletRequest request, long schedule, int version,
                                                                String key, tools.jackson.databind.JsonNode input) {
        var authorization = resolutionAuthorization(request);
        var scan = new ScheduleActivationScan(draft -> {
            var eligibility = dentists.require(draft.dentistId(), draft.clinicId());
            if (eligibility == null || eligibility.provenance() == null) throw new ConfigurationUnavailable();
            eligibility.provenance().requireUsable();
            if (eligibility.clinicId() != draft.clinicId() || eligibility.dentistId() != draft.dentistId()
                    || eligibility.evidenceReference() == null || eligibility.evidenceReference().isBlank()) {
                throw new ConfigurationUnavailable();
            }
            var snapshot = cn.adc.shared.audit.AuditDocuments.JSON.createObjectNode()
                    .put("qualification", eligibility.evidenceReference())
                    .put("source", eligibility.provenance().reference()).put("version", eligibility.provenance().version());
            return new ScheduleActivationScan.Eligibility(cn.adc.shared.audit.AuditDocuments.hash(snapshot),
                    eligibility.evidenceReference().equals(draft.qualification()));
        });
        return new ActivationSubmission(tx, authorization, clock, auditIds,courseUc22Narrow).activate(schedule, version, key, input, scan);
    }

    @Override
    public tools.jackson.databind.JsonNode impact(HttpServletRequest request, long schedule, int version) {
        var authorization = resolutionAuthorization(request);
        var preview = new ImpactAssessmentService().preview(tx, authorization, null, schedule, version);
        return ImpactReportView.of(preview, cn.adc.shared.time.StrictInputPolicy.eventTime(clock));
    }

    private StaffAuthorization resolutionAuthorization(HttpServletRequest request) {
        try { activation.get().requireUsable(); }
        catch (RuntimeException unavailable) { throw new JdbcTransactions.Failure(false, "CONFIG_UNAVAILABLE"); }
        var identity = identity(request);
        if (identity.current().isEmpty()) throw new SecurityException("AUTHENTICATION_REQUIRED");
        return new StaffAuthorization(identity, bindings::bindings);
    }
}
