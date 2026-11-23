package cn.adc.scheduling;

import cn.adc.shared.audit.CommandRecovery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleActivationTest {
    private final ScheduleActivationReview review = new ScheduleActivationReview();
    private final Instant start = Instant.parse("2026-11-15T01:00:00Z");
    private final ImpactAssessmentService.Schedule draft = schedule(10, 2, 1, start);

    @Test void newOrChangedAppointmentsInvalidateTheReviewedSnapshot() {
        var initial = List.of(new ScheduleActivationReview.AppointmentState(11, "original", false));
        var token = review.token("draft", "eligibility", initial);
        for (var changed : List.of(List.of(new ScheduleActivationReview.AppointmentState(11, "modified", false)),
                List.of(initial.getFirst(), new ScheduleActivationReview.AppointmentState(12, "new", false)))) {
            assertEquals("ACTIVATION_CHECK_STALE", assertThrows(CommandRecovery.Rejection.class, () ->
                    review.requireReady(token, "draft", "eligibility", draft, List.of(), changed, true)).getMessage());
        }
    }

    @Test void skipsPriorVersionOfSameScheduleAndAllowsAdjacentCrossClinicWindow() {
        var old = schedule(10, 1, 1, start);
        var adjacent = schedule(11, 1, 2, start.plusSeconds(60));
        var token = review.token("draft", "eligibility", List.of());
        assertDoesNotThrow(() -> review.requireReady(token, "draft", "eligibility", draft,
                List.of(old, adjacent), List.of(), true));
        var overlapping = schedule(12, 1, 2, start.plusSeconds(60).minusNanos(1000));
        assertEquals("EFFECTIVE_SCHEDULE_CONFLICT", assertThrows(CommandRecovery.Rejection.class, () ->
                review.requireReady(token, "draft", "eligibility", draft, List.of(old, overlapping), List.of(), true)).getMessage());
    }

    @Test void reviewedButUnresolvedAppointmentStillBlocksActivation() {
        var unresolved = List.of(new ScheduleActivationReview.AppointmentState(11, "observed", true));
        var token = review.token("draft", "eligibility", unresolved);
        assertEquals("APPOINTMENT_HANDLING_REQUIRED", assertThrows(CommandRecovery.Rejection.class, () ->
                review.requireReady(token, "draft", "eligibility", draft, List.of(), unresolved, true)).getMessage());
    }

    private static ImpactAssessmentService.Schedule schedule(long id, int version, long clinic, Instant start) {
        return new ImpactAssessmentService.Schedule(id, version, clinic, 9,
                new ImpactAssessmentService.Window(start, start.plusSeconds(60)));
    }
}
