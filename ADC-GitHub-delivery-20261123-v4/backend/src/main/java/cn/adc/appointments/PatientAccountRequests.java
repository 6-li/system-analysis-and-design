package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Server-owned request receipts. No shared synthetic history or client supplied owner. */
final class PatientAccountRequests {
    JsonNode list(Connection c,String account,String cursor) throws SQLException {
        if(JdbcPatientAccountScope.accountId(account)==null)throw new SecurityException("ACCOUNT_SESSION_REQUIRED");
        UUID after=null;
        if(cursor!=null) {after=UUID.fromString(cursor);if(!after.toString().equals(cursor))throw new IllegalArgumentException("INVALID_CURSOR");}
        var body=AuditDocuments.JSON.createObjectNode(); var items=body.putArray("items");
        try(var q=c.prepareStatement("""
            SELECT r.request_id,r.request_version,r.associated_patient_id,r.submitted_content::text
            FROM adc_mini.preliminary_request r
            JOIN adc_mini.patient_account a ON r.applicant_account=('ACCOUNT:' || a.account_id::text)
            WHERE r.applicant_account=? AND NOT a.disabled
              AND (CAST(? AS uuid) IS NULL OR r.request_id>?)
              AND EXISTS(SELECT 1 FROM adc_mini.operation_audit_event e WHERE e.request_id=r.request_id AND e.operation='op008')
            ORDER BY r.request_id LIMIT 26
            """)) {
            q.setString(1,account);q.setObject(2,after);q.setObject(3,after);
            try(var r=q.executeQuery()) {
                String last=null;
                while(r.next()) {
                    if(items.size()==25){body.put("nextCursor",last);break;}
                    UUID id=r.getObject(1,UUID.class);last=id.toString();
                    Long patient=r.getObject(3,Long.class);var input=AuditDocuments.parse(r.getString(4));
                    String type=input.path("type").asText();
                    String status=patient==null?"PENDING_VERIFICATION":"PENDING_BOOKING";
                    long clinic=0;
                    if("NEW".equals(type)) {
                        try{clinic=Long.parseLong(input.path("intendedClinicId").asText());}catch(NumberFormatException invalid){/* no invented success */}
                        if(clinic>0&&MiniRequestInboxService.completed(c,id,type,patient,input,clinic))status="BOOKED";
                    } else status="PENDING_PROCESSING";
                    if(!"NEW".equals(type)){
                        try(var query=c.prepareStatement("SELECT s.clinic_id FROM adc.appointment a JOIN adc.reception_schedule_version s ON (s.schedule_id,s.schedule_version_no)=(a.schedule_id,a.schedule_version_no) WHERE a.appointment_id::text=? AND a.patient_id=?")){
                            query.setString(1,input.path("originalAppointmentId").asText());query.setObject(2,patient,java.sql.Types.BIGINT);
                            try(var rows=query.executeQuery()){if(rows.next())clinic=rows.getLong(1);}
                        }
                        if(clinic>0&&MiniRequestInboxService.completed(c,id,type,patient,input,clinic))status="CHANGE".equals(type)?"CHANGED":"CANCELLED";
                    }
                    String rejection=RequestDecisions.reason(c,id);if(rejection!=null)status="REJECTED";
                    var item=items.addObject().put("requestId",last).put("requestVersion",r.getInt(2)).put("type",type)
                        .put("status",status).put("preferredDate",input.path("preferred").path("date").asText());
                    if(rejection!=null)item.put("reason",rejection);
                }
            }
        }
        return body;
    }
}
