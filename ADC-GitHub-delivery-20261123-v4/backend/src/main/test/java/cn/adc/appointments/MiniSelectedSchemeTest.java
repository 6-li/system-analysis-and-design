package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.duration.DurationPolicyService.Basis;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class MiniSelectedSchemeTest {
    private static tools.jackson.databind.JsonNode input(){return AuditDocuments.parse("""
      {"intendedClinicId":"81001","treatmentNeed":"SYNTHETIC check","preferred":{"date":"2026-11-21"},"patientConfirmed":true,
       "selectedScheme":{"effectiveScheduleRef":{"scheduleId":"87001","versionNo":2},"startsAt":"2026-11-21T09:00:00+08:00","endsAt":"2026-11-21T09:27:00+08:00","treatmentRequest":"SYNTHETIC check",
       "durationBasis":{"schemaVersion":1,"basisMode":"INITIAL_CURRENT","primaryProjectId":"85001","projectVersion":1,"adoptedDurationMinutes":27}}}
      """);}
    @Test void originalSelectionBindsToTheExactCommittedScheme(){var i=input();assertDoesNotThrow(()->MiniSelectedScheme.requireMatches(i,new InitialBookingReview.AcceptedScheme(71,new Version(87001,2),81001,79001,Instant.parse("2026-11-21T01:00:00Z"),Instant.parse("2026-11-21T01:27:00Z"),"SYNTHETIC check",new Basis(85001,1,27))));}
    @Test void changedTimeOrProjectCannotBeSubstituted(){var i=input();assertThrows(Rejection.class,()->MiniSelectedScheme.requireMatches(i,new InitialBookingReview.AcceptedScheme(71,new Version(87001,2),81001,79001,Instant.parse("2026-11-21T02:00:00Z"),Instant.parse("2026-11-21T02:27:00Z"),"SYNTHETIC check",new Basis(85001,1,27))));}
    @Test void absentConfirmationOrMismatchedDemandCannotBecomeAcceptance(){var i=(tools.jackson.databind.node.ObjectNode)input();i.put("patientConfirmed",false);assertThrows(IllegalArgumentException.class,()->MiniSelectedScheme.validate(i));i.put("patientConfirmed",true).put("treatmentNeed","SYNTHETIC other");assertThrows(IllegalArgumentException.class,()->MiniSelectedScheme.validate(i));}
}
