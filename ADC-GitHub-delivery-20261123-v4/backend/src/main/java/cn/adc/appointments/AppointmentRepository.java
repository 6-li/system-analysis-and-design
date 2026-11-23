package cn.adc.appointments;

import cn.adc.shared.resource.ResourceLockCoordinator.Version;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Internal mappings only. Callers own authorization, lock order, verified outcomes and transaction completion. */
final class AppointmentRepository {
    private static final String APPOINTMENT_FIELDS = """
            appointment_id, patient_id, schedule_id, schedule_version_no, starts_at, ends_at, treatment_request,
            manual_confirmation, appointment_status, no_show_verification, reminder_arrangement,
            cancellation_verification, operated_by, operated_at, representative_verification_basis, arrival_pending_notes
            """;
    private static final String IN_ORIGIN_CLINIC = """
            FROM adc.appointment a WHERE a.appointment_id=?
            AND EXISTS (SELECT 1 FROM adc.reception_schedule_version v
                WHERE (v.schedule_id,v.schedule_version_no)=(a.schedule_id,a.schedule_version_no) AND v.clinic_id=?)
            """;
    private static final String VISIT_FIELDS = """
            visit_id, appointment_id, clinic_id, actual_dentist_id, registered_at, arrival_verification,
            registered_by, arrived_at
            """;
    private static final String CHANGE_FIELDS = """
            appointment_change_id, appointment_id, old_schedule_id, old_schedule_version_no,
            new_schedule_id, new_schedule_version_no, old_starts_at, old_ends_at, new_starts_at, new_ends_at,
            old_treatment_request, new_treatment_request, manual_confirmation_basis, change_basis, verification_basis,
            old_reminder_basis, new_reminder_basis, changed_by, changed_at, change_request_reference
            """;

    record Terms(Version schedule, Instant startsAt, Instant endsAt, String treatmentRequest, String reminder) {}
    record Appointment(long id, long patientId, Terms terms, String acceptance, String status,
                       String noShow, String cancellation, String actor, Instant operatedAt,
                       String representative, String pendingArrival) {}
    record Visit(long id, long appointmentId, long clinicId, long dentistId, Instant registeredAt,
                 String verification, String actor, Instant arrivedAt) {}
    record ChangeEvidence(long id, String acceptance, String reason, String verification,
                          String actor, Instant changedAt, String requestReference) {}
    record Change(long appointmentId, Terms before, Terms after, ChangeEvidence evidence) {}

    /** Minimal projection for a caller that already verified the supplied clinic scope. */
    Optional<Version> findOrigin(Connection c, long id, long clinic) throws SQLException {
        try (var p = c.prepareStatement("SELECT schedule_id, schedule_version_no " + IN_ORIGIN_CLINIC)) {
            bindPair(p, id, clinic);
            try (var r = p.executeQuery()) {
                return r.next() ? Optional.of(version(r, 1)) : Optional.empty();
            }
        }
    }

    Optional<Appointment> findAppointment(Connection c, long id, long clinic) throws SQLException {
        try (var p = c.prepareStatement("SELECT " + APPOINTMENT_FIELDS + IN_ORIGIN_CLINIC)) {
            bindPair(p, id, clinic);
            try (var r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(appointment(r));
            }
        }
    }

    record SupportCandidate(Version version, boolean sameResource, boolean covers,
                            boolean scheduleConflict, boolean bookingConflict, boolean uncertainBooking) {}
    record ReadSnapshot(Appointment appointment, List<SupportCandidate> candidates) {}

