package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;

/** UC01 verified lookup. The response exposes only the original patient ID and display name. */
public final class PatientDirectoryQuery {
    @FunctionalInterface
    public interface VerifiedIdentity {
        Optional<String> resolve(Connection connection, String reference, String actor, long clinic) throws SQLException;
    }

    private final JdbcTransactions transactions;
    private final StaffAuthorization authorization;
    private final VerifiedIdentity identities;

    public PatientDirectoryQuery(JdbcTransactions transactions, StaffAuthorization authorization, VerifiedIdentity identities) {
        this.transactions = transactions;
        this.authorization = authorization;
        this.identities = identities;
    }

    public JsonNode query(JsonNode request) {
        AuditDocuments.fields(request, "verifiedIdentityContext", "clinicId");
        String reference = AuditDocuments.text(request, "verifiedIdentityContext");
        String clinicText = AuditDocuments.text(request, "clinicId");
        long clinic = Long.parseLong(clinicText);
        if (clinic <= 0 || !Long.toString(clinic).equals(clinicText)) throw new IllegalArgumentException("INVALID_CLINIC");
        var scope = Set.of(clinic);
        String actor = authorization.require("UC01", scope).actor();
        return transactions.execute(connection -> {
            var identity = identities.resolve(connection, reference, actor, clinic).filter(value -> !value.isBlank())
                    .orElseThrow(() -> new SecurityException("PATIENT_IDENTITY_UNVERIFIED"));
            var result = AuditDocuments.JSON.createObjectNode();
            var items = result.putArray("items");
            try (var query = connection.prepareStatement("""
                    SELECT patient_id, patient_name FROM adc.patient WHERE identity_information=?
                    ORDER BY patient_id LIMIT 2
                    """)) {
                query.setString(1, identity);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        items.addObject().put("patientId", Long.toString(rows.getLong(1)))
                                .put("displayName", rows.getString(2));
                        if (rows.next()) throw new IllegalArgumentException("PATIENT_IDENTITY_AMBIGUOUS");
                    }
                }
            }
            if (!actor.equals(authorization.require("UC01", scope).actor())) {
                throw new SecurityException("PATIENT_LOOKUP_ACTOR_CHANGED");
            }
            return result;
        });
    }
}
