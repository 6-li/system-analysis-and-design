package cn.adc.scheduling;

import cn.adc.appointments.AppointmentResolutionEvidence;
import cn.adc.shared.audit.CommandRecovery;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Final database observations, using the same connection as the locked version switch. */
final class ScheduleActivationScan implements ScheduleActivationService.FinalScan {
    record Eligibility(String fingerprint, boolean confirmed) {}

    @FunctionalInterface
    interface EligibilitySource {
        Eligibility verify(ScheduleStateToken.Version draft) throws SQLException;
    }

    private final EligibilitySource eligibility;
    private final ImpactAssessmentService impacts = new ImpactAssessmentService();
    private final ResolutionRepository resolutions = new ResolutionRepository();
    private final ResolutionHistoryService history = new ResolutionHistoryService();
    private final ResolutionChain chains = new ResolutionChain();
    private final AppointmentResolutionEvidence evidence = new AppointmentResolutionEvidence();

    ScheduleActivationScan(EligibilitySource eligibility) {
        this.eligibility = java.util.Objects.requireNonNull(eligibility);
    }

    @Override
    public ScheduleActivationService.Observation read(Connection c, ScheduleStateToken.Version draft) throws SQLException {
        var decision = eligibility.verify(draft);
        var effective = new JdbcScheduleStore().overlaps(c, draft.dentistId(),
                new ScheduleStore.Window(draft.startsAt(), draft.endsAt())).stream()
                .filter(row -> "EFFECTIVE".equals(row.status())).map(ImpactAssessmentService::schedule).toList();
        var appointments = new ArrayList<ScheduleActivationReview.AppointmentState>();
        // Include every historical clinic of this stable arrangement, even when its new clinic differs.
        var clinics = new JdbcScheduleStore().versions(c, draft.scheduleId()).stream()
                .map(ScheduleStateToken.Version::clinicId).distinct().sorted().toList();
        for (long clinic : clinics) {
            for (var impact : impacts.forAuthorizedClinic(c, clinic, ImpactAssessmentService.schedule(draft), effective)) {
                var state = evidence.activationState(c, clinic, impact.appointmentId())
                        .orElseThrow(() -> new CommandRecovery.Rejection("ACTIVATION_CHECK_STALE"));
                boolean supported = state.ordinaryPendingArrival()
                        && impact.assessment() == ImpactAssessmentService.Assessment.SUPPORTED;
                boolean resolved = !supported && verifiedHandling(c, draft, clinic, impact.appointmentId());
                appointments.add(new ScheduleActivationReview.AppointmentState(impact.appointmentId(), state.token(),
                        !supported && !resolved));
            }
        }
        appointments.sort(java.util.Comparator.comparingLong(ScheduleActivationReview.AppointmentState::id));
        return new ScheduleActivationService.Observation(decision.fingerprint(), decision.confirmed(),
                List.copyOf(appointments), effective);
    }

    String impactToken(Connection c, ScheduleStateToken.Version draft, List<ImpactAssessmentService.Schedule> effective)
            throws SQLException {
        var target = ImpactAssessmentService.schedule(draft);
        var items = new ArrayList<ImpactAssessmentService.ObservedImpact>();
        for (var impact : impacts.forAuthorizedClinic(c, draft.clinicId(), target, effective)) {
            var token = evidence.currentToken(c, draft.clinicId(), impact.appointmentId())
                    .orElseThrow(() -> new CommandRecovery.Rejection("ACTIVATION_CHECK_STALE"));
            items.add(new ImpactAssessmentService.ObservedImpact(impact, token));
        }
        boolean hidden = effective.stream().anyMatch(row -> row.id() != draft.scheduleId()
                && row.clinic() != draft.clinicId());
        return ImpactReportView.of(new ImpactAssessmentService.Preview(target, items, hidden),
                java.time.Instant.EPOCH).path("checkToken").asText();
    }

    private boolean verifiedHandling(Connection c, ScheduleStateToken.Version draft, long clinic, long appointment)
            throws SQLException {
        var rows = resolutions.forAppointment(c, draft.scheduleId(), draft.versionNo(), appointment);
        var docs = resolutions.terminalDocuments(c, draft.scheduleId(), draft.versionNo(), appointment);
        var heads = chains.heads(chains.verifiedRevisions(rows, docs));
        for (var head : heads.values()) {
            var document = docs.stream().filter(doc -> Long.toString(head.id())
                    .equals(doc.path("newResolutionId").asText())).findFirst().orElseThrow();
            var verification = document.path("verification");
            // Unsupported active bookings cannot be released by an unrelated historical change.
            if (!"VERIFIED".equals(verification.path("conclusion").asText())
                    || !"UC04".equals(verification.path("formalEvidence").path("ucCode").asText())) continue;
            try {
                history.verifyFormalEvidence(c, clinic, appointment, document);
                return true;
            } catch (CommandRecovery.Rejection stale) {
                // The current appointment no longer corroborates this historical fact.
            }
        }
        return false;
    }
}
