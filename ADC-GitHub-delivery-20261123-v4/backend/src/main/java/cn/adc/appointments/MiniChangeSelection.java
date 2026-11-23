package cn.adc.appointments;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
final class MiniChangeSelection {
    static ObjectNode normalized(JsonNode input,long clinic){
        var copy=(ObjectNode)input.deepCopy();
        copy.put("intendedClinicId",Long.toString(clinic));copy.put("treatmentNeed",input.path("selectedScheme").path("treatmentRequest").asText());
        ((ObjectNode)copy.path("selectedScheme").path("durationBasis")).put("basisMode","INITIAL_CURRENT");return copy;
    }
    static void validate(JsonNode input){
        String mode=input.path("selectedScheme").path("durationBasis").path("basisMode").asText();
        if(!java.util.Set.of("PRESERVED_ORIGINAL","CHANGED_PROJECT_CURRENT").contains(mode))throw new IllegalArgumentException("CHANGE_DURATION_MODE_REQUIRED");
        AuditDocuments.text(input,"expectedAppointmentToken");MiniSelectedScheme.validate(normalized(input,81001));
    }
    static void requireMatches(JsonNode input,AppointmentRepository.Appointment original,InitialBookingReview.AcceptedScheme scheme){
        if(!input.has("selectedScheme"))throw new Rejection("PATIENT_CHANGE_SELECTION_REQUIRED");
        validate(input);
        if(!AppointmentReadService.token(original).equals(input.path("expectedAppointmentToken").asText()))throw new Rejection("STALE_APPOINTMENT");
        MiniSelectedScheme.requireMatches(normalized(input,scheme.clinicId()),scheme);
    }
}
