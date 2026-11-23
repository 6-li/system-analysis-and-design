package cn.adc.appointments;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Optional;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;

class MiniNewRequestIntakeTest {
    @Test void preliminaryRequestKeepsReceiptAndSameKeyIsIdempotent() {
        var fixture = new Fixture();
        var request = request();
        var first = fixture.intake.submit("Bearer alice", "same", request);
        assertEquals("CONFIRMED_RECEIVED", first.path("receiptStatus").asText());
        assertEquals(first, fixture.intake.submit("Bearer alice", "same", request.deepCopy()));
        assertEquals(first, fixture.intake.recover("Bearer alice", "same", "op008"));
        assertEquals(1, fixture.rows.size());
    }

    @Test void mismatchedPayloadAndCrossAccountReadKeepTheOriginalReceiptUnchanged() {
        var fixture = new Fixture();
        var original = fixture.intake.submit("Bearer alice", "same", request());
        assertEquals("MINI_SUBMISSION_CONFLICT", assertThrows(IllegalArgumentException.class,
                () -> fixture.intake.submit("Bearer alice", "same", request().put("treatmentNeed", "other"))).getMessage());
        var hidden = fixture.intake.recover("Bearer bob", "same", "op008");
        assertEquals("UNKNOWN", hidden.path("receiptStatus").asText());
        assertFalse(hidden.has("requestId"));
        assertEquals(original, fixture.intake.recover("Bearer alice", "same", "op008"));
    }

    @Test void loginCannotClaimAnExistingPatientOrGuardianAuthority() {
        var fixture = new Fixture();
        assertThrows(IllegalArgumentException.class,
                () -> fixture.intake.submit("Bearer alice", "key", request().put("patientId", "123")));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.intake.submit("Bearer alice", "key", request().put("actingMode", "GUARDIAN")));
        assertThrows(SecurityException.class, () -> fixture.intake.submit("Bearer revoked", "key", request()));
        assertTrue(fixture.rows.isEmpty());
    }

    @Test void changeFollowsDedicatedAuthorityPathAndOnlyAcknowledgesReceipt() {
        var fixture = new Fixture();
        var change = request().put("type", "CHANGE").put("patientId", "201").put("originalAppointmentId", "301");
        change.remove(java.util.List.of("intendedClinicId", "treatmentNeed", "bookingIntent"));
        assertThrows(SecurityException.class, () -> fixture.intake.submit("Bearer bob", "change", change));
        assertThrows(IllegalArgumentException.class, () -> fixture.intake.submit("Bearer alice", "change",
                change.deepCopy().put("originalAppointmentId", "-301")));
        assertTrue(fixture.rows.isEmpty());
        var result = fixture.intake.submit("Bearer alice", "change", change);
        assertEquals("CONFIRMED_RECEIVED", result.path("receiptStatus").asText());
        assertFalse(result.has("appointmentId"));
        assertEquals(result, fixture.intake.recover("Bearer alice", "change", "op008"));
    }

    private static ObjectNode request() {
        var request = JSON.createObjectNode().put("type", "NEW").put("actingMode", "SELF")
                .put("intendedClinicId", "81001").put("treatmentNeed", "SYNTHETIC preliminary need");
        request.putObject("identity").put("patientIdentification", "SYNTHETIC")
                .put("applicantInformation", "SYNTHETIC").put("contactInformation", "SYNTHETIC");
        request.putObject("preferred").put("date", "2026-11-16");
        request.putObject("bookingIntent").put("visitType", "INITIAL");
        return request;
    }

    private static final class Fixture implements MiniNewRequestIntake.Store {
        private record Key(String account, String key) {}
        final HashMap<Key, MiniNewRequestIntake.Receipt> rows = new HashMap<>();
        final MiniNewRequestIntake intake = new MiniNewRequestIntake(new MiniRequestSession(code -> Optional.empty(),
                new MiniRequestSession.Sessions() {
                    public String issue(String account) { throw new AssertionError("not used"); }
                    public Optional<String> activeAccount(String token) {
                        return "alice".equals(token) || "bob".equals(token) ? Optional.of(token) : Optional.empty();
                    }
                }), this);

        public MiniNewRequestIntake.Receipt saveOnce(String account, String key, String hash, JsonNode input) {
            return rows.computeIfAbsent(new Key(account, key),
                    k -> new MiniNewRequestIntake.Receipt(hash, "SYNTHETIC-" + rows.size()));
        }

        @Override
        public MiniNewRequestIntake.Receipt saveChange(String account, String key, String hash, JsonNode input) {
            if (!"alice".equals(account)) throw new SecurityException("denied");
            return saveOnce(account, key, hash, input);
        }

        public Optional<MiniNewRequestIntake.Receipt> find(String account, String key) {
            return Optional.ofNullable(rows.get(new Key(account, key)));
        }
    }
}
