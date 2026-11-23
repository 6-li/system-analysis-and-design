package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import static cn.adc.shared.audit.AuditDocuments.JSON;

@RestController
@Profile("course-demo")
@ConditionalOnProperty(name = "adc.mini.preliminary-intake-enabled",
        havingValue = "true")
final class CourseMiniClinicDirectory {
    private final MiniRequestSession sessions;
    private final JdbcTransactions transactions;

    CourseMiniClinicDirectory(MiniRequestSession sessions,
                              @Qualifier("courseMiniTransactions") JdbcTransactions transactions) {
        this.sessions = sessions;
        this.transactions = transactions;
    }

    @GetMapping("/api/v1/mini/booking/clinics")
    ResponseEntity<JsonNode> clinics(@RequestHeader(value = "Authorization", required = false) String bearer,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) String region) {
        try {
            String account = sessions.account(bearer);
            if (region != null) return error(422, "REGION_FILTER_UNAVAILABLE");
            long after = cursor == null ? 0 : Long.parseLong(cursor);
            if (cursor != null && (after <= 0 || !Long.toString(after).equals(cursor))) {
                return error(400, "INVALID_CURSOR");
            }
            return response(200, transactions.execute(c -> {
                var body = JSON.createObjectNode();
                var items = body.putArray("items");
                try (var query = c.prepareStatement("""
                        SELECT clinic_id, clinic_name FROM adc.clinic
                        WHERE clinic_id>? ORDER BY clinic_id LIMIT 26
                        """)) {
                    query.setLong(1, after);
                    try (var rows = query.executeQuery()) {
                        String last = null;
                        while (rows.next()) {
                            if (items.size() == 25) {
                                body.put("nextCursor", last);
                                break;
                            }
                            last = Long.toString(rows.getLong(1));
                            items.addObject().put("clinicId", last).put("clinicName", rows.getString(2));
                        }
                    }
                }
                if (!account.equals(sessions.account(bearer))) throw new SecurityException("MINI_SESSION_REQUIRED");
                return body;
            }));
        } catch (SecurityException denied) {
            return error(401, "MINI_SESSION_REQUIRED");
        } catch (IllegalArgumentException invalid) {
            return error(400, "INVALID_CURSOR");
        } catch (JdbcTransactions.Failure unavailable) {
            return error(503, "DEPENDENCY_UNAVAILABLE");
        }
    }

    private static ResponseEntity<JsonNode> error(int status, String code) {
        return response(status, JSON.createObjectNode().put("code", code).put("message", "暂时无法读取诊所列表"));
    }

    private static ResponseEntity<JsonNode> response(int status, JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
