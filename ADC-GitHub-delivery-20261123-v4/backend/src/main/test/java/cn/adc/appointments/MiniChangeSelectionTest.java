package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import cn.adc.shared.audit.AuditDocuments;
import tools.jackson.databind.node.ObjectNode;
class MiniChangeSelectionTest {
    private ObjectNode input(){
        var input=AuditDocuments.JSON.createObjectNode().put("type","CHANGE").put("expectedAppointmentToken","TEST-token").put("patientConfirmed",true);
        input.putObject("preferred").put("date","2026-12-21");
        var scheme=input.putObject("selectedScheme").put("startsAt","2026-12-21T09:00:00+08:00").put("endsAt","2026-12-21T09:27:00+08:00").put("treatmentRequest","TEST check");
        scheme.putObject("effectiveScheduleRef").put("scheduleId","1").put("versionNo",1);
        scheme.putObject("durationBasis").put("schemaVersion",1).put("basisMode","PRESERVED_ORIGINAL").put("primaryProjectId","1").put("projectVersion",1).put("adoptedDurationMinutes",27);return input;
    }
    @Test void validatesConfirmedPatientSelectedChangeWithoutMutating(){var value=input();var before=value.deepCopy();assertDoesNotThrow(()->MiniChangeSelection.validate(value));assertEquals(before,value);}
    @Test void missingConfirmationOrTokenIsRejected(){var value=input();value.put("patientConfirmed",false);assertThrows(IllegalArgumentException.class,()->MiniChangeSelection.validate(value));value.put("patientConfirmed",true);value.remove("expectedAppointmentToken");assertThrows(IllegalArgumentException.class,()->MiniChangeSelection.validate(value));}
    @Test void wrongDurationAndPreferredDateRejected(){var value=input();((ObjectNode)value.path("preferred")).put("date","2026-12-22");assertThrows(IllegalArgumentException.class,()->MiniChangeSelection.validate(value));var invalid=input();((ObjectNode)invalid.path("selectedScheme").path("durationBasis")).put("adoptedDurationMinutes",40);assertThrows(IllegalArgumentException.class,()->MiniChangeSelection.validate(invalid));}
}
