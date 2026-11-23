package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static cn.adc.shared.audit.AuditDocuments.parse;

/** op015. Listing formal bookings requires a current patient binding or guardian grant. */
@RestController
@ConditionalOnProperty(name = "adc.mini.appointment-read-enabled", havingValue = "true")
@RequestMapping("/api/v1/mini")
final class MiniAppointmentReadController {
    interface PatientScope {
        boolean mayRead(Connection c, String account, long patient) throws SQLException;

        default List<Authorization> authorizations(Connection c, String account) throws SQLException {
            throw new SQLException("MINI_AUTHORIZATION_DIRECTORY_UNAVAILABLE");
        }
    }

    record Authorization(String id, long patientId, Kind kind, List<String> permissions, int version) {
        enum Kind { SELF, GUARDIAN }
    }

    static final String VISIT_HISTORY_READ = "VISIT_HISTORY_READ";
    @org.springframework.beans.factory.annotation.Value("${adc.course.inventory-detail-enabled:false}")
    private boolean visitDetailsEnabled;
    @org.springframework.beans.factory.annotation.Value("${adc.course.quarterly-usability-enabled:false}")
    private boolean patientNotesEnabled;
    private final MiniRequestSession sessions;
    private final JdbcTransactions transactions;
    private final PatientScope scope;
    private final AppointmentRepository appointments = new AppointmentRepository();

    MiniAppointmentReadController(MiniRequestSession sessions,
                                  @org.springframework.beans.factory.annotation.Qualifier("miniReadTransactions") JdbcTransactions transactions,
                                  PatientScope scope) {
        this.sessions = sessions;
        this.transactions = transactions;
        this.scope = scope;
    }

    @GetMapping("/authorizations")
    ResponseEntity<JsonNode> authorizations(
            @RequestHeader(value = "Authorization", required = false) String bearer) {
        String account = sessions.account(bearer);
        return response(200, transactions.execute(c -> {
            var result = JSON.createObjectNode();
            var items = result.putArray("items");
            for (var grant : scope.authorizations(c, account)) {
                if (!scope.mayRead(c, account, grant.patientId())) continue;
                var item = items.addObject().put("authorizationId", grant.id())
                        .put("patientId", Long.toString(grant.patientId())).put("kind", grant.kind().name())
                        .put("status", "ACTIVE").put("version", grant.version());
                var permissions = item.putArray("permissions");
                grant.permissions().forEach(permissions::add);
            }
            if (!account.equals(sessions.account(bearer))) {
                throw new SecurityException("MINI_APPOINTMENT_ACCESS_DENIED");
            }
            return result;
        }));
    }

    @GetMapping("/appointments")
    ResponseEntity<JsonNode> list(@RequestHeader(value = "Authorization", required = false) String bearer,
                                  @RequestParam String patientId, @RequestParam(required = false) String cursor,
                                  @RequestParam(defaultValue = "25") int limit) {
        String account = sessions.account(bearer);
        long patient = positiveId(patientId);
        long after = cursor == null ? 0 : positiveId(cursor);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("INVALID_LIMIT");
        return response(200, transactions.execute(c -> {
            requireScope(c, bearer, account, patient);
            var body = JSON.createObjectNode();
            var items = body.putArray("items");
            try (var p = c.prepareStatement("""
                    SELECT a.appointment_id, v.clinic_id, v.dentist_id, a.schedule_id, a.schedule_version_no
                    FROM adc.appointment a
                    JOIN adc.reception_schedule_version v
                      ON (v.schedule_id,v.schedule_version_no)=(a.schedule_id,a.schedule_version_no)
                    WHERE a.patient_id=? AND a.appointment_id>? ORDER BY a.appointment_id LIMIT ?
                    """)) {
                p.setLong(1, patient);
                p.setLong(2, after);
                p.setInt(3, limit + 1);
                try (var rows = p.executeQuery()) {
                    long last = after;
                    while (rows.next()) {
                        if (items.size() == limit) {
                            body.put("nextCursor", Long.toString(last));
                            break;
                        }
                        long id = rows.getLong(1);
                        long clinic = rows.getLong(2);
                        var appointment = appointments.findAppointment(c, id, clinic)
                                .filter(row -> row.patientId() == patient)
                                .orElseThrow(() -> new SQLException("MINI_APPOINTMENT_READ_STALE", "40001"));
                        var version = appointment.terms().schedule();
                        if (version.scheduleId() != rows.getLong(4) || version.versionNo() != rows.getInt(5)) {
                            throw new SQLException("MINI_APPOINTMENT_READ_STALE", "40001");
                        }
                        items.addObject().put("appointmentId", Long.toString(id)).put("patientId", patientId)
                                .put("clinicId", Long.toString(clinic)).put("dentistId", Long.toString(rows.getLong(3)))
                                .put("startsAt", appointment.terms().startsAt().toString())
                                .put("endsAt", appointment.terms().endsAt().toString())
                                .put("formalStatus", appointment.status())
                                .put("factVerification", matchesCurrentBooking(c, clinic, appointment)
                                        ? "VERIFIED" : "PENDING_VERIFICATION");
                        last = id;
                    }
                }
            }
            requireScope(c, bearer, account, patient);
            return body;
        }));
    }

