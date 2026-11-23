package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService.Basis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AppointmentEvidenceCodecTest {
    static final Instant START = Instant.parse("2026-11-08T09:00:00.123456Z");
    static final Instant END = Instant.parse("2026-11-08T09:27:00.123456Z");
    static final String DOCUMENT = """
            {
              "treatmentRequest": "SYNTHETIC 需求  保留空格\\n及换行",
              "otherIntentions": ["SYNTHETIC 复核", {"note":"保留原对象"}],
              "bookingDurationBasis": {
                "schemaVersion": 1, "primaryProjectId": "85001", "projectVersion": 2,
                "projectNameSnapshot": "SYNTHETIC 旧名称", "adoptedDurationMinutes": 27,
                "basisMode": "PRESERVED_ORIGINAL", "originBasisReference": "SYNTHETIC/original",
                "timeInterpretation": {
                  "zonePolicyVersion": "SYNTHETIC-zone-v1", "clinicId": "81001", "zoneId": "Asia/Shanghai",
                  "sourceLocalStart": "2026-11-08T17:00:00.123456", "sourceLocalEnd": "2026-11-08T17:27:00.123456",
                  "localEndSource": "DERIVED_FROM_DURATION", "selectedOffsetStart": "+08:00", "selectedOffsetEnd": "+08:00",
                  "resolvedInstantStart": "2026-11-08T09:00:00.123456Z",
                  "resolvedInstantEnd": "2026-11-08T09:27:00.123456Z", "tzdbVersion": "SYNTHETIC-frozen"
                }
              }
            }
            """;

    @Test void t02701KeepsExactTextAndDoesNotRecomputeHistoricalBasis() {
        var frozen = AppointmentEvidenceCodec.readStructured(DOCUMENT);
        assertEquals(DOCUMENT, frozen.text());
        assertEquals(new Basis(85001, 2, 27), frozen.basis());
        assertEquals(81001, frozen.clinicId());
        assertEquals(START, frozen.startsAt());
        assertEquals(END, frozen.endsAt());
        assertSame(DOCUMENT, AppointmentEvidenceCodec.forWindow(DOCUMENT, START, END));
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "trailing", "duplicate", "duration", "precision", "version", "missing", "offset"})
    void t02702UnverifiedTextCannotBecomeNewFrozenBasis(String problem) {
        String candidate = switch (problem) {
            case "legacy" -> "原自由文本，没有已发布的时长依据";
            case "trailing" -> DOCUMENT + "{}";
            case "duplicate" -> DOCUMENT.replace("\"projectVersion\": 2", "\"projectVersion\": 3, \"projectVersion\": 2");
            case "duration" -> DOCUMENT.replace("\"adoptedDurationMinutes\": 27", "\"adoptedDurationMinutes\": 28");
            case "precision" -> DOCUMENT.replace(".123456Z", ".1234567Z");
            case "version" -> DOCUMENT.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");
            case "missing" -> DOCUMENT.replace("\"projectNameSnapshot\": \"SYNTHETIC 旧名称\",", "");
            case "offset" -> DOCUMENT.replace("+08:00", "+09:00");
            default -> throw new AssertionError(problem);
        };
        assertThrows(IllegalArgumentException.class, () -> AppointmentEvidenceCodec.readStructured(candidate));
    }

    @Test void t02703RejectsAnOtherwiseValidDocumentOnAnotherWindow() {
        assertThrows(IllegalArgumentException.class, () -> AppointmentEvidenceCodec.forWindow(DOCUMENT, START, END.plusNanos(1000)));
    }
}
