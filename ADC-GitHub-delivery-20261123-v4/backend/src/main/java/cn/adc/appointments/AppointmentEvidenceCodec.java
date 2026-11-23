package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

/** Validates new frozen TEXT documents without rewriting the supplied text or consulting CURRENT. */
final class AppointmentEvidenceCodec {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> MODES = Set.of("INITIAL_CURRENT", "CHANGED_PROJECT_CURRENT", "PRESERVED_ORIGINAL");

    record Frozen(String text, Basis basis, long clinicId, Instant startsAt, Instant endsAt) {}

    private AppointmentEvidenceCodec() {}

    static Frozen readStructured(String stored) {
        if (stored == null) throw new IllegalArgumentException("FROZEN_EVIDENCE_REQUIRED");
        final JsonNode document;
        try {
            document = JSON.readTree(stored);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("INVALID_FROZEN_EVIDENCE");
        }
        text(document, "treatmentRequest");
        var basis = document.path("bookingDurationBasis");
        if (StrictInputPolicy.positiveMinutes(basis.get("schemaVersion")) != 1) {
            throw new IllegalArgumentException("UNKNOWN_BOOKING_EVIDENCE_VERSION");
        }
        text(basis, "projectNameSnapshot");
        if (!MODES.contains(text(basis, "basisMode"))) throw new IllegalArgumentException("INVALID_BOOKING_BASIS_MODE");
        var adopted = new Basis(id(basis, "primaryProjectId"),
                StrictInputPolicy.positiveMinutes(basis.get("projectVersion")),
                StrictInputPolicy.positiveMinutes(basis.get("adoptedDurationMinutes")));
        var time = basis.path("timeInterpretation");
        long clinic = id(time, "clinicId");
        text(time, "zonePolicyVersion");
        text(time, "zoneId");
        text(time, "tzdbVersion");
        if (!Set.of("PROVIDED", "DERIVED_FROM_DURATION").contains(text(time, "localEndSource"))) {
            throw new IllegalArgumentException("INVALID_BOOKING_TIME_SOURCE");
        }
        var start = endpoint(time, "Start");
        var end = endpoint(time, "End");
        requireWindow(start, end, adopted);
        return new Frozen(stored, adopted, clinic, start, end);
    }

    static String forWindow(String stored, Instant start, Instant end) {
        var frozen = readStructured(stored);
        if (!frozen.startsAt().equals(start) || !frozen.endsAt().equals(end)) {
            throw new IllegalArgumentException("FROZEN_WINDOW_MISMATCH");
        }
        return frozen.text();
    }

    private static Instant endpoint(JsonNode time, String name) {
        var resolved = StrictInputPolicy.instant(text(time, "resolvedInstant" + name));
        var local = StrictInputPolicy.local(text(time, "sourceLocal" + name));
        final ZoneOffset offset;
        try {
            offset = ZoneOffset.of(text(time, "selectedOffset" + name));
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("INVALID_BOOKING_OFFSET");
        }
        if (!resolved.equals(local.toInstant(offset))) throw new IllegalArgumentException("FROZEN_TIME_MISMATCH");
        return resolved;
    }

    private static void requireWindow(Instant start, Instant end, Basis basis) {
        if (!Duration.between(start, end).equals(Duration.ofMinutes(basis.minutes()))) {
            throw new IllegalArgumentException("FROZEN_DURATION_MISMATCH");
        }
    }

    private static long id(JsonNode node, String field) {
        String value = text(node, field);
        if (!value.matches("-?[0-9]+")) throw new IllegalArgumentException("INVALID_FROZEN_ID: " + field);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("INVALID_FROZEN_ID: " + field);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isString() || value.asText().isBlank()) {
            throw new IllegalArgumentException("FROZEN_FIELD_REQUIRED: " + field);
        }
        return value.asText();
    }
}