    private void requireScope(Connection c, String bearer, String account, long patient) throws SQLException {
        if (!account.equals(sessions.account(bearer)) || !scope.mayRead(c, account, patient)) {
            throw new SecurityException("MINI_APPOINTMENT_ACCESS_DENIED");
        }
    }

    private static boolean matchesCurrentBooking(Connection c, long clinic, AppointmentRepository.Appointment appointment)
            throws SQLException {
        String ref;
        try {
            if ("已取消".equals(appointment.status())) {
                ref = AppointmentCancellationService.cancellationReference(appointment).orElse("");
            } else if ("待到诊".equals(appointment.status())) {
                ref = parse(appointment.acceptance()).path("requestReference").asText();
            } else return false;
        }
        catch (IllegalArgumentException opaque) { return false; }
        if (ref.isBlank()) return false;
        try (var p = c.prepareStatement("""
                SELECT evidence, audit_event_id, uc_code FROM adc.operation_audit_event
                WHERE request_reference=? AND outcome='SUCCESS'
                  AND ((uc_code='UC02' AND operation_name='APPOINTMENT_CREATE')
                    OR (uc_code='UC03' AND operation_name='APPOINTMENT_CHANGE')
                    OR (uc_code='UC04' AND operation_name='APPOINTMENT_CANCEL'))
                """)) {
            p.setString(1, ref);
            try (var rows = p.executeQuery()) {
                if (!rows.next()) return false;
                var evidence = parse(rows.getString(1));
                long eventId = rows.getLong(2);
                var useCase = rows.getString(3);
                var saved = evidence.path("businessResultSnapshot");
                if (rows.next() || !AppointmentResolutionEvidence.isCommittedResult(evidence)
                        || !Long.toString(appointment.id()).equals(saved.path("after").path("appointmentId").asText())
                        || !saved.path("after").equals(saved.path("businessReference"))) return false;
                if ("UC03".equals(useCase)) {
                    long changeId = positiveId(saved.path("after").path("appointmentChangeId").asText());
                    return new AppointmentResolutionEvidence().confirms(c, clinic, appointment.id(),
                            AppointmentReadService.token(appointment), eventId, useCase, changeId, null);
                }
                if ("UC04".equals(useCase)) {
                    return new AppointmentResolutionEvidence().confirms(c, clinic, appointment.id(),
                            AppointmentReadService.token(appointment), eventId, useCase, null, ref);
                }
                return ref.equals(saved.path("verification").path("requestReference").asText())
                        && FormalBookingService.fingerprint(appointment)
                        .equals(saved.path("verification").path("creationFingerprint").asText());
            } catch (IllegalArgumentException invalidAudit) { return false; }
        }
    }

