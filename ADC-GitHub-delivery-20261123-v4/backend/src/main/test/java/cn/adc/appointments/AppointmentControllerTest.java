package cn.adc.appointments;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Static UC02 OpenAPI contract assertions. No HTTP handler is registered or invoked. */
class AppointmentControllerTest {
    private static final String CONTRACT = "specs/002-dentist-scheduling/contracts/api.openapi.json";
    private final JsonNode api = readContract();

    @Test void staffCreationRequiresCsrfAndIdempotencyHeaders() {
        var op = api.at("/paths/~1staff~1appointments/post");
        assertEquals("SCH-OP-013", op.path("operationId").asText());
        var headers = op.path("parameters").valueStream().filter(n -> n.path("in").asText().equals("header"))
                .filter(n -> n.path("required").asBoolean()).map(n -> n.path("name").asText()).collect(Collectors.toSet());
        assertEquals(Set.of("Idempotency-Key", "X-CSRF-Token"), headers);
        assertEquals("#/components/schemas/CreateAppointmentInput",
                op.at("/requestBody/content/application~1json/schema/$ref").asText());
        assertEquals("#/components/schemas/Error",
                op.at("/responses/403/content/application~1json/schema/$ref").asText());
    }

    @Test void createRequiresActualAcceptanceReferenceAndDoesNotRequireMiniProgramSource() {
        var input = schema("CreateAppointmentInput");
        assertEquals(Set.of("patientId", "scheme", "acceptanceEvidenceReference"), values(input.path("required")));
        assertFalse(input.path("additionalProperties").asBoolean(true));
        assertEquals(1, input.at("/properties/acceptanceEvidenceReference/minLength").asInt());
        assertEquals("#/components/schemas/BookingScheme", input.at("/properties/scheme/$ref").asText());
    }

    @Test void schemeCarriesEffectiveCompositeReferenceAndControlledDurationBasis() {
        var scheme = schema("BookingScheme");
        assertEquals(Set.of("effectiveScheduleRef", "startsAt", "endsAt", "treatmentRequest", "durationBasis"),
                values(scheme.path("required")));
        assertEquals("#/components/schemas/ScheduleVersionRef", scheme.at("/properties/effectiveScheduleRef/$ref").asText());
        assertEquals(Set.of("scheduleId", "versionNo"), values(schema("ScheduleVersionRef").path("required")));
        var basis = scheme.at("/properties/durationBasis");
        assertEquals(Set.of("schemaVersion", "primaryProjectId", "projectVersion", "adoptedDurationMinutes", "basisMode"),
                values(basis.path("required")));
        var minutes = basis.at("/properties/adoptedDurationMinutes");
        assertEquals("integer", minutes.path("type").asText());
        assertEquals(1, minutes.path("minimum").asInt());
        assertFalse(minutes.has("default"));
        assertTrue(values(basis.at("/properties/basisMode/enum")).contains("INITIAL_CURRENT"));
    }

    @Test void createNarrowsOnlyItsOwnBasisModeToInitialCurrent() {
        assertEquals("INITIAL_CURRENT", schema("CreateAppointmentInput")
                .at("/properties/scheme/properties/durationBasis/properties/basisMode/const").asText());
        assertEquals("#/components/schemas/BookingScheme", schema("ChangeAppointmentInput")
                .at("/properties/scheme/$ref").asText());
        assertFalse(schema("ChangeAppointmentInput").path("properties").path("scheme").has("properties"));
        assertEquals(Set.of("INITIAL_CURRENT", "CHANGED_PROJECT_CURRENT", "PRESERVED_ORIGINAL"),
                values(schema("BookingScheme").at("/properties/durationBasis/properties/basisMode/enum")));
    }

    @Test void unknownDoesNotHaveSuccessLinksAndCompletedUc02RequiresBusinessAndAuditReferences() {
        var op = api.at("/paths/~1staff~1appointments/post");
        assertEquals(Set.of("UNKNOWN"), values(op.at("/responses/202/content/application~1json/schema/allOf/1/properties/status/enum")));
        var cases = schema("CommandReceipt").path("oneOf");
        var completed = cases.valueStream().filter(n -> values(n.at("/properties/status/enum")).contains("COMPLETED"))
                .findFirst().orElseThrow();
        var uc02 = completed.at("/allOf/0/oneOf").valueStream()
                .filter(n -> values(n.at("/properties/ucCode/enum")).contains("UC02")).findFirst().orElseThrow();
        assertEquals(Set.of("appointmentId", "originalUcSuccessEventId"), values(uc02.at("/properties/links/required")));
        var unknown = cases.valueStream().filter(n -> values(n.at("/properties/status/enum")).contains("UNKNOWN"))
                .findFirst().orElseThrow();
        assertEquals(Set.of("links"), values(unknown.at("/not/required")));
    }

    @Test void readContractsSeparateOriginalArrangementFromCurrentSupport() {
        assertEquals("SCH-OP-012", api.at("/paths/~1staff~1appointments~1{appointmentId}~1schedule-support/get/operationId").asText());
        assertEquals("SCH-OP-018", api.at("/paths/~1staff~1appointments~1{appointmentId}/get/operationId").asText());
        assertTrue(values(schema("StaffAppointmentDetail").path("required")).containsAll(Set.of("originScheduleRef", "currentSupport")));
        var support = schema("CurrentSupport");
        assertTrue(values(support.path("required")).contains("originScheduleRef"));
        assertEquals(Set.of("effectiveScheduleRef"), values(support.at("/oneOf/0/required")));
    }

    @Test void appointmentDetailDocumentsDependencyFailureSeparatelyFromDenialAndAbsence() {
        var detail = api.at("/paths/~1staff~1appointments~1{appointmentId}/get/responses");
        var support = api.at("/paths/~1staff~1appointments~1{appointmentId}~1schedule-support/get/responses");
        assertEquals(support.path("503"), detail.path("503"));
        for (var status : Set.of("403", "404", "503")) {
            assertEquals("#/components/schemas/Error",
                    detail.at("/" + status + "/content/application~1json/schema/$ref").asText());
        }
    }

    @Test void t02505StaleScheduleFeedbackUsesExistingConflictContract() {
        var op = api.at("/paths/~1staff~1appointments/post");
        assertEquals("#/components/schemas/Error", op.at("/responses/409/content/application~1json/schema/$ref").asText());
        assertEquals(Set.of("code", "message"), values(schema("Error").path("required")));
        var feedback = BookingReviewFeedback.forRejectedReview("STALE_SCHEDULE").orElseThrow();
        assertEquals(409, feedback.status());
        assertEquals("STALE_SCHEDULE", feedback.body().code());
        assertTrue(feedback.body().message().contains("重新匹配"));
        assertTrue(feedback.body().message().contains("申请人确认"));
        assertTrue(BookingReviewFeedback.forRejectedReview("COMMIT_OUTCOME_UNKNOWN").isEmpty());
    }

    private JsonNode schema(String name) { return api.path("components").path("schemas").path(name); }

    private static Set<String> values(JsonNode array) {
        assertTrue(array.isArray(), array::toString);
        return array.valueStream().map(JsonNode::asText).collect(Collectors.toSet());
    }

    private static JsonNode readContract() {
        var path = Path.of(CONTRACT);
        if (!Files.isRegularFile(path)) path = Path.of("..").resolve(path);
        try (var input = Files.newInputStream(path)) {
            return new ObjectMapper().readTree(input);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + CONTRACT, e);
        }
    }
}
