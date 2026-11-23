package cn.adc.scheduling;

import cn.adc.appointments.AppointmentResolutionEvidence;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Evaluates replacement coverage without rewriting the appointment's historical reference. */
final class ImpactAssessmentService {
    record Window(Instant start, Instant end) {
        Window {
            if (!start.isBefore(end)) throw new IllegalArgumentException("INVALID_WINDOW");
        }

        boolean covers(Window other) {
            return !other.start.isBefore(start) && !other.end.isAfter(end);
        }

        boolean overlaps(Window other) {
            return start.isBefore(other.end) && other.start.isBefore(end);
        }
    }

    record Schedule(long id, int version, long clinic, long dentist, Window window) {}
    record Appointment(long id, Schedule historical, Window window) {}
    enum Assessment { UNAFFECTED, SUPPORTED, REQUIRES_PROCESSING }

    record Impact(long appointmentId, long historicalScheduleId, int historicalVersion, Assessment assessment) {}
    record ObservedImpact(Impact impact, String appointmentToken) {}
    record Preview(Schedule target, List<ObservedImpact> items, boolean hiddenConflict) {}

    Preview preview(JdbcTransactions transactions, StaffAuthorization authorization, Long requestedClinic,
                    long schedule, int version) {
        return transactions.execute(c -> {
            new ResourceLockCoordinator().scope(c).schedules(List.of(schedule));
            var store = new JdbcScheduleStore();
            var target = store.versions(c, schedule).stream().filter(row -> row.versionNo() == version)
                    .findFirst().orElseThrow(() -> new java.util.NoSuchElementException("SCHEDULE_NOT_FOUND"));
            long clinic = target.clinicId();
            if (requestedClinic != null && requestedClinic != clinic) throw new SecurityException("IMPACT_TARGET_OUT_OF_SCOPE");
            var scope = Set.of(clinic);
            var actor = authorization.require("UC22", scope).actor();
            if (!"DRAFT".equals(target.status())) throw new IllegalArgumentException("IMPACT_REQUIRES_DRAFT");
            var replacement = schedule(target);
            var effective = store.overlaps(c, target.dentistId(),
                    new ScheduleStore.Window(target.startsAt(), target.endsAt())).stream()
                    .filter(row -> "EFFECTIVE".equals(row.status())).map(ImpactAssessmentService::schedule).toList();
            var items = new ArrayList<ObservedImpact>();
            var evidence = new AppointmentResolutionEvidence();
            for (var item : forAuthorizedClinic(c, clinic, replacement, effective)) {
                var token = evidence.currentToken(c, clinic, item.appointmentId())
                        .orElseThrow(() -> new IllegalArgumentException("IMPACT_CHANGED_DURING_READ"));
                items.add(new ObservedImpact(item, token));
            }
            boolean hidden = effective.stream().anyMatch(row -> row.id() != schedule && row.clinic() != clinic);
            if (!actor.equals(authorization.require("UC22", scope).actor())) {
                throw new SecurityException("IMPACT_ACTOR_CHANGED");
            }
            return new Preview(replacement, List.copyOf(items), hidden);
        });
    }

    static Schedule schedule(ScheduleStateToken.Version row) {
        return new Schedule(row.scheduleId(), row.versionNo(), row.clinicId(), row.dentistId(),
                new Window(row.startsAt(), row.endsAt()));
    }

    /** Caller validates the candidate and the historical clinic before using this projection. */
    List<Impact> forAuthorizedClinic(Connection connection, long authorizedClinic, Schedule replacement,
                                     List<Schedule> effective) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT a.appointment_id, v.schedule_id, v.schedule_version_no, v.clinic_id, v.dentist_id,
                       v.starts_at, v.ends_at, a.starts_at, a.ends_at
                FROM adc.appointment a
                JOIN adc.reception_schedule_version v
                  ON (a.schedule_id,a.schedule_version_no)=(v.schedule_id,v.schedule_version_no)
                WHERE v.schedule_id=? AND v.clinic_id=?
                ORDER BY a.appointment_id
                """)) {
            query.setLong(1, replacement.id());
            query.setLong(2, authorizedClinic);
            var results = new ArrayList<Impact>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var historical = new Schedule(rows.getLong(2), rows.getInt(3), rows.getLong(4), rows.getLong(5),
                            new Window(instant(rows, 6), instant(rows, 7)));
                    var appointment = new Appointment(rows.getLong(1), historical,
                            new Window(instant(rows, 8), instant(rows, 9)));
                    results.add(new Impact(appointment.id(), historical.id(), historical.version(),
                            assess(appointment, replacement, effective)));
                }
            }
            return List.copyOf(results);
        }
    }

    private static Instant instant(java.sql.ResultSet rows, int index) throws SQLException {
        return rows.getObject(index, OffsetDateTime.class).toInstant();
    }

    Assessment assess(Appointment appointment, Schedule replacement, List<Schedule> effective) {
        var historical = appointment.historical();
        if (historical.id() != replacement.id()) return Assessment.UNAFFECTED;
        if (historical.clinic() != replacement.clinic() || historical.dentist() != replacement.dentist()
                || !replacement.window().covers(appointment.window())) return Assessment.REQUIRES_PROCESSING;
        return effective.stream().anyMatch(other -> other.id() != replacement.id()
                && other.dentist() == replacement.dentist() && other.window().overlaps(appointment.window()))
                ? Assessment.REQUIRES_PROCESSING : Assessment.SUPPORTED;
    }
}
