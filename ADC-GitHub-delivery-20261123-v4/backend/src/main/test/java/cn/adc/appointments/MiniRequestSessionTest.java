package cn.adc.appointments;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;

class MiniRequestSessionTest {
    @Test void loginExchangesTheCodeWithoutClaimingPatientIdentityAndChecksRevocationOnEveryRead() {
        var active = new AtomicBoolean(true);
        var service = new MiniRequestSession(code -> "SYNTHETIC-code".equals(code)
                ? Optional.of("SYNTHETIC-account") : Optional.empty(), new MiniRequestSession.Sessions() {
            public String issue(String account) {
                assertEquals("SYNTHETIC-account", account);
                return "SYNTHETIC-token";
            }
            public Optional<String> activeAccount(String token) {
                return active.get() && "SYNTHETIC-token".equals(token) ? Optional.of("SYNTHETIC-account") : Optional.empty();
            }
        });
        assertThrows(SecurityException.class, () -> service.login(JSON.createObjectNode().put("code", "forged")));
        var login = service.login(JSON.createObjectNode().put("code", "SYNTHETIC-code"));
        assertFalse(login.path("identityVerified").asBoolean(true));
        assertEquals("SYNTHETIC-account", service.account("Bearer " + login.path("accessToken").asText()));
        active.set(false);
        assertThrows(SecurityException.class, () -> service.account("Bearer SYNTHETIC-token"));
    }
}
