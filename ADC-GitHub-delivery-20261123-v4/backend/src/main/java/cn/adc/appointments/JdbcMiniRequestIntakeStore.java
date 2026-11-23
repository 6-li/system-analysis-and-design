package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** Candidate implementation for the independent mini-program extension storage. */
final class JdbcMiniRequestIntakeStore implements MiniNewRequestIntake.Store {
    @FunctionalInterface
    interface AppointmentRequestAuthority {
        boolean mayRequest(Connection c, String account, long patient, long appointment) throws SQLException;
    }

    private final JdbcTransactions transactions;
    private final Clock clock;
    private final AppointmentRequestAuthority changeAuthority;
    private final AppointmentRequestAuthority cancelAuthority;
    @FunctionalInterface interface BoundPatient { Long resolve(Connection c,String account,JsonNode input)throws SQLException; }
    private BoundPatient boundPatient;
    JdbcMiniRequestIntakeStore withBoundPatient(BoundPatient resolver){this.boundPatient=java.util.Objects.requireNonNull(resolver);return this;}

    JdbcMiniRequestIntakeStore(JdbcTransactions transactions, Clock clock) {
        this(transactions, clock, null);
    }

    JdbcMiniRequestIntakeStore(JdbcTransactions transactions, Clock clock, AppointmentRequestAuthority changeAuthority) {
        this(transactions, clock, changeAuthority, null);
    }

    JdbcMiniRequestIntakeStore(JdbcTransactions transactions, Clock clock, AppointmentRequestAuthority changeAuthority,
                              AppointmentRequestAuthority cancelAuthority) {
        this.transactions = transactions;
        this.clock = clock;
        this.changeAuthority = changeAuthority;
        this.cancelAuthority = cancelAuthority;
    }

    @Override
    public MiniNewRequestIntake.Receipt saveOnce(String account, String key, String hash, JsonNode input) {
        return transactions.execute(c -> {
            var existing=find(c,account,key);
            if(existing.isPresent())return existing.orElseThrow();
            Long patient=boundPatient==null?null:boundPatient.resolve(c,account,input);
            return persist(c,account,key,hash,input,patient);
        });
    }

    @Override
    public MiniNewRequestIntake.Receipt saveChange(String account, String key, String hash, JsonNode input) {
        return saveExisting(account, key, hash, input, changeAuthority);
    }

    @Override
    public MiniNewRequestIntake.Receipt saveCancel(String account, String key, String hash, JsonNode input) {
        return saveExisting(account, key, hash, input, cancelAuthority);
    }

    private MiniNewRequestIntake.Receipt saveExisting(String account, String key, String hash, JsonNode input,
                                                      AppointmentRequestAuthority authority) {
        if (authority == null) throw new SecurityException("MINI_APPOINTMENT_REQUEST_UNAVAILABLE");
        return transactions.execute(c -> {
            var existing = find(c, account, key);
            if (existing.isPresent()) return existing.orElseThrow();
            long patient = InitialBookingReview.id(input, "patientId");
            long appointment = InitialBookingReview.id(input, "originalAppointmentId");
            if (!authority.mayRequest(c, account, patient, appointment)) {
                throw new SecurityException("MINI_APPOINTMENT_REQUEST_DENIED");
            }
            try (var query = c.prepareStatement("""
                    SELECT 1 FROM adc.appointment
                    WHERE appointment_id=? AND patient_id=? AND appointment_status='待到诊'
                    """)) {
                query.setLong(1, appointment);
                query.setLong(2, patient);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new SecurityException("MINI_APPOINTMENT_REQUEST_DENIED");
                }
            }
            return persist(c, account, key, hash, input, patient);
        });
    }

    private MiniNewRequestIntake.Receipt persist(Connection c, String account, String key, String hash, JsonNode input,
                                                Long patient) throws SQLException {
            var id = UUID.randomUUID();
            var receivedAt = StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC);
            int inserted;
            boolean boundNew=boundPatient!=null&&patient!=null&&"NEW".equals(input.path("type").asText());
            try (var statement = c.prepareStatement("""
                    INSERT INTO adc_mini.preliminary_request
                         (request_id, applicant_account, submission_key, payload_hash, submitted_content, received_at,
                          associated_patient_id%s)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?%s)
                    ON CONFLICT (applicant_account, submission_key) DO NOTHING
                    """.formatted(boundNew?",request_version":"",boundNew?", ?":""))) {
                statement.setObject(1, id);
                statement.setString(2, account);
                statement.setString(3, key);
                statement.setString(4, hash);
                statement.setString(5, input.toString());
                statement.setObject(6, receivedAt);
                statement.setObject(7, patient, java.sql.Types.BIGINT);
                if(boundNew)statement.setInt(8,1);
                inserted = statement.executeUpdate();
            }
            if (inserted == 0) return find(c, account, key)
                    .orElseThrow(() -> new SQLException("MINI_RECEIPT_UNCONFIRMED", "P0001"));
            try (var statement = c.prepareStatement("""
                    INSERT INTO adc_mini.operation_audit_event (request_id, operation, event_at) VALUES (?, 'op008', ?)
                    """)) {
                statement.setObject(1, id);
                statement.setObject(2, receivedAt);
                if (statement.executeUpdate() != 1) throw new SQLException("MINI_INTAKE_AUDIT_UNCONFIRMED", "P0001");
            }
            return new MiniNewRequestIntake.Receipt(hash, id.toString());
    }

    @Override
    public Optional<MiniNewRequestIntake.Receipt> find(String account, String key) {
        return transactions.execute(c -> find(c, account, key));
    }

    private static Optional<MiniNewRequestIntake.Receipt> find(Connection c, String account, String key)
            throws SQLException {
        try (var statement = c.prepareStatement("""
                SELECT r.payload_hash, r.request_id::text FROM adc_mini.preliminary_request r
                JOIN adc_mini.operation_audit_event a ON a.request_id=r.request_id AND a.operation='op008'
                WHERE r.applicant_account=? AND r.submission_key=?
                """)) {
            statement.setString(1, account);
            statement.setString(2, key);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new MiniNewRequestIntake.Receipt(rows.getString(1), rows.getString(2)))
                        : Optional.empty();
            }
        }
    }
}
