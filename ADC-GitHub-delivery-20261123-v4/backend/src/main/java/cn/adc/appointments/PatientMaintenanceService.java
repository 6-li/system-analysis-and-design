package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/** Pure UC01 path. Its trusted verifier must establish source, identity and representative authority. */
final class PatientMaintenanceService {
    interface VerifiedContext {
        void requirePure(Connection c, String actor, long clinic, Long patient, JsonNode input) throws SQLException;
    }

    interface MiniSource {
        void requireCurrent(Connection c, String actor, long clinic, Long patient, JsonNode input) throws SQLException;
        JsonNode associate(Connection c, String actor, long clinic, long patient, JsonNode input,
                           String originalRequestReference) throws SQLException;
        boolean matchesCommitted(Connection c, String actor, long clinic, long patient, JsonNode input,
                                 JsonNode result) throws SQLException;
    }

    private final StaffAuthorization auth;
    private final VerifiedContext verifier;
    private final AuditStore.EventIdAllocator patients;
    private final CommandRecovery journal;
    private final MiniSource miniSource;

    PatientMaintenanceService(JdbcTransactions tx, StaffAuthorization auth, VerifiedContext verifier,
                              AuditStore.EventIdAllocator patients, AuditStore.EventIdAllocator audits, Clock clock) {
        this(tx, auth, verifier, patients, audits, clock, null);
    }

