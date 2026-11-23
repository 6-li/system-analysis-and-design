package cn.adc.scheduling;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.CommandRecovery;

import java.util.List;

/** Compares server-side observations before the final version switch. */
final class ScheduleActivationReview {
    record AppointmentState(long id, String fingerprint, boolean blocksActivation) {}

    String token(String draftState, String eligibilityState, List<AppointmentState> appointments) {
        var snapshot = AuditDocuments.JSON.createObjectNode().put("draftState", draftState)
                .put("eligibilityState", eligibilityState);
        var items = snapshot.putArray("appointments");
        appointments.stream().sorted(java.util.Comparator.comparingLong(AppointmentState::id)).forEach(a ->
                items.addObject().put("id", Long.toString(a.id())).put("fingerprint", a.fingerprint())
                        .put("blocksActivation", a.blocksActivation()));
        return AuditDocuments.hash(snapshot);
    }

    void requireReady(String expected, String draftState, String eligibilityState,
                      ImpactAssessmentService.Schedule draft, List<ImpactAssessmentService.Schedule> effective,
                      List<AppointmentState> current, boolean qualifiedAndCoordinated) {
        if (!token(draftState, eligibilityState, current).equals(expected)) {
            throw new CommandRecovery.Rejection("ACTIVATION_CHECK_STALE");
        }
        if (!qualifiedAndCoordinated) throw new CommandRecovery.Rejection("ACTIVATION_ELIGIBILITY_UNCONFIRMED");
        if (effective.stream().anyMatch(other -> other.id() != draft.id() && other.dentist() == draft.dentist()
                && other.window().overlaps(draft.window()))) {
            throw new CommandRecovery.Rejection("EFFECTIVE_SCHEDULE_CONFLICT");
        }
        if (current.stream().anyMatch(AppointmentState::blocksActivation)) {
            throw new CommandRecovery.Rejection("APPOINTMENT_HANDLING_REQUIRED");
        }
    }
}
