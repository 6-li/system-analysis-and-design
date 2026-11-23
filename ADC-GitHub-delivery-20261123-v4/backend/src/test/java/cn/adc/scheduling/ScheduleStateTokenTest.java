package cn.adc.scheduling;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleStateTokenTest {
    @Test
    void orderingDoesNotChangeTokenButSubsequentCommitDoes() {
        var basis = version(15, 1, "EFFECTIVE", "clear");
        var existingDraft = version(15, 2, "DRAFT", "clear");
        var token = ScheduleStateToken.of(basis, List.of(existingDraft, basis));
        assertEquals(token, ScheduleStateToken.of(basis, List.of(basis, existingDraft)));
        assertNotEquals(token, ScheduleStateToken.of(basis, List.of(basis, existingDraft, version(15, 3, "DRAFT", "clear"))));
        assertNotEquals(token, ScheduleStateToken.of(existingDraft, List.of(basis, existingDraft)));
    }

    @Test
    void bindsOriginalStateAndNullsWithoutUsingMaximumAsCurrent() {
        var basis = version(15, 1, "EFFECTIVE", "clear");
        var draft = version(15, 2, "DRAFT", null);
        assertNotEquals(ScheduleStateToken.of(basis, List.of(basis, draft)),
                ScheduleStateToken.of(basis, List.of(basis, version(15, 2, "DRAFT", ""))));
        assertNotEquals(ScheduleStateToken.of(basis, List.of(basis, draft)),
                ScheduleStateToken.of(basis, List.of(basis, version(15, 2, "SUPERSEDED", null))));
    }

    @Test
    void missingBasisMixedIdentityAndDuplicateKeysAreRejected() {
        var basis = version(15, 1, "DRAFT", null);
        assertThrows(IllegalArgumentException.class, () -> ScheduleStateToken.of(basis, List.of(version(15, 2, "DRAFT", null))));
        assertThrows(IllegalArgumentException.class, () -> ScheduleStateToken.of(basis, List.of(basis, version(16, 1, "DRAFT", null))));
        assertThrows(IllegalArgumentException.class, () -> ScheduleStateToken.of(basis, List.of(basis, basis)));
    }

    private static ScheduleStateToken.Version version(long id, int no, String status, String coordination) {
        return new ScheduleStateToken.Version(id, no, 1, 2, Instant.parse("2026-11-07T09:00:00.123456Z"),
                Instant.parse("2026-11-07T10:00:00Z"), status, coordination, "verified", "clear", null,
                status.equals("DRAFT") ? null : Instant.parse("2026-11-06T10:00:00Z"));
    }
}
