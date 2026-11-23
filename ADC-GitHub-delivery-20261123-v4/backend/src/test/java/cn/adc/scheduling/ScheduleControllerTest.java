package cn.adc.scheduling;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Reads the existing OpenAPI contract while the production controller remains unauthorized. */
class ScheduleControllerTest {
    private static final String CONTRACT = "specs/002-dentist-scheduling/contracts/api.openapi.json";
    private final JsonNode api = readContract();

    @Test
    void theSevenUc21OperationsKeepPathsAndWriteHeaders() {
        operation("get", "/staff/schedules", "001");
        operation("get", "/staff/schedules/{scheduleId}/versions/{versionNo}", "002");
        operation("get", "/staff/schedule-maintenance-issues/{issueId}", "011");
        write("/staff/schedule-checks", "003", "CheckInput");
        write("/staff/schedule-drafts", "004", "DraftInput");
        write("/staff/schedules/{scheduleId}/draft-revisions", "005", "DraftRevisionInput");
        write("/staff/schedule-maintenance-issues", "019", "MaintenanceIssueInput");
    }

    @Test
    void draftAndRevisionRequireValidInputShapeWithoutAcceptingIdentityOrActivationFields() {
        var draft = schema("DraftInput");
        assertEquals(Set.of("clinicId", "dentistId", "startsAt", "endsAt"), values(draft.path("required")));
        assertFalse(draft.path("additionalProperties").asBoolean(true));
        for (String forbidden : Set.of("actorReference", "authorizationScope", "activationStatus", "activatedAt")) {
            assertFalse(draft.path("properties").has(forbidden), forbidden);
        }
        assertTrue(values(schema("DraftRevisionInput").path("required")).containsAll(Set.of("basisRef", "expectedStateToken")));
        assertEquals(Set.of("scheduleId", "versionNo"), values(schema("ScheduleVersionRef").path("required")));
    }

    @Test
    void firstFailureRequiresReasonAndDoesNotRequireAnArrangementIdentity() {
        assertEquals(Set.of("submittedContext", "failureReason"), values(schema("MaintenanceIssueInput").path("required")));
        assertEquals(Set.of("clinicId"), values(schema("CheckInput").path("required")));
        var result = operation("post", "/staff/schedule-maintenance-issues", "019")
                .at("/responses/200/content/application~1json/schema/allOf/1/properties/status/enum");
        assertEquals(Set.of("PERSISTED_INCOMPLETE"), values(result));
        assertEquals(Set.of("issueId", "clinicId", "failureReason", "recordedAt"),
                values(schema("MaintenanceIssueView").path("required")));
    }

    @Test
    void unknownDraftReceiptAndSafeFailureResponsesHaveDistinctContracts() {
        for (var path : Set.of("/staff/schedule-drafts", "/staff/schedules/{scheduleId}/draft-revisions")) {
            var responses = api.path("paths").path(path).path("post").path("responses");
            assertEquals(Set.of("UNKNOWN"), values(responses.at("/202/content/application~1json/schema/allOf/1/properties/status/enum")));
            for (String code : Set.of("401", "403", "409", "422", "503")) {
                assertEquals("#/components/schemas/Error", responses.path(code).at("/content/application~1json/schema/$ref").asText());
            }
        }
        assertEquals(Set.of("code", "message"), values(schema("Error").path("required")));
    }

    @Test
    void onlyConfirmedUc21DraftReceiptsRequireTheImpactAndRevisionSelectionIsOptional() {
        var revision=schema("DraftRevisionInput");
        assertEquals("string",revision.at("/properties/previousIssueId/type").asText());
        assertFalse(values(revision.path("required")).contains("previousIssueId"));
        var receipt=schema("CommandReceipt");
        var guard=receipt.at("/allOf/0");
        assertEquals("UC21",guard.at("/if/properties/ucCode/const").asText());
        assertEquals("COMPLETED",guard.at("/if/properties/status/const").asText());
        assertEquals(Set.of("DRAFT_SAVE","DRAFT_REVISE"),values(guard.at("/if/properties/operationKind/enum")));
        assertEquals(Set.of("appointmentImpact"),values(guard.at("/then/required")));
        assertEquals(Set.of("appointmentImpact"),values(guard.at("/else/not/required")));
        assertTrue(receipt.at("/properties/appointmentImpact/properties/appointmentIds/uniqueItems").asBoolean());
    }

    private JsonNode operation(String method, String path, String id) {
        var op = api.path("paths").path(path).path(method);
        assertEquals("SCH-OP-" + id, op.path("operationId").asText());
        return op;
    }

    private void write(String path, String id, String schema) {
        var op = operation("post", path, id);
        var headers = op.path("parameters").valueStream().filter(p -> p.path("in").asText().equals("header"))
                .filter(p -> p.path("required").asBoolean()).map(p -> p.path("name").asText()).collect(Collectors.toSet());
        assertEquals(Set.of("Idempotency-Key", "X-CSRF-Token"), headers);
        assertEquals("#/components/schemas/" + schema, op.at("/requestBody/content/application~1json/schema/$ref").asText());
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
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read " + CONTRACT, e);
        }
    }
}
