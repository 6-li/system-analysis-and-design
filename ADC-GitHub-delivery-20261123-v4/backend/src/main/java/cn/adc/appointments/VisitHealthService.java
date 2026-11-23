package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore.EventIdAllocator;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

/** UC07. The staff adapter supplies clinic scope and an authenticated dentist identity. */
final class VisitHealthService implements CommandRecovery.SuccessVerifier {
    private static final String UC = "UC07";
    private static final String OPERATION = "HEALTH_CHANGE_SAVE";

    @FunctionalInterface
    interface DentistIdentity {
        long requireDentist(String actor, long clinic);
    }

    record Context(long clinic, long patient, long visit) {}
    record Snapshot(long id, String medicalHistory, String allergyInformation) {}
    record Update(long expectedSnapshotId, String medicalHistory, String allergyInformation, String changeContent) {}
    record Treatment(long visitId, long recordId, int version, String treatment, String result) {}
    record Details(Snapshot snapshot, List<Treatment> priorTreatments) {}

    private final JdbcTransactions transactions;
    private final StaffAuthorization authorization;
    private final DentistIdentity dentists;
    private final FormalBookingService.AppointmentIds ids;
    private final Clock clock;
    private final CommandRecovery commands;

    VisitHealthService(JdbcTransactions transactions, StaffAuthorization authorization, DentistIdentity dentists,
                       FormalBookingService.AppointmentIds ids, EventIdAllocator auditIds, Clock clock) {
        this.transactions = transactions;
        this.authorization = authorization;
        this.dentists = dentists;
        this.ids = ids;
        this.clock = clock;
        this.commands = new UcAuditService(transactions, authorization, this, clock, auditIds).commands();
    }

    Details read(Context context) {
        return transactions.execute(c -> {
            long dentist = requireVisit(c, context);
            var snapshot = current(c, context).orElse(null);
            var treatments = priorTreatments(c, context);
            if (requireVisit(c, context) != dentist) throw new SecurityException("HEALTH_SCOPE_CHANGED");
            return new Details(snapshot, treatments);
        });
    }

    private static List<Treatment> priorTreatments(Connection c, Context context) throws SQLException {
        try (var query = c.prepareStatement("""
                SELECT v.visit_id, r.clinical_record_id, rv.record_version_no,
                       rv.performed_treatment, rv.implementation_result
                FROM adc.visit v
                JOIN adc.appointment a ON a.appointment_id=v.appointment_id
                JOIN adc.visit current_visit ON current_visit.visit_id=?
                JOIN adc.clinical_record r ON r.visit_id=v.visit_id
                JOIN adc.clinical_record_version rv ON rv.clinical_record_id=r.clinical_record_id
                WHERE a.patient_id=? AND v.clinic_id=? AND a.appointment_status='已到诊'
                  AND v.registered_at<current_visit.registered_at AND rv.is_current
                ORDER BY v.registered_at DESC, v.visit_id, r.clinical_record_id
                """)) {
            query.setLong(1, context.visit());
            query.setLong(2, context.patient());
            query.setLong(3, context.clinic());
            try (var rows = query.executeQuery()) {
                var treatments = new ArrayList<Treatment>();
                while (rows.next()) {
                    treatments.add(new Treatment(rows.getLong(1), rows.getLong(2), rows.getInt(3),
                            rows.getString(4), rows.getString(5)));
                }
                return List.copyOf(treatments);
            }
        }
    }

    CommandRecovery.Receipt save(Context context, UUID key, Update update) {
        if (update.expectedSnapshotId() < 0 || update.changeContent() == null || update.changeContent().isBlank()
                || blank(update.medicalHistory()) || blank(update.allergyInformation())) {
            throw new Rejection("INVALID_HEALTH_CHANGE");
        }
        var payload = JSON.createObjectNode().put("patientId", context.patient()).put("visitId", context.visit())
                .put("expectedSnapshotId", update.expectedSnapshotId()).put("medicalHistory", update.medicalHistory())
                .put("allergyInformation", update.allergyInformation()).put("changeContent", update.changeContent());
        var command = new CommandRecovery.Command(OPERATION, UC, key, payload, Set.of(context.clinic()));
        return commands.execute(command, (c, locks) -> append(c, context, update));
    }

    private static boolean blank(String value) { return value != null && value.isBlank(); }

