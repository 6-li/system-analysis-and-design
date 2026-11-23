package cn.adc.appointments;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PatientAccountsTest {
    static final class MemoryStore implements PatientAccounts.Store {
        final Map<String, PatientAccounts.Credential> rows = new HashMap<>();
        public boolean create(UUID id, String name, String digest) {
            return rows.putIfAbsent(name, new PatientAccounts.Credential(id, digest, false)) == null;
        }
        public Optional<PatientAccounts.Credential> find(String name) { return Optional.ofNullable(rows.get(name)); }
        public boolean enabled(UUID id) { return rows.values().stream().anyMatch(c -> c.id().equals(id) && !c.disabled()); }
    }
    @Test void independentAccountsSurviveServiceRecreationAndPasswordsAreCleared() {
        var store = new MemoryStore();
        var sessions = new WeChatCourseSessionConfiguration.ExpiringSessions(Clock.systemUTC());
        var service = new PatientAccounts(store, new PatientAccountPasswords(), sessions);
        char[] first = "TEST-first-password".toCharArray();
        UUID alice = service.register("Alice", first);
        assertArrayEquals(new char[first.length], first);
        UUID bob = service.register("Bob_1", "TEST-second-password".toCharArray());
        assertNotEquals(alice, bob);
        var restarted = new PatientAccounts(store, new PatientAccountPasswords(), sessions);
        String token = restarted.login("ALICE", "TEST-first-password".toCharArray());
        assertEquals(Optional.of(PatientAccounts.principal(alice)), sessions.activeAccount(token));
        assertFalse(store.rows.get("alice").toString().contains("PBKDF2"));
    }
    @Test void duplicateWrongUnknownAndDisabledAccountsDoNotIssueSessions() {
        var store = new MemoryStore();
        var sessions = new WeChatCourseSessionConfiguration.ExpiringSessions(Clock.systemUTC());
        var service = new PatientAccounts(store, new PatientAccountPasswords(), sessions);
        UUID id = service.register("Alice", "TEST-first-password".toCharArray());
        assertThrows(IllegalArgumentException.class, () -> service.register("ALICE", "TEST-second-password".toCharArray()));
        for (String name : List.of("Alice", "Unknown", "!")) {
            char[] password = "TEST-wrong-password".toCharArray();
            assertEquals("ACCOUNT_LOGIN_FAILED", assertThrows(SecurityException.class, () -> service.login(name, password)).getMessage());
            assertArrayEquals(new char[password.length], password);
        }
        var old = store.rows.get("alice");
        store.rows.put("alice", new PatientAccounts.Credential(id, old.digest(), true));
        assertThrows(SecurityException.class, () -> service.login("Alice", "TEST-first-password".toCharArray()));
        assertEquals(1, store.rows.size());
    }
}
