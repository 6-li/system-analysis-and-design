package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.List;
import static cn.adc.shared.audit.AuditDocuments.JSON;

/** Self archive read only: neither a login nor an intake assertion establishes a patient binding. */
@RestController
@ConditionalOnExpression("'${adc.mini.profile-read-enabled:false}' == 'true' || '${adc.course.patient-accounts-enabled:false}' == 'true'")
final class MiniPatientProfileController {
    static final String PERMISSION = "PATIENT_PROFILE_READ";
    private final MiniRequestSession sessions;
    private final JdbcTransactions transactions;
    private final MiniAppointmentReadController.PatientScope scope;

    MiniPatientProfileController(MiniRequestSession sessions,
            @org.springframework.beans.factory.annotation.Qualifier("miniReadTransactions") JdbcTransactions transactions,
            MiniAppointmentReadController.PatientScope scope) {
        this.sessions=sessions; this.transactions=transactions; this.scope=scope;
    }

    private List<MiniAppointmentReadController.Authorization> self(java.sql.Connection c, String account)
            throws java.sql.SQLException {
        return scope.authorizations(c, account).stream()
                .filter(g -> g.kind()==MiniAppointmentReadController.Authorization.Kind.SELF).toList();
    }

    @GetMapping("/api/v1/mini/me")
    ResponseEntity<JsonNode> read(@RequestHeader(value="Authorization",required=false) String bearer) {
        String account=sessions.account(bearer);
        return response(200,transactions.execute(c -> {
            var grants=self(c,account);
            var result=JSON.createObjectNode();
            if (grants.isEmpty()) result.put("status","UNLINKED");
            else {
                if (grants.stream().map(g -> g.patientId()).distinct().count()!=1)
                    throw new IllegalArgumentException("AMBIGUOUS_SELF_BINDING");
                var grant=grants.stream().filter(g -> g.permissions().contains(PERMISSION)).findFirst()
                        .orElseThrow(() -> new SecurityException("PROFILE_FORBIDDEN"));
                try (var p=c.prepareStatement("SELECT patient_id, patient_name, identity_information, contact_information FROM adc.patient WHERE patient_id=?")) {
                    p.setLong(1,grant.patientId());
                    try (var rows=p.executeQuery()) {
                        if (!rows.next()) throw new java.sql.SQLException("SELF_PROFILE_MISSING");
                        result.put("status","VERIFIED").put("patientId",Long.toString(rows.getLong(1)))
                                .put("patientName",rows.getString(2)).put("identityInformation",rows.getString(3))
                                .put("contactInformation",rows.getString(4));
                        if (rows.next()) throw new java.sql.SQLException("SELF_PROFILE_AMBIGUOUS");
                    }
                }
            }
            if (!account.equals(sessions.account(bearer)) || !grants.equals(self(c,account)))
                throw new SecurityException("PROFILE_FORBIDDEN");
            return result;
        }));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<JsonNode> denied(SecurityException e) {
        return response("MINI_SESSION_REQUIRED".equals(e.getMessage())?401:403,
                JSON.createObjectNode().put("code","PROFILE_ACCESS_DENIED"));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<JsonNode> ambiguous() { return response(409,JSON.createObjectNode().put("code","SELF_BINDING_CONFLICT")); }
    @ExceptionHandler(JdbcTransactions.Failure.class)
    ResponseEntity<JsonNode> unavailable() { return response(503,JSON.createObjectNode().put("code","PROFILE_UNAVAILABLE")); }
    private static ResponseEntity<JsonNode> response(int status,JsonNode body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
