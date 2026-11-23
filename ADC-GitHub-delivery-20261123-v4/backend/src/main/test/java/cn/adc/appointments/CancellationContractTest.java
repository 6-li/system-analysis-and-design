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

/** Offline schema assertions. The UC04 controller is outside the authorized scope. */
class CancellationContractTest {
    private static final Path CONTRACT = Path.of("specs/002-dentist-scheduling/contracts/api.openapi.json");
    private final JsonNode api = readContract();
    private final JsonNode op = api.at("/paths/~1staff~1appointments~1{appointmentId}~1cancellations/post");
    private final JsonNode receipt = api.at("/components/schemas/CommandReceipt");

    @Test void cancellationRequiresOriginalAppointmentTokenVerificationAndHeaders() {
        assertEquals("SCH-OP-015", op.path("operationId").asText());
        assertEquals(Set.of("appointmentId", "Idempotency-Key", "X-CSRF-Token"), op.path("parameters").valueStream()
                .filter(n -> n.path("required").asBoolean()).map(n -> n.path("name").asText()).collect(Collectors.toSet()));
        assertEquals("#/components/schemas/CancelAppointmentInput",
                op.at("/requestBody/content/application~1json/schema/$ref").asText());
        var input = api.at("/components/schemas/CancelAppointmentInput");
        assertEquals(Set.of("expectedAppointmentToken", "cancellationVerification"), values(input.path("required")));
        assertEquals(1, input.at("/properties/cancellationVerification/minLength").asInt());
        assertFalse(input.path("additionalProperties").asBoolean(true));
        for (String code : Set.of("401", "403", "404", "409", "422", "503")) assertTrue(op.path("responses").has(code), code);
    }

    @Test void unknownCarriesOriginalRequestReferenceAndNoSuccessLinks() {
        assertEquals(Set.of("UNKNOWN"), values(op.at("/responses/202/content/application~1json/schema/allOf/1/properties/status/enum")));
        assertTrue(values(receipt.path("required")).contains("requestReference"));
        assertEquals(Set.of("links"), values(branch("UNKNOWN").at("/not/required")));
    }

    @Test void cancellationSuccessRequiresOriginalAppointmentAndAuditWithoutRequiringChangeHistory() {
        var links = branch("COMPLETED").at("/allOf/0/oneOf").valueStream()
                .filter(n -> values(n.at("/properties/ucCode/enum")).contains("UC04"))
                .findFirst().orElseThrow().at("/properties/links");
        assertEquals(Set.of("appointmentId", "originalUcSuccessEventId"), values(links.path("required")));
    }

    private JsonNode branch(String status) {
        return receipt.path("oneOf").valueStream()
                .filter(n -> values(n.at("/properties/status/enum")).contains(status)).findFirst().orElseThrow();
    }
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
