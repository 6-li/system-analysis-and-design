package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

class MiniIntakeIsolatedPgIT {
    private static final String ACCOUNT = "SYNTHETIC-UC01-account";
    private final T028IsolatedPgIT.IsolatedData db = new T028IsolatedPgIT.IsolatedData("runtime", 0);
    private final JdbcMiniRequestIntakeStore store = new JdbcMiniRequestIntakeStore(new JdbcTransactions(db),
            Clock.systemUTC());

    @Test void requestAndAuditCommitAndDuplicateReadsOriginal() throws Exception {
        var input = AuditDocuments.JSON.createObjectNode().put("treatmentNeed", "SYNTHETIC");
        var key = "SYNTHETIC-main-path";
        var first = store.saveOnce(ACCOUNT, key, AuditDocuments.hash(input), input);
        var changed = input.deepCopy().put("treatmentNeed", "SYNTHETIC alternate");
        assertEquals(first, store.saveOnce(ACCOUNT, key, AuditDocuments.hash(changed), changed));
        assertEquals(first, store.find(ACCOUNT, key).orElseThrow());
        assertTrue(store.find("SYNTHETIC-other", key).isEmpty());
        try (var c = db.getConnection(); var p = c.prepareStatement("""
                SELECT count(*) FROM adc_mini.operation_audit_event WHERE request_id=?::uuid AND operation='op008'
                """)) {
            p.setString(1, first.requestId());
            try (var rows = p.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test void failingAuditRollsBackRequestAndReceipt() throws Exception {
        var key = "SYNTHETIC-force-audit-failure";
        var input = AuditDocuments.JSON.createObjectNode().put("treatmentNeed", "SYNTHETIC audit error");
        var failure = assertThrows(JdbcTransactions.Failure.class,
                () -> store.saveOnce(ACCOUNT, key, AuditDocuments.hash(input), input));
        assertEquals("23514", failure.sqlState());
        assertFalse(failure.unknown());
        assertTrue(store.find(ACCOUNT, key).isEmpty());
        try (var c = db.getConnection(); var p = c.prepareStatement(
                "SELECT 1 FROM adc_mini.preliminary_request WHERE applicant_account=? AND submission_key=?")) {
            p.setString(1, ACCOUNT);
            p.setString(2, key);
            try (var rows = p.executeQuery()) { assertFalse(rows.next()); }
        }
    }

    @Test void lostCommitAcknowledgementIsRecoverableWithoutAnotherWrite() {
        var lossy = new JdbcMiniRequestIntakeStore(new JdbcTransactions(
                new T028IsolatedPgIT.IsolatedData("runtime", 1)), Clock.systemUTC());
        var key = "SYNTHETIC-lost-ack";
        var input = AuditDocuments.JSON.createObjectNode().put("treatmentNeed", "SYNTHETIC lost ack");
        String hash = AuditDocuments.hash(input);
        assertTrue(assertThrows(JdbcTransactions.Failure.class,
                () -> lossy.saveOnce(ACCOUNT, key, hash, input)).unknown());
        assertEquals(hash, store.find(ACCOUNT, key).orElseThrow().payloadHash());
    }
}