    /** One statement keeps the original row and derived support on the same READ COMMITTED snapshot. */
    Optional<ReadSnapshot> readSnapshot(Connection c, long id, long clinic) throws SQLException {
        try (var p = c.prepareStatement("""
                SELECT %s, candidate.version, candidate.same_resource, candidate.covers,
                       candidate.schedule_conflict, candidate.booking_conflict, candidate.uncertain_booking
                FROM adc.appointment a
                JOIN adc.reception_schedule_version origin
                  ON (origin.schedule_id,origin.schedule_version_no)=(a.schedule_id,a.schedule_version_no)
                LEFT JOIN LATERAL (
                  SELECT v.schedule_version_no AS version,
                    (v.dentist_id=origin.dentist_id AND v.clinic_id=origin.clinic_id) AS same_resource,
                    (v.starts_at<=a.starts_at AND a.ends_at<=v.ends_at) AS covers,
                    EXISTS (
                      SELECT 1 FROM adc.reception_schedule_version other
                      WHERE other.activation_status='EFFECTIVE' AND other.schedule_id<>v.schedule_id
                        AND other.dentist_id=origin.dentist_id
                        AND other.starts_at<a.ends_at AND a.starts_at<other.ends_at
                    ) AS schedule_conflict,
                    EXISTS (
                      SELECT 1 FROM adc.appointment b JOIN adc.reception_schedule_version bv
                        ON (bv.schedule_id,bv.schedule_version_no)=(b.schedule_id,b.schedule_version_no)
                      WHERE b.appointment_id<>a.appointment_id AND bv.dentist_id=origin.dentist_id
                        AND b.starts_at<a.ends_at AND a.starts_at<b.ends_at
                        AND b.appointment_status IN ('待到诊','待核实','已到诊')
                    ) AS booking_conflict,
                    EXISTS (
                      SELECT 1 FROM adc.appointment b JOIN adc.reception_schedule_version bv
                        ON (bv.schedule_id,bv.schedule_version_no)=(b.schedule_id,b.schedule_version_no)
                      WHERE b.appointment_id<>a.appointment_id AND bv.dentist_id=origin.dentist_id
                        AND b.starts_at<a.ends_at AND a.starts_at<b.ends_at
                        AND b.appointment_status NOT IN ('待到诊','待核实','已到诊')
                    ) AS uncertain_booking
                  FROM adc.reception_schedule_version v
                  WHERE v.schedule_id=origin.schedule_id AND v.activation_status='EFFECTIVE'
                ) candidate ON true
                WHERE a.appointment_id=? AND origin.clinic_id=?
                """.formatted("a." + APPOINTMENT_FIELDS.strip().replaceAll(",\s*", ", a.")))) {
            bindPair(p, id, clinic);
            try (var r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                var a = appointment(r);
                var candidates = new ArrayList<SupportCandidate>();
                do {
                    var version = r.getObject(17, Integer.class);
                    if (version != null) candidates.add(new SupportCandidate(
                            new Version(a.terms().schedule().scheduleId(), version),
                            r.getBoolean(18), r.getBoolean(19), r.getBoolean(20), r.getBoolean(21), r.getBoolean(22)));
                } while (r.next());
                return Optional.of(new ReadSnapshot(a, List.copyOf(candidates)));
            }
        }
    }

    private static Appointment appointment(ResultSet r) throws SQLException {
        return new Appointment(r.getLong(1), r.getLong(2),
                new Terms(version(r, 3), instant(r, 5), instant(r, 6), r.getString(7), r.getString(11)),
                r.getString(8), r.getString(9), r.getString(10), r.getString(12), r.getString(13),
                instant(r, 14), r.getString(15), r.getString(16));
    }