    PatientMaintenanceService(JdbcTransactions tx, StaffAuthorization auth, VerifiedContext verifier,
                              AuditStore.EventIdAllocator patients, AuditStore.EventIdAllocator audits, Clock clock,
                              MiniSource miniSource) {
        this.auth = java.util.Objects.requireNonNull(auth);
        this.verifier = java.util.Objects.requireNonNull(verifier);
        this.patients = java.util.Objects.requireNonNull(patients);
        this.miniSource = miniSource;
        this.journal = new UcAuditService(tx, auth, new CommandRecovery.SuccessVerifier() {
            public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
                var after = saved.path("after");
                var patient = after.path("patient");
                if (!patient.path("patientId").isString()) return false;
                long id = Long.parseLong(patient.path("patientId").asText());
                long clinic = command.clinics().iterator().next();
                String actor = auth.require("UC01", command.clinics()).actor();
                var input = command.payload().get("input");
                if (input.has("source")) {
                    return miniSource != null && "MINI_REQUEST".equals(after.path("sourceKind").asText())
                            && miniSource.matchesCommitted(c, actor, clinic, id, input, after)
                            && patient.equals(summary(c, id));
                }
                if (!"PURE_UC".equals(after.path("sourceKind").asText())) return false;
                verifier.requirePure(c, actor, clinic, id, input);
                return patient.equals(summary(c, id)) && saved.path("verification").path("profileHash").asText()
                        .equals(AuditDocuments.hash(profile(c, id)));
            }
            public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
                return true;
            }
        }, clock, java.util.Objects.requireNonNull(audits)).commands();
    }

    CommandRecovery.Receipt save(long clinic, Long patient, UUID key, JsonNode input) {
        if (clinic <= 0 || (patient != null && patient <= 0) || !input.isObject()
                || (input.has("source") && miniSource == null)) {
            throw new IllegalArgumentException("INVALID_PURE_PATIENT_COMMAND");
        }
        var names = Set.of("identityInformation", "patientName", "contactInformation", "emergencyContactInformation",
                "representativeIdentityInformation", "representativeVerificationInformation", "existingPatientId", "source");
        if (!names.containsAll(input.propertyNames())) throw new IllegalArgumentException("INVALID_PATIENT_FIELDS");
        String identity = AuditDocuments.text(input, "identityInformation");
        String name = AuditDocuments.text(input, "patientName");
        String contact = AuditDocuments.text(input, "contactInformation");
        if (name.codePointCount(0, name.length()) > 100) throw new IllegalArgumentException("PATIENT_NAME_TOO_LONG");
        String emergency = optional(input, "emergencyContactInformation");
        String representative = optional(input, "representativeIdentityInformation");
        String representativeCheck = optional(input, "representativeVerificationInformation");
        if ((representative == null) != (representativeCheck == null)
                || (input.has("existingPatientId") && (patient == null
                || !patient.toString().equals(AuditDocuments.text(input, "existingPatientId"))))) {
            throw new IllegalArgumentException("PATIENT_CONTEXT_MISMATCH");
        }
        var payload = AuditDocuments.JSON.createObjectNode().put("patientId", patient == null ? null : patient.toString());
        payload.set("input", input);
        var scope = Set.of(clinic);
        var command = new CommandRecovery.Command(patient == null ? "PATIENT_CREATE" : "PATIENT_UPDATE",
                "UC01", key, payload, scope);
        boolean fromMini = input.has("source");
        if (fromMini) {
            var source = input.get("source");
            if (!source.isObject() || !Set.of("kind", "requestId", "expectedRequestVersion", "identityMatchBasis",
                    "onsiteRecordReference").containsAll(source.propertyNames())
                    || !"MINI_REQUEST".equals(AuditDocuments.text(source, "kind"))
                    || !source.path("expectedRequestVersion").isIntegralNumber()
                    || !source.path("expectedRequestVersion").canConvertToInt()
                    || source.path("expectedRequestVersion").intValue() < 0) {
                throw new IllegalArgumentException("INVALID_PATIENT_SOURCE");
            }
            AuditDocuments.text(source, "requestId");
            AuditDocuments.text(source, "identityMatchBasis");
            if (source.has("onsiteRecordReference")) AuditDocuments.text(source, "onsiteRecordReference");
        }
        return journal.execute(command, (c, locks) -> {
            var actor = auth.require("UC01", scope).actor();
            var trustedInput = command.payload().get("input");
            if (fromMini) miniSource.requireCurrent(c, actor, clinic, patient, trustedInput);
            if (patient != null) lockIdentity(c, 41005, patient.toString());
            lockIdentity(c, 41004, identity);
            if (!actor.equals(auth.require("UC01", scope).actor())) throw new SecurityException("PATIENT_ACTOR_CHANGED");
            if (fromMini) miniSource.requireCurrent(c, actor, clinic, patient, trustedInput);
            else verifier.requirePure(c, actor, clinic, patient, trustedInput);
            checkIdentity(c, identity, patient);
            long id = patient == null ? patients.next(c) : patient;
            if (id <= 0) throw new SQLException("PATIENT_ID_UNAVAILABLE", "22003");
            var before = patient == null ? null : summary(c, id);
            if (patient != null && before.isNull()) throw new CommandRecovery.Rejection("PATIENT_NOT_FOUND");
            var sql = patient == null
                    ? """
                      INSERT INTO adc.patient(patient_name,identity_information,contact_information,
                        emergency_contact_information,representative_identity_information,
                        representative_verification_information,patient_id) VALUES (?,?,?,?,?,?,?)
                      """
                    : """
                      UPDATE adc.patient SET patient_name=?,identity_information=?,contact_information=?,
                        emergency_contact_information=?,representative_identity_information=?,
                        representative_verification_information=? WHERE patient_id=?
                      """;
            try (var write = c.prepareStatement(sql)) {
                write.setString(1, name); write.setString(2, identity); write.setString(3, contact);
                write.setString(4, emergency); write.setString(5, representative);
                write.setString(6, representativeCheck); write.setLong(7, id);
                if (write.executeUpdate() != 1) throw new SQLException("PATIENT_WRITE_UNCONFIRMED", "P0001");
            }
            var result = AuditDocuments.JSON.createObjectNode().put("sourceKind", fromMini ? "MINI_REQUEST" : "PURE_UC");
            result.set("patient", summary(c, id));
            var reference = AuditDocuments.JSON.createObjectNode().put("patientId", Long.toString(id));
            var verification = AuditDocuments.JSON.createObjectNode().put("profileHash", AuditDocuments.hash(profile(c, id)));
            if (fromMini) {
                var linked = miniSource.associate(c, actor, clinic, id, trustedInput, journal.reference(command));
                AuditDocuments.fields(linked, "sourceAssociation", "extensionResultEvent");
                var association = linked.path("sourceAssociation");
                var event = linked.path("extensionResultEvent");
                if (!Long.toString(id).equals(association.path("patientId").asText())
                        || !trustedInput.path("source").path("requestId").equals(association.path("requestId"))
                        || !association.path("patientAccessGrantedByAssociation").isBoolean()
                        || association.path("patientAccessGrantedByAssociation").asBoolean()
                        || !"MINI_EXTENSION".equals(event.path("source").asText())
                        || !"COMMITTED_RESULT".equals(event.path("role").asText())
                        || !event.path("eventId").isString() || event.path("eventId").asText().isBlank()
                        || !event.path("commandReference").isString() || event.path("commandReference").asText().isBlank()) {
                    throw new SQLException("MINI_ASSOCIATION_UNCONFIRMED", "P0001");
                }
                result.set("sourceAssociation", association);
                result.set("extensionResultEvent", event);
            }
            return new AuditDocuments.Change(reference, before, result, verification);
        });
    }

    private static String optional(JsonNode input, String name) {
        return input.has(name) ? AuditDocuments.text(input, name) : null;
    }

    private static void lockIdentity(Connection c, int namespace, String key) throws SQLException {
        try (var p = c.prepareStatement("SELECT pg_advisory_xact_lock(?,?)")) {
            p.setInt(1, namespace);
            p.setInt(2, (int) ResourceLockCoordinator.key(key));
            p.execute();
        }
    }

    private static void checkIdentity(Connection c, String identity, Long patient) throws SQLException {
        try (var p = c.prepareStatement("SELECT patient_id FROM adc.patient WHERE identity_information=?")) {
            p.setString(1, identity);
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    if (patient == null || patient != rows.getLong(1)) {
                        throw new CommandRecovery.Rejection("PATIENT_IDENTITY_ALREADY_EXISTS");
                    }
                }
            }
        }
    }

    private static JsonNode profile(Connection c, long id) throws SQLException {
        try (var p = c.prepareStatement("SELECT to_jsonb(p)::text FROM adc.patient p WHERE patient_id=?")) {
            p.setLong(1, id);
            try (var rows = p.executeQuery()) {
                return rows.next() ? AuditDocuments.parse(rows.getString(1)) : AuditDocuments.JSON.nullNode();
            }
        }
    }

    private static JsonNode summary(Connection c, long id) throws SQLException {
        var row = profile(c, id);
        return row.isNull() ? row : AuditDocuments.JSON.createObjectNode().put("patientId", Long.toString(id))
                .put("displayName", row.path("patient_name").asText());
    }
}
