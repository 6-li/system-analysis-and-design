package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

/** op001 exchanges a login code for an application session; patient authority is checked separately. */
final class MiniRequestSession {
    interface Accounts {
        Optional<String> exchangeCode(String code);
    }

    interface Sessions {
        String issue(String account);
        Optional<String> activeAccount(String token);
        default void revoke(String token) { throw new IllegalStateException("SESSION_REVOCATION_UNAVAILABLE"); }
    }

    private final Accounts accounts;
    private final Sessions sessions;

    MiniRequestSession(Accounts accounts, Sessions sessions) {
        this.accounts = java.util.Objects.requireNonNull(accounts);
        this.sessions = java.util.Objects.requireNonNull(sessions);
    }

    JsonNode login(JsonNode input) {
        AuditDocuments.fields(input, "code");
        var code = AuditDocuments.text(input, "code");
        var account = accounts.exchangeCode(code).filter(value -> !value.isBlank())
                .orElseThrow(() -> new SecurityException("MINI_LOGIN_UNVERIFIED"));
        var token = sessions.issue(account);
        if (token == null || token.isBlank() || token.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("MINI_SESSION_UNAVAILABLE");
        }
        return AuditDocuments.JSON.createObjectNode().put("accessToken", token).put("identityVerified", false);
    }

    String account(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.length() == 7) {
            throw new SecurityException("MINI_SESSION_REQUIRED");
        }
        var token = header.substring(7);
        if (token.codePoints().anyMatch(Character::isWhitespace)) throw new SecurityException("MINI_SESSION_REQUIRED");
        return sessions.activeAccount(token).filter(value -> !value.isBlank())
                .orElseThrow(() -> new SecurityException("MINI_SESSION_REQUIRED"));
    }

    void logout(String header) {
        account(header);
        sessions.revoke(header.substring(7));
    }
}
