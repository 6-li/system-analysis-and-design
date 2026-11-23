package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** Immutable patient selection in the original intake JSON. This is not a capacity reservation. */
final class MiniSelectedScheme {
    static void validate(JsonNode input) {
        if (!input.has("selectedScheme") && !input.has("patientConfirmed")) return; // old requests remain readable
        if (!input.has("selectedScheme") || !input.path("patientConfirmed").isBoolean()
                || !input.path("patientConfirmed").booleanValue()) throw new IllegalArgumentException("PATIENT_SELECTION_CONFIRMATION_REQUIRED");
        var s=input.path("selectedScheme");
        AuditDocuments.fields(s,"effectiveScheduleRef","startsAt","endsAt","treatmentRequest","durationBasis");
        var ref=s.path("effectiveScheduleRef");AuditDocuments.fields(ref,"scheduleId","versionNo");
        InitialBookingReview.id(ref,"scheduleId");StrictInputPolicy.positiveMinutes(ref.get("versionNo"));
        var b=s.path("durationBasis");AuditDocuments.fields(b,"schemaVersion","basisMode","primaryProjectId","projectVersion","adoptedDurationMinutes");
        if(StrictInputPolicy.positiveMinutes(b.get("schemaVersion"))!=1||!"INITIAL_CURRENT".equals(b.path("basisMode").asText()))throw new IllegalArgumentException("INITIAL_CURRENT_REQUIRED");
        InitialBookingReview.id(b,"primaryProjectId");StrictInputPolicy.positiveMinutes(b.get("projectVersion"));
        int minutes=StrictInputPolicy.positiveMinutes(b.get("adoptedDurationMinutes"));
        var start=OffsetDateTime.parse(AuditDocuments.text(s,"startsAt"));var end=OffsetDateTime.parse(AuditDocuments.text(s,"endsAt"));
        if(!start.toInstant().plusSeconds(Math.multiplyExact((long)minutes,60)).equals(end.toInstant())
                ||!AuditDocuments.text(input,"treatmentNeed").equals(AuditDocuments.text(s,"treatmentRequest"))
                ||!input.path("preferred").path("date").asText().equals(start.toLocalDate().toString()))
            throw new IllegalArgumentException("PATIENT_SELECTION_BINDING_MISMATCH");
    }
    static void requireMatches(JsonNode input,InitialBookingReview.AcceptedScheme actual) {
        validate(input);
        if(!input.has("selectedScheme"))return;
        var s=input.path("selectedScheme");var ref=s.path("effectiveScheduleRef");var b=s.path("durationBasis");
        if(InitialBookingReview.id(input,"intendedClinicId")!=actual.clinicId()
                ||InitialBookingReview.id(ref,"scheduleId")!=actual.schedule().scheduleId()
                ||ref.path("versionNo").asInt()!=actual.schedule().versionNo()
                ||!StrictInputPolicy.instant(s.path("startsAt").asText()).equals(actual.startsAt())
                ||!StrictInputPolicy.instant(s.path("endsAt").asText()).equals(actual.endsAt())
                ||!s.path("treatmentRequest").asText().equals(actual.treatmentRequest())
                ||InitialBookingReview.id(b,"primaryProjectId")!=actual.duration().projectId()
                ||b.path("projectVersion").asInt()!=actual.duration().versionNo()
                ||b.path("adoptedDurationMinutes").asInt()!=actual.duration().minutes())
            throw new Rejection("PATIENT_SELECTED_SCHEME_CHANGED");
    }
}
