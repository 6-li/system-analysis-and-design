package cn.adc.scheduling;
import cn.adc.shared.audit.CommandRecovery;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CourseUc22ActivationSafetyTest {
    @Test void unhandledImpactedAppointmentStillBlocksActivation(){
        var review=new ScheduleActivationReview();
        var appointments=List.of(new ScheduleActivationReview.AppointmentState(100L,"TEST-current",true));
        var draft=new ImpactAssessmentService.Schedule(10L,1,81001L,79001L,new ImpactAssessmentService.Window(Instant.parse("2026-12-21T01:00:00Z"),Instant.parse("2026-12-21T04:00:00Z")));
        String token=review.token("TEST-draft","TEST-qualified",appointments);
        assertEquals("APPOINTMENT_HANDLING_REQUIRED",assertThrows(CommandRecovery.Rejection.class,()->review.requireReady(token,"TEST-draft","TEST-qualified",draft,List.of(),appointments,true)).getMessage());
    }
}