    AuditDocuments.Change append(Connection c, Context context, Update update) throws SQLException {
        long dentist = requireVisit(c, context);
        // Serialize health snapshots for the patient, including changes made from different visits.
        try (var lock = c.prepareStatement("SELECT pg_advisory_xact_lock(41007, ?)")) {
            lock.setInt(1, (int) ResourceLockCoordinator.key("patient-health:" + context.patient()));
            lock.execute();
        }
        if (requireVisit(c, context) != dentist) throw new SecurityException("HEALTH_SCOPE_CHANGED");
        var prior = current(c, context).orElse(new Snapshot(0, null, null));
        if (prior.id() != update.expectedSnapshotId()) throw new Rejection("HEALTH_SNAPSHOT_STALE");
        if (java.util.Objects.equals(prior.medicalHistory(), update.medicalHistory())
                && java.util.Objects.equals(prior.allergyInformation(), update.allergyInformation())) {
            throw new Rejection("HEALTH_CHANGE_NOT_REQUIRED");
        }
        long id = ids.next(c);
        if (id <= 0) throw new SQLException("HEALTH_CHANGE_ID_UNAVAILABLE", "22003");
        try (var insert = c.prepareStatement("""
                INSERT INTO adc.patient_health_change
                    (health_change_id, visit_id, medical_history, allergy_information,
                     change_content, verified_dentist_id, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setLong(1, id);
            insert.setLong(2, context.visit());
            insert.setString(3, update.medicalHistory());
            insert.setString(4, update.allergyInformation());
            insert.setString(5, update.changeContent());
            insert.setLong(6, dentist);
            insert.setObject(7, StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC));
            if (insert.executeUpdate() != 1) throw new SQLException("HEALTH_CHANGE_WRITE_UNCONFIRMED", "P0001");
        }
        var result = JSON.createObjectNode().put("healthChangeId", id).put("visitId", context.visit());
        var verification = JSON.createObjectNode().put("snapshotHash",
                fingerprint(update.medicalHistory(), update.allergyInformation(), update.changeContent(), dentist));
        return new AuditDocuments.Change(result, null, result, verification);
    }

    private long requireVisit(Connection c, Context context) throws SQLException {
        return TreatingDentistAccess.require(c, authorization, dentists, UC, context.clinic(), context.patient(), context.visit());
    }

    private static Optional<Snapshot> current(Connection c, Context context) throws SQLException {
        try (var query = c.prepareStatement("""
                SELECT h.health_change_id, h.medical_history, h.allergy_information
                FROM adc.patient_health_change h JOIN adc.visit v ON v.visit_id=h.visit_id
                JOIN adc.appointment a ON a.appointment_id=v.appointment_id
                WHERE a.patient_id=? AND v.clinic_id=?
                ORDER BY h.changed_at DESC, h.health_change_id DESC LIMIT 1
                """)) {
            query.setLong(1, context.patient());
            query.setLong(2, context.clinic());
            try (var row = query.executeQuery()) {
                return row.next() ? Optional.of(new Snapshot(row.getLong(1), row.getString(2), row.getString(3)))
                        : Optional.empty();
            }
        }
    }

    @Override
    public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
        if (!UC.equals(command.ucCode()) || !OPERATION.equals(command.operation()) || command.clinics().size() != 1) return false;
        var context = new Context(command.clinics().iterator().next(), command.payload().path("patientId").asLong(),
                command.payload().path("visitId").asLong());
        long dentist = requireVisit(c, context);
        var result = saved.path("after");
        if (!result.equals(saved.path("businessReference")) || result.path("visitId").asLong() != context.visit()) return false;
        try (var query = c.prepareStatement("""
                SELECT medical_history, allergy_information, change_content FROM adc.patient_health_change
                WHERE health_change_id=? AND visit_id=? AND verified_dentist_id=?
                """)) {
            query.setLong(1, result.path("healthChangeId").asLong());
            query.setLong(2, context.visit());
            query.setLong(3, dentist);
            try (var row = query.executeQuery()) {
                return row.next() && fingerprint(row.getString(1), row.getString(2), row.getString(3), dentist)
                        .equals(saved.path("verification").path("snapshotHash").asText());
            }
        }
    }

    private static String fingerprint(String history, String allergies, String changeContent, long dentist) {
        return AuditDocuments.hash(JSON.createArrayNode().add(history).add(allergies).add(changeContent).add(dentist));
    }

    @Override
    public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        return true;
    }
}
