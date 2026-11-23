package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import java.util.Optional;
import java.util.UUID;

/** Dedicated credential store. No patient table access or implicit grants. */
final class JdbcPatientAccounts implements PatientAccounts.Store {
    private final JdbcTransactions transactions;
    JdbcPatientAccounts(JdbcTransactions transactions) { this.transactions = transactions; }
    @Override public boolean create(UUID id, String username, String digest) {
        return transactions.execute(c -> {
            try (var q = c.prepareStatement("INSERT INTO adc_mini.patient_account(account_id,username,password_digest) VALUES (?,?,?) ON CONFLICT (username) DO NOTHING")) {
                q.setObject(1, id); q.setString(2, username); q.setString(3, digest);
                return q.executeUpdate() == 1;
            }
        });
    }
    @Override public Optional<PatientAccounts.Credential> find(String username) {
        return transactions.execute(c -> {
            try (var q = c.prepareStatement("SELECT account_id,password_digest,disabled FROM adc_mini.patient_account WHERE username=?")) {
                q.setString(1, username);
                try (var r = q.executeQuery()) {
                    if (!r.next()) return Optional.empty();
                    return Optional.of(new PatientAccounts.Credential(r.getObject(1, UUID.class), r.getString(2), r.getBoolean(3)));
                }
            }
        });
    }
    @Override public boolean enabled(UUID id) {
        return transactions.execute(c -> {
            try (var q = c.prepareStatement("SELECT 1 FROM adc_mini.patient_account WHERE account_id=? AND NOT disabled")) {
                q.setObject(1, id);
                try (var r = q.executeQuery()) { return r.next(); }
            }
        });
    }
}
