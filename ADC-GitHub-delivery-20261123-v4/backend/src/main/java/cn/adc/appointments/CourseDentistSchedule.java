package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

import static cn.adc.shared.audit.AuditDocuments.JSON;

/** Course-only personal read projection. UC21/22 remain manager operations. */
@RestController
@Profile("course-demo")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${adc.course.scheduling-enabled:false} or ${adc.course.personal-schedule-read-enabled:false}")
final class CourseDentistSchedule {
    private final JdbcTransactions transactions;
    private final long clinic;
    private final long dentist;

    @Autowired CourseDentistSchedule(Environment env) {
        this(CourseAppointmentRead.courseTransactions(env, "adc_course_schedule_read",
                        "ADC_COURSE_DB_SCHEDULE_READ_PASSWORD", true),
                env.getRequiredProperty("adc.course.clinic-id", Long.class),
                env.getRequiredProperty("adc.course.dentist-id", Long.class));
    }

    CourseDentistSchedule(JdbcTransactions transactions, long clinic, long dentist) {
        if (clinic <= 0 || dentist <= 0) throw new IllegalArgumentException("COURSE_DENTIST_BINDING_REQUIRED");
        this.transactions = transactions;
        this.clinic = clinic;
        this.dentist = dentist;
    }

    ResponseEntity<JsonNode> read(HttpServletRequest request,Long cursor){return read(request,cursor,false);}
    @GetMapping("/api/v1/staff/my-schedule")
    ResponseEntity<JsonNode> read(HttpServletRequest request, @RequestParam(required = false) Long cursor, @RequestParam(defaultValue="false") boolean includeAppointments) {
        try {
            var identity = CourseAppointmentRead.identity(request);
            if (!identity.current().filter("SYNTHETIC dentist"::equals).isPresent()) {
                return error(identity.current().isEmpty() ? 401 : 403, "DENTIST_SESSION_REQUIRED");
            }
            if (cursor != null && cursor <= 0) return error(400, "INVALID_CURSOR");
            return response(200, transactions.execute(c -> {
                var body = JSON.createObjectNode().put("dentistId",Long.toString(dentist)).put("clinicId",Long.toString(clinic));
                var items = body.putArray("items");
                try (var query = c.prepareStatement("""
                        SELECT v.schedule_id, v.schedule_version_no, v.starts_at, v.ends_at,
                               (SELECT count(*) FROM adc.appointment a
                                WHERE a.schedule_id=v.schedule_id
                                  AND a.appointment_status IN ('待到诊','待核实','已到诊')
                                  AND a.starts_at>=v.starts_at AND a.ends_at<=v.ends_at) AS occupied
                        FROM adc.reception_schedule_version v
                        WHERE v.dentist_id=? AND v.clinic_id=? AND v.activation_status='EFFECTIVE'
                          AND v.schedule_id>? ORDER BY v.schedule_id LIMIT 26
                        """)) {
                    query.setLong(1, dentist);
                    query.setLong(2, clinic);
                    query.setLong(3, cursor == null ? 0 : cursor);
                    try (var rows = query.executeQuery()) {
                        String last = null;
                        while (rows.next()) {
                            if (items.size() == 25) { body.put("nextCursor", last); break; }
                            last = Long.toString(rows.getLong(1));
                            items.addObject().put("scheduleId", last).put("versionNo", rows.getInt(2))
                                    .put("startsAt", rows.getObject(3, OffsetDateTime.class).toString())
                                    .put("endsAt", rows.getObject(4, OffsetDateTime.class).toString())
                                    .put("clinicId", Long.toString(clinic)).put("appointmentCount", rows.getLong(5));
                        }
                    }
                }
                if(includeAppointments)for(var item:items){
                    var appointments=((tools.jackson.databind.node.ObjectNode)item).putArray("appointments");
                    try(var p=c.prepareStatement("""
                        SELECT a.appointment_id,a.starts_at,a.ends_at,a.appointment_status
                        FROM adc.appointment a JOIN adc.reception_schedule_version v ON a.schedule_id=v.schedule_id
                        WHERE v.schedule_id=? AND v.schedule_version_no=? AND v.clinic_id=? AND v.dentist_id=?
                          AND v.activation_status='EFFECTIVE' AND a.starts_at>=v.starts_at AND a.ends_at<=v.ends_at
                          AND a.appointment_status IN ('待到诊','待核实','已到诊') ORDER BY a.starts_at,a.appointment_id
                        """)){
                        p.setLong(1,Long.parseLong(item.path("scheduleId").asText()));p.setInt(2,item.path("versionNo").asInt());p.setLong(3,clinic);p.setLong(4,dentist);
                        try(var rows=p.executeQuery()){while(rows.next())appointments.addObject().put("appointmentId",Long.toString(rows.getLong(1))).put("startsAt",rows.getObject(2,OffsetDateTime.class).toString()).put("endsAt",rows.getObject(3,OffsetDateTime.class).toString()).put("statusLabel",rows.getString(4));}
                    }
                    ((tools.jackson.databind.node.ObjectNode)item).put("appointmentCount",appointments.size());
                }
                if (!identity.current().filter("SYNTHETIC dentist"::equals).isPresent()) {
                    throw new SecurityException("DENTIST_SESSION_REQUIRED");
                }
                return body;
            }));
        } catch (SecurityException denied) { return error(403, "DENTIST_SESSION_REQUIRED"); }
        catch (JdbcTransactions.Failure unavailable) { return error(503, "SCHEDULE_UNAVAILABLE"); }
    }

    private static ResponseEntity<JsonNode> error(int status, String code) {
        return response(status, JSON.createObjectNode().put("code", code).put("message", "接诊安排暂时无法读取"));
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