    @GetMapping("/visit-history")
    ResponseEntity<JsonNode> visitHistory(@RequestHeader(value = "Authorization", required = false) String bearer,
                                          @RequestParam String patientId,
                                          @RequestParam(required = false) String cursor) {
        String account = sessions.account(bearer);
        long patient = positiveId(patientId);
        long before = cursor == null ? 0 : positiveId(cursor);
        return response(200, transactions.execute(c -> {
            requireVisitHistoryScope(c, bearer, account, patient);
            var body = JSON.createObjectNode();
            var items = body.putArray("items");
            try (var query = c.prepareStatement("""
                    SELECT v.visit_id, v.registered_at, v.clinic_id, projects.names
                    FROM adc.visit v
                    JOIN adc.appointment a ON a.appointment_id=v.appointment_id
                    CROSS JOIN LATERAL (
                        SELECT json_agg(rv.performed_treatment ORDER BY r.clinical_record_id)::text AS names
                        FROM adc.clinical_record r
                        JOIN adc.clinical_record_version rv USING (clinical_record_id)
                        WHERE r.visit_id=v.visit_id AND rv.is_current AND rv.implementation_result='已实施'
                    ) projects
                    WHERE a.patient_id=? AND a.appointment_status='已到诊'
                      AND (?=0 OR v.visit_id<?)
                    ORDER BY v.visit_id DESC LIMIT 26
                    """)) {
                query.setLong(1, patient);
                query.setLong(2, before);
                query.setLong(3, before);
                try (var rows = query.executeQuery()) {
                    String last = null;
                    while (rows.next()) {
                        if (items.size() == 25) {
                            body.put("nextCursor", last);
                            break;
                        }
                        last = Long.toString(rows.getLong(1));
                        var item = items.addObject().put("visitId", last).put("patientId", patientId)
                                .put("registeredAt", rows.getObject(2, OffsetDateTime.class)
                                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                                .put("clinicId", Long.toString(rows.getLong(3)));
                        String projects = rows.getString(4);
                        item.set("performedProjects", projects == null ? JSON.createArrayNode() : parse(projects));
                    }
                }
            }
            requireVisitHistoryScope(c, bearer, account, patient);
            return body;
        }));
    }

    @GetMapping("/visit-history/{visitId}")
    ResponseEntity<JsonNode> visitDetail(@RequestHeader(value="Authorization",required=false) String bearer,
                                        @PathVariable String visitId,@RequestParam String patientId){
        if(!visitDetailsEnabled)return error(404,"NOT_FOUND");
        String account=sessions.account(bearer);long patient=positiveId(patientId),visit=positiveId(visitId);
        return response(200,transactions.execute(c->{
            requireVisitHistoryScope(c,bearer,account,patient);
            var body=MiniVisitDetail.read(c,patient,visit,patientNotesEnabled);
            requireVisitHistoryScope(c,bearer,account,patient);
            return body;
        }));
    }

    private void requireVisitHistoryScope(Connection c, String bearer, String account, long patient) throws SQLException {
        requireScope(c, bearer, account, patient);
        if (scope.authorizations(c, account).stream().noneMatch(grant -> grant.patientId() == patient
                && grant.permissions().contains(VISIT_HISTORY_READ))) {
            throw new SecurityException("MINI_VISIT_HISTORY_ACCESS_DENIED");
        }
    }

    private static long positiveId(String value) {
        long id = Long.parseLong(value);
        if (id <= 0 || !Long.toString(id).equals(value)) throw new IllegalArgumentException("INVALID_ID");
        return id;
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> denied(SecurityException denied) {
        boolean missing = "MINI_SESSION_REQUIRED".equals(denied.getMessage());
        return error(missing ? 401 : 403, missing ? "MINI_SESSION_REQUIRED" : "FORBIDDEN");
    }

    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<JsonNode> invalid() { return error(400, "INVALID_INPUT"); }

    @ExceptionHandler(JdbcTransactions.Failure.class)
    ResponseEntity<JsonNode> unavailable() { return error(503, "DEPENDENCY_UNAVAILABLE"); }

    private static ResponseEntity<JsonNode> error(int status, String code) {
        return response(status, JSON.createObjectNode().put("code", code).put("message", "暂时无法读取预约"));
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
