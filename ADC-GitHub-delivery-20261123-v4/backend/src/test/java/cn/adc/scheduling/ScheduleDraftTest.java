package cn.adc.scheduling;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** UC21 tests against existing foundations. No draft service or database is constructed. */
class ScheduleDraftTest {
    @Test
    void uc21RequiresItsOwnActionAndClinicAndRereadsRevocation() {
        var grants = new AtomicReference<>(Map.of("synthetic-manager",
                new StaffAuthorization.Grant("v1", Set.of("CLINIC_MANAGER"), Set.of("UC21"), Set.of(1L), false)));
        var auth = new StaffAuthorization(() -> Optional.of("synthetic-manager"), grants::get);
        assertEquals(Set.of(1L), auth.require("UC21", Set.of(1L)).clinicIds());
        assertThrows(SecurityException.class, () -> auth.require("UC21", Set.of(2L)));
        assertThrows(SecurityException.class, () -> auth.require("UC22", Set.of(1L)));
        grants.set(Map.of());
        assertThrows(SecurityException.class, () -> auth.require("UC21", Set.of(1L)));
    }

    @Test
    void absoluteEndpointsPreserveOffsetsAndRejectAmbiguousPrecision() {
        assertEquals(Instant.parse("2026-11-07T01:00:00.123456Z"),
                StrictInputPolicy.instant("2026-11-07T10:00:00.123456+09:00"));
        assertThrows(IllegalArgumentException.class, () -> StrictInputPolicy.instant("2026-11-07T10:00:00"));
        assertThrows(IllegalArgumentException.class, () -> StrictInputPolicy.instant("2026-11-07T10:00:00.1234567+09:00"));
    }

    @Test
    void auditIdentityAndCanonicalPayloadDistinguishRepeatedAndChangedAttempts() {
        var key = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
        String reference = AuditDocuments.requestReference("synthetic-manager", "UC", "UC21_DRAFT_SAVE", key);
        assertEquals(reference, AuditDocuments.requestReference("synthetic-manager", "UC", "UC21_DRAFT_SAVE", key));
        assertNotEquals(reference, AuditDocuments.requestReference("synthetic-other", "UC", "UC21_DRAFT_SAVE", key));
        assertNotEquals(reference, AuditDocuments.requestReference("synthetic-manager", "UC", "UC21_MAINTENANCE_ISSUE_SAVE", key));
        String canonical = AuditDocuments.hash(AuditDocuments.parse("{\"clinicId\":\"1\",\"dentistId\":\"2\"}"));
        assertEquals(canonical, AuditDocuments.hash(AuditDocuments.parse("{\"dentistId\":\"2\",\"clinicId\":\"1\"}")));
        assertNotEquals(canonical, AuditDocuments.hash(AuditDocuments.parse("{\"dentistId\":\"3\",\"clinicId\":\"1\"}")));
    }
}