    /** Internal fact lookup for an already scoped appointment, including contradictory visit facts. */
    Optional<Visit> findVisit(Connection c, Appointment appointment) throws SQLException {
        try (var p = c.prepareStatement("SELECT " + VISIT_FIELDS + "FROM adc.visit WHERE appointment_id=?")) {
            p.setLong(1, appointment.id());
            try (var r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new Visit(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4),
                        instant(r, 5), r.getString(6), r.getString(7), instant(r, 8)));
            }
        }
    }

    Optional<Change> findChange(Connection c, Appointment appointment, long changeId) throws SQLException {
        return findChange(c, appointment.id(), changeId);
    }

    Optional<Change> findChange(Connection c, long appointmentId, long changeId) throws SQLException {
        try (var p = c.prepareStatement("SELECT " + CHANGE_FIELDS
                + "FROM adc.appointment_change_history WHERE appointment_change_id=? AND appointment_id=?")) {
            p.setLong(1, changeId);
            p.setLong(2, appointmentId);
            try (var r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                var before = new Terms(version(r, 3), instant(r, 7), instant(r, 8), r.getString(11), r.getString(16));
                var after = new Terms(version(r, 5), instant(r, 9), instant(r, 10), r.getString(12), r.getString(17));
                var evidence = new ChangeEvidence(r.getLong(1), r.getString(13), r.getString(14), r.getString(15),
                        r.getString(18), instant(r, 19), r.getString(20));
                return Optional.of(new Change(r.getLong(2), before, after, evidence));
            }
        }
    }

    record Overlap(long appointmentId, long clinicId, String status, String basis,
                   Instant endsAt, boolean hasVisit) {}

    /** Call after acquiring L4 and L5. Scan all versions and clinics for the global dentist. */
    List<Overlap> overlaps(Connection c, long dentist, Instant start, Instant end) throws SQLException {
        try (var p = c.prepareStatement("""
                SELECT a.appointment_id, v.clinic_id, a.appointment_status,
                       CASE WHEN a.appointment_status='已取消' THEN a.cancellation_verification
                            WHEN a.appointment_status='已确认未到诊' THEN a.no_show_verification END,
                       a.ends_at, EXISTS (SELECT 1 FROM adc.visit f WHERE f.appointment_id=a.appointment_id)
                FROM adc.appointment a
                JOIN adc.reception_schedule_version v
                  ON (v.schedule_id,v.schedule_version_no)=(a.schedule_id,a.schedule_version_no)
                WHERE v.dentist_id=? AND a.starts_at < ? AND ? < a.ends_at
                """)) {
            p.setLong(1, dentist);
            time(p, 2, end);
            time(p, 3, start);
            var rows = new ArrayList<Overlap>();
            try (var r = p.executeQuery()) {
                while (r.next()) rows.add(new Overlap(r.getLong(1), r.getLong(2), r.getString(3),
                        r.getString(4), instant(r, 5), r.getBoolean(6)));
            }
            return rows;
        }
    }

    void insertAppointment(Connection c, Appointment a) throws SQLException {
        requireTransaction(c);
        requireAppointment(a);
        requireFrozen(a.terms());
        try (var p = c.prepareStatement("INSERT INTO adc.appointment (" + APPOINTMENT_FIELDS + ") "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            bindPair(p, a.id(), a.patientId());
            bindAppointment(p, 3, a);
            written(p);
        }
    }

    /** The caller must already hold the appointment lock and supply its locked snapshot. */
    boolean hasVisit(Connection c, Appointment appointment) throws SQLException {
        try(var p=c.prepareStatement("SELECT 1 FROM adc.visit WHERE appointment_id=?")) {
            p.setLong(1,appointment.id());
            try(var r=p.executeQuery()){return r.next();}
        }
    }

    void updateAppointment(Connection c, Appointment before, Appointment after) throws SQLException {
        requireTransaction(c);
        sameIdentity(before, after);
        requireAppointment(after);
        if (!samePlan(before.terms(), after.terms())) requireFrozen(after.terms());
        try (var p = c.prepareStatement("""
                UPDATE adc.appointment SET schedule_id=?, schedule_version_no=?, starts_at=?, ends_at=?,
                    treatment_request=?, manual_confirmation=?, appointment_status=?, no_show_verification=?,
                    reminder_arrangement=?, cancellation_verification=?, operated_by=?, operated_at=?,
                    representative_verification_basis=?, arrival_pending_notes=?
                WHERE appointment_id=? AND patient_id=?
                """)) {
            bindAppointment(p, 1, after);
            p.setLong(15, before.id());
            p.setLong(16, before.patientId());
            written(p);
        }
    }

    /** Append only for a caller-verified UC03 success in the same transaction as the appointment update. */
    void appendChange(Connection c, Appointment before, Appointment after, ChangeEvidence evidence) throws SQLException {
        requireTransaction(c);
        sameIdentity(before, after);
        requireTerms(before.terms());
        requireTerms(after.terms());
        requireFrozen(after.terms());
        requireText(evidence.acceptance(), "CHANGE_ACCEPTANCE");
        requireText(evidence.reason(), "CHANGE_REASON");
        requireText(evidence.actor(), "CHANGE_ACTOR");
        requireTime(evidence.changedAt());
        if (!evidence.acceptance().equals(after.acceptance())) throw new IllegalArgumentException("CHANGE_ACCEPTANCE_MISMATCH");
        if (after.representative() != null) requireText(evidence.verification(), "CHANGE_REPRESENTATIVE_EVIDENCE");
        var old = before.terms();
        var updated = after.terms();
        try (var p = c.prepareStatement("INSERT INTO adc.appointment_change_history (" + CHANGE_FIELDS + ") "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            bindPair(p, evidence.id(), before.id());
            bindVersion(p, 3, old.schedule());
            bindVersion(p, 5, updated.schedule());
            time(p, 7, old.startsAt());
            time(p, 8, old.endsAt());
            time(p, 9, updated.startsAt());
            time(p, 10, updated.endsAt());
            p.setString(11, old.treatmentRequest());
            p.setString(12, updated.treatmentRequest());
            p.setString(13, evidence.acceptance());
            p.setString(14, evidence.reason());
            p.setString(15, evidence.verification());
            p.setString(16, old.reminder());
            p.setString(17, updated.reminder());
            p.setString(18, evidence.actor());
            time(p, 19, evidence.changedAt());
            p.setString(20, evidence.requestReference());
            written(p);
        }
    }

    /** Caller supplies the confirmed appointment snapshot and independently verified arrival facts. */
    void insertVisit(Connection c, Appointment confirmed, Visit visit) throws SQLException {
        requireTransaction(c);
        if (visit.appointmentId() != confirmed.id() || !"已到诊".equals(confirmed.status())) {
            throw new IllegalArgumentException("VISIT_APPOINTMENT_MISMATCH");
        }
        requireTime(visit.registeredAt());
        if (visit.arrivedAt() != null) requireTime(visit.arrivedAt());
        requireText(visit.verification(), "ARRIVAL_VERIFICATION");
        requireText(visit.actor(), "VISIT_ACTOR");
        try (var p = c.prepareStatement("INSERT INTO adc.visit (" + VISIT_FIELDS + ") VALUES (?,?,?,?,?,?,?,?)")) {
            bindPair(p, visit.id(), visit.appointmentId());
            p.setLong(3, visit.clinicId());
            p.setLong(4, visit.dentistId());
            time(p, 5, visit.registeredAt());
            p.setString(6, visit.verification());
            p.setString(7, visit.actor());
            time(p, 8, visit.arrivedAt());
            written(p);
        }
    }

    private static void bindAppointment(PreparedStatement p, int n, Appointment a) throws SQLException {
        var terms = a.terms();
        bindVersion(p, n, terms.schedule());
        time(p, n + 2, terms.startsAt());
        time(p, n + 3, terms.endsAt());
        p.setString(n + 4, terms.treatmentRequest());
        p.setString(n + 5, a.acceptance());
        p.setString(n + 6, a.status());
        p.setString(n + 7, a.noShow());
        p.setString(n + 8, terms.reminder());
        p.setString(n + 9, a.cancellation());
        p.setString(n + 10, a.actor());
        time(p, n + 11, a.operatedAt());
        p.setString(n + 12, a.representative());
        p.setString(n + 13, a.pendingArrival());
    }

    private static void sameIdentity(Appointment before, Appointment after) {
        if (before.id() != after.id() || before.patientId() != after.patientId()) {
            throw new IllegalArgumentException("APPOINTMENT_IDENTITY_MISMATCH");
        }
    }

    private static boolean samePlan(Terms before, Terms after) {
        return before.schedule().equals(after.schedule()) && before.startsAt().equals(after.startsAt())
                && before.endsAt().equals(after.endsAt()) && before.treatmentRequest().equals(after.treatmentRequest());
    }

    private static void requireAppointment(Appointment a) {
        requireTerms(a.terms());
        requireText(a.acceptance(), "APPOINTMENT_ACCEPTANCE");
        requireText(a.status(), "APPOINTMENT_STATUS");
        requireText(a.actor(), "APPOINTMENT_ACTOR");
        requireTime(a.operatedAt());
    }

    private static void requireTerms(Terms terms) {
        if (terms.schedule() == null) throw new IllegalArgumentException("APPOINTMENT_VERSION_REQUIRED");
        requireTime(terms.startsAt());
        requireTime(terms.endsAt());
        if (!terms.startsAt().isBefore(terms.endsAt())) throw new IllegalArgumentException("INVALID_APPOINTMENT_WINDOW");
        if (terms.treatmentRequest() == null || terms.reminder() == null) {
            throw new IllegalArgumentException("APPOINTMENT_TERMS_REQUIRED");
        }
    }

    private static void requireFrozen(Terms terms) {
        AppointmentEvidenceCodec.forWindow(terms.treatmentRequest(), terms.startsAt(), terms.endsAt());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "_REQUIRED");
    }

    private static void requireTime(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) throw new IllegalArgumentException("APPOINTMENT_MICROSECOND_TIME_REQUIRED");
    }

    private static void requireTransaction(Connection c) throws SQLException {
        if (c.getAutoCommit()) throw new SQLException("APPOINTMENT_TRANSACTION_REQUIRED", "25000");
    }

    private static void written(PreparedStatement p) throws SQLException {
        if (p.executeUpdate() != 1) throw new SQLException("APPOINTMENT_WRITE_NOT_CONFIRMED", "P0001");
    }

    private static void bindPair(PreparedStatement p, long first, long second) throws SQLException {
        p.setLong(1, first);
        p.setLong(2, second);
    }

    private static Version version(ResultSet r, int n) throws SQLException {
        Long id = r.getObject(n, Long.class);
        Integer version = r.getObject(n + 1, Integer.class);
        if (id == null || version == null) throw new SQLException("INCOMPLETE_APPOINTMENT_VERSION", "22000");
        return new Version(id, version);
    }

    private static void bindVersion(PreparedStatement p, int n, Version version) throws SQLException {
        p.setLong(n, version.scheduleId());
        p.setInt(n + 1, version.versionNo());
    }

    private static Instant instant(ResultSet r, int n) throws SQLException {
        var value = r.getObject(n, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static void time(PreparedStatement p, int n, Instant value) throws SQLException {
        if (value == null) p.setNull(n, Types.TIMESTAMP_WITH_TIMEZONE);
        else p.setObject(n, value.atOffset(ZoneOffset.UTC));
    }
}
