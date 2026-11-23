package cn.adc.appointments;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Access credentials only; registration never establishes patient authority. */
final class PatientAccounts {
    record Credential(UUID id, String digest, boolean disabled) {
        @Override public String toString() { return "Credential[redacted]"; }
    }
    interface Store {
        /** Atomically inserts or returns false for an existing normalized username. */
        boolean create(UUID id, String username, String digest);
        Optional<Credential> find(String username);
        boolean enabled(UUID id);
    }
    private final Store store;
    private final PatientAccountPasswords passwords;
    private final MiniRequestSession.Sessions sessions;
    private final String dummyDigest;

    PatientAccounts(Store store, PatientAccountPasswords passwords, MiniRequestSession.Sessions sessions) {
        this.store = java.util.Objects.requireNonNull(store);
        this.passwords = java.util.Objects.requireNonNull(passwords);
        this.sessions = java.util.Objects.requireNonNull(sessions);
        char[] dummy = UUID.randomUUID().toString().toCharArray();
        try { dummyDigest = passwords.hash(dummy); }
        finally { Arrays.fill(dummy, '\0'); }
    }

    UUID register(String username, char[] password) {
        try {
            String normalized = username(username);
            UUID id = UUID.randomUUID();
            String digest = passwords.hash(password);
            if (!store.create(id, normalized, digest)) throw new IllegalArgumentException("ACCOUNT_USERNAME_UNAVAILABLE");
            return id;
        } finally { clear(password); }
    }

    String login(String username, char[] password) {
        try {
            String normalized;
            try { normalized = username(username); }
            catch (IllegalArgumentException invalid) { normalized = ""; }
            var credential = store.find(normalized);
            boolean matched = passwords.matches(password, credential.map(Credential::digest).orElse(dummyDigest));
            if (!matched || credential.isEmpty() || credential.get().disabled()
                    || !store.enabled(credential.get().id())) throw new SecurityException("ACCOUNT_LOGIN_FAILED");
            return sessions.issue(principal(credential.get().id()));
        } finally { clear(password); }
    }

    static String principal(UUID id) { return "ACCOUNT:" + id; }
    static String username(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{4,32}"))
            throw new IllegalArgumentException("ACCOUNT_USERNAME_FORMAT_REQUIRED");
        return value.toLowerCase(Locale.ROOT);
    }
    private static void clear(char[] password) { if (password != null) Arrays.fill(password, '\0'); }
}
