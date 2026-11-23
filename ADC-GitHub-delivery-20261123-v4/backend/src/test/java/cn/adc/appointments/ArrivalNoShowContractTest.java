package cn.adc.appointments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Static review of the already-approved artifact, not an HTTP or service test. */
class ArrivalNoShowContractTest {
    private static final Path CONTRACT = Path.of("specs/002-dentist-scheduling/contracts/api.openapi.json");
    private final JsonNode api = readContract();

    @ParameterizedTest(name = "T026-C01 {1}")
    @CsvSource({"arrival-verifications,SCH-OP-016,ArrivalInput,ARRIVAL_VERIFIED",
            "no-show-verifications,SCH-OP-017,NoShowInput,NO_SHOW_VERIFIED"})
    void writePathsRequireConfirmationEvidenceAndSafetyHeaders(String path, String operation, String input,
                                                              String confirmedFinding) {
        var op = api.at("/paths/~1staff~1appointments~1{appointmentId}~1" + path + "/post");
        assertEquals(operation, op.path("operationId").asText());
        assertEquals(Set.of("Idempotency-Key", "X-CSRF-Token"), op.path("parameters").valueStream()
                .filter(n -> n.path("in").asText().equals("header") && n.path("required").asBoolean())
                .map(n -> n.path("name").asText()).collect(Collectors.toSet()));
        for (String code : Set.of("401", "403", "404", "409", "422", "503")) assertTrue(op.path("responses").has(code), code);
        assertEquals("#/components/schemas/" + input, op.at("/requestBody/content/application~1json/schema/$ref").asText());
        var schema = schema(input);
        assertEquals(Set.of("expectedAppointmentToken", "finding", "verificationBasis"), values(schema.path("required")));
        assertEquals(1, schema.at("/properties/verificationBasis/minLength").asInt());
        assertEquals(Set.of(confirmedFinding, "PENDING_VERIFICATION"), values(schema.at("/properties/finding/enum")));
        if (input.equals("ArrivalInput")) assertEquals(Set.of("verifiedIdentityReference", "actualDentistId", "actualClinicId"),
                values(schema.at("/oneOf/0/required")));
    }

    @Test void detailKeepsHistoricalBasisAndCurrentSupportSeparate() {
        var op = api.at("/paths/~1staff~1appointments~1{appointmentId}/get");
        assertEquals("SCH-OP-018", op.path("operationId").asText());
        assertTrue(op.path("responses").has("403"));
        assertTrue(op.path("responses").has("404"));
        assertTrue(values(schema("StaffAppointmentDetail").path("required")).containsAll(Set.of("originScheduleRef", "currentSupport")));
    }

    @Test void pendingUnknownAndConfirmedVisitHaveDifferentContractEvidence() {
        var receipts = schema("CommandReceipt").path("oneOf");
        var completed = branch(receipts, "COMPLETED").at("/allOf/0/oneOf");
        assertEquals(Set.of("appointmentId", "visitId", "originalUcSuccessEventId"), successLinks(completed, "UC05"));
        assertEquals(Set.of("appointmentId", "originalUcSuccessEventId"), successLinks(completed, "UC06"));
        var pending = branch(receipts, "PERSISTED_INCOMPLETE").path("oneOf").valueStream()
                .filter(n -> values(n.at("/properties/ucCode/enum")).containsAll(Set.of("UC05", "UC06")))
                .findFirst().orElseThrow();
        assertEquals(Set.of("appointmentId", "originalUcSuccessEventId"), values(pending.at("/properties/links/required")));
        assertEquals(Set.of("visitId"), values(pending.at("/properties/links/not/required")));
        assertEquals(Set.of("links"), values(branch(receipts, "UNKNOWN").at("/not/required")));
    }

    private static JsonNode branch(JsonNode branches, String status) {
        return branches.valueStream().filter(n -> values(n.at("/properties/status/enum")).contains(status)).findFirst().orElseThrow();
    }
    private static Set<String> successLinks(JsonNode branches, String uc) {
        var branch = branches.valueStream().filter(n -> values(n.at("/properties/ucCode/enum")).contains(uc)).findFirst().orElseThrow();
        return values(branch.at("/properties/links/required"));
    }
    private JsonNode schema(String name) { return api.path("components").path("schemas").path(name); }
    private static Set<String> values(JsonNode array) {
        return array.valueStream().map(JsonNode::asText).collect(Collectors.toSet());
    }
    private static JsonNode readContract() {
        var path = Files.isRegularFile(CONTRACT) ? CONTRACT : Path.of("..").resolve(CONTRACT);
        try (var input = Files.newInputStream(path)) {
            return new ObjectMapper().readTree(input);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read approved OpenAPI", e);
        }
    }
}
