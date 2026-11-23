package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.JsonNode;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import static cn.adc.shared.audit.AuditDocuments.JSON;

/** Read-only reception projection. Neither association nor an extension event alone proves booking success. */
public final class MiniRequestInboxService {
    private final JdbcTransactions tx;
    private final StaffAuthorization auth;
    private final long clinic;
    public MiniRequestInboxService(JdbcTransactions tx, StaffAuthorization auth, long clinic) {
        if (clinic <= 0) throw new IllegalArgumentException("INBOX_CLINIC_REQUIRED");
        this.tx=tx;this.auth=auth;this.clinic=clinic;
    }
    public JsonNode list(String cursor) { return query(cursor == null ? null : uuid(cursor),null); }
    public JsonNode selection(String id) {
        var result=query(null,uuid(id)).path("items");
        if(result.size()!=1)throw new IllegalArgumentException("MINI_REQUEST_UNAVAILABLE");
        return result.get(0);
    }
    public JsonNode selection(String id,String version){
        var row=selection(id);
        if(version==null||!Integer.toString(row.path("requestVersion").asInt()).equals(version))throw new IllegalArgumentException("MINI_REQUEST_VERSION_STALE");
        return row;
    }
    /** Original self-reported data is shown only on the scoped UC01 verification page, never the list. */
    public JsonNode verificationPreview(String id,String version){
        auth.require("UC01",Set.of(clinic));var selected=selection(id,version);
        if(!"NEW".equals(selected.path("type").asText())||selected.has("associatedPatientId"))throw new IllegalArgumentException("MINI_IDENTITY_ALREADY_ASSOCIATED");
        String actor=auth.require("UC01",Set.of(clinic)).actor();
        return tx.execute(c->{
            JsonNode identity;
            try(var p=c.prepareStatement("""
              SELECT submitted_content::text FROM adc_mini.preliminary_request WHERE request_id=? AND request_version=?
                AND associated_patient_id IS NULL AND submitted_content->>'type'='NEW' AND submitted_content->>'intendedClinicId'=?
              """)){
                p.setObject(1,uuid(id));p.setInt(2,selected.path("requestVersion").asInt());p.setString(3,Long.toString(clinic));
                try(var rows=p.executeQuery()){if(!rows.next())throw new IllegalArgumentException("MINI_SOURCE_STALE");identity=AuditDocuments.parse(rows.getString(1)).path("identity").deepCopy();}
            }
            if(!actor.equals(auth.require("UC01",Set.of(clinic)).actor()))throw new SecurityException("INBOX_ACTOR_CHANGED");
            return identity;
        });
    }
    /** Scoped UC02 page metadata only. Never returned by the masked inbox API. */
    public JsonNode bookingPreview(String id,String version){
        String actor=auth.require("UC02",Set.of(clinic)).actor();var selected=selection(id,version);
        if(!"NEW".equals(selected.path("type").asText())||!selected.has("associatedPatientId"))throw new IllegalArgumentException("BOOKING_ASSOCIATION_REQUIRED");
        return tx.execute(c->{
            JsonNode input;
            try(var p=c.prepareStatement("SELECT submitted_content::text FROM adc_mini.preliminary_request WHERE request_id=? AND request_version=? AND associated_patient_id=? AND submitted_content->>'type'='NEW' AND submitted_content->>'intendedClinicId'=?")){
                p.setObject(1,uuid(id));p.setInt(2,selected.path("requestVersion").asInt());p.setLong(3,Long.parseLong(selected.path("associatedPatientId").asText()));p.setString(4,Long.toString(clinic));
                try(var rows=p.executeQuery()){if(!rows.next())throw new IllegalArgumentException("BOOKING_SOURCE_STALE");input=AuditDocuments.parse(rows.getString(1));}
            }
            var preview=JSON.createObjectNode().put("requestId",id).put("patientId",selected.path("associatedPatientId").asText()).put("treatmentNeed",input.path("treatmentNeed").asText()).put("preferredDate",input.path("preferred").path("date").asText());
              if(input.has("selectedScheme")){MiniSelectedScheme.validate(input);preview.set("selectedScheme",input.path("selectedScheme").deepCopy());preview.put("patientConfirmed",true);}
            if(!actor.equals(auth.require("UC02",Set.of(clinic)).actor()))throw new SecurityException("INBOX_ACTOR_CHANGED");return preview;
        });
    }
    public JsonNode processingPreview(String id,String version){
        var row=selection(id,version);if("NEW".equals(row.path("type").asText()))return bookingPreview(id,version);
        auth.require("CHANGE".equals(row.path("type").asText())?"UC03":"UC04",Set.of(clinic));
        return tx.execute(c->{try(var q=c.prepareStatement("SELECT submitted_content::text FROM adc_mini.preliminary_request WHERE request_id=? AND request_version=?")){
            q.setObject(1,uuid(id));q.setInt(2,Integer.parseInt(version));try(var r=q.executeQuery()){
                if(!r.next())throw new IllegalArgumentException("REQUEST_STALE");var input=AuditDocuments.parse(r.getString(1));
                var result=JSON.createObjectNode().put("requestId",id).put("patientId",row.path("associatedPatientId").asText()).put("preferredDate",input.path("preferred").path("date").asText()).put("changeReason",input.path("changeReason").asText());
                if(input.has("selectedScheme")){MiniChangeSelection.validate(input);result.set("selectedScheme",input.path("selectedScheme"));result.put("patientConfirmed",true);}
                return result;
            }
        }});
    }
    /** Input must come from this service's selection(), never browser supplied source metadata. */
    public static void bindSelection(tools.jackson.databind.node.ObjectNode context,JsonNode row){
        String patient=row.path("associatedPatientId").asText();
        if(!patient.matches("[1-9][0-9]*"))throw new IllegalArgumentException("MINI_PATIENT_ASSOCIATION_REQUIRED");
        String type=row.path("type").asText();
        String field=switch(type){case "NEW"->"verifiedBookingSource";case "CHANGE"->"verifiedChangeSource";case "CANCEL"->"verifiedCancelSource";default->throw new IllegalArgumentException("MINI_SOURCE_TYPE_INVALID");};
        var source=context.putObject(field).put("reference",CourseMiniBookingContext.PREFIX+row.path("requestId").asText()+":"+row.path("requestVersion").asInt())
          .put("requestId",row.path("requestId").asText()).put("patientId",patient);
        if(!type.equals("NEW"))source.put("appointmentId",row.path("originalAppointmentId").asText());
    }
    private JsonNode query(UUID cursor, UUID selected) {
        var actions=new LinkedHashMap<String,String>();
        if(auth.evaluate("UC01",Set.of(clinic)).allowed())actions.put("NEW","UC01");
        else if(auth.evaluate("UC02",Set.of(clinic)).allowed())actions.put("NEW","UC02");
        if(auth.evaluate("UC03",Set.of(clinic)).allowed())actions.put("CHANGE","UC03");
        if(auth.evaluate("UC04",Set.of(clinic)).allowed())actions.put("CANCEL","UC04");
        if(actions.isEmpty())throw new SecurityException("INBOX_SCOPE_DENIED");
        String actor=auth.require(actions.values().iterator().next(),Set.of(clinic)).actor();
        return tx.execute(c->{
            String markers=String.join(",",Collections.nCopies(actions.size(),"?"));
            String sql="""
                SELECT r.request_id,r.request_version,r.received_at,r.associated_patient_id,r.submitted_content::text
                FROM adc_mini.preliminary_request r
                LEFT JOIN adc.appointment a ON a.appointment_id::text=r.submitted_content->>'originalAppointmentId'
                  AND a.patient_id::text=r.submitted_content->>'patientId'
                LEFT JOIN adc.reception_schedule_version s ON s.schedule_id=a.schedule_id
                  AND s.schedule_version_no=a.schedule_version_no
                WHERE CASE WHEN r.submitted_content->>'type'='NEW' THEN r.submitted_content->>'intendedClinicId'
                  ELSE s.clinic_id::text END = ?
                  AND EXISTS(SELECT 1 FROM adc_mini.operation_audit_event e WHERE e.request_id=r.request_id AND e.operation='op008')
                """ + " AND r.submitted_content->>'type' IN ("+markers+")"
                +(cursor==null?"":" AND r.request_id>?")+(selected==null?"":" AND r.request_id=?")
                +" ORDER BY r.request_id LIMIT 51";
            var output=JSON.createObjectNode();var items=output.putArray("items");
            try(var p=c.prepareStatement(sql)){
                int index=1;p.setString(index++,Long.toString(clinic));
                for(String type:actions.keySet())p.setString(index++,type);
                if(cursor!=null)p.setObject(index++,cursor);if(selected!=null)p.setObject(index,selected);
                try(var rows=p.executeQuery()){
                    int scanned=0;String last=null;
                    while(rows.next()){
                        if(scanned++==50){output.put("nextCursor",last);break;}
                        UUID id=rows.getObject(1,UUID.class);last=id.toString();
                        var input=AuditDocuments.parse(rows.getString(5));String type=input.path("type").asText();
                        Long patient=rows.getObject(4,Long.class);
                        if(RequestDecisions.reason(c,id)!=null||completed(c,id,type,patient,input))continue;
                        var row=items.addObject().put("requestId",id.toString()).put("requestVersion",rows.getInt(2))
                            .put("receivedAt",rows.getObject(3,OffsetDateTime.class).toInstant().toString())
                            .put("type",type).put("clinicId",Long.toString(clinic))
                            .put("applicantSummary",mask(input.path("identity").path("applicantInformation").asText()))
                            .put("contactSummary",mask(input.path("identity").path("contactInformation").asText()));
                        if(patient!=null)row.put("associatedPatientId",Long.toString(patient));
                        if(!type.equals("NEW"))row.put("originalAppointmentId",input.path("originalAppointmentId").asText());
                        String date=input.path("preferred").path("date").asText();
                        if(date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))row.put("preferredDate",date);
                    }
                }
            }
            for(String action:new HashSet<>(actions.values()))
                if(!actor.equals(auth.require(action,Set.of(clinic)).actor()))throw new SecurityException("INBOX_ACTOR_CHANGED");
            return output;
        });
    }
    private boolean completed(Connection c,UUID request,String type,Long patient,JsonNode input)throws SQLException{
        return completed(c,request,type,patient,input,clinic);
    }
    static boolean completed(Connection c,UUID request,String type,Long patient,JsonNode input,long clinic)throws SQLException{
        String uc=type.equals("NEW")?"UC02":type.equals("CHANGE")?"UC03":"UC04";
        String operation=type.equals("NEW")?"APPOINTMENT_CREATE":type.equals("CHANGE")?"APPOINTMENT_CHANGE":"APPOINTMENT_CANCEL";
        String event=type.equals("NEW")?"UC02_BOOKED":type.equals("CHANGE")?"UC03_CHANGED":"UC04_CANCELLED";
        int valid=0;
        try(var p=c.prepareStatement("""
            SELECT uc.evidence,uc.business_reference,uc.request_reference,uc.authorization_scope
            FROM adc_mini.operation_audit_event e JOIN adc.operation_audit_event uc ON uc.request_reference=e.original_request_reference
            WHERE e.request_id=? AND e.operation=? AND e.event_id IS NOT NULL
              AND uc.uc_code=? AND uc.operation_name=? AND uc.outcome='SUCCESS'
            """)){
            p.setObject(1,request);p.setString(2,event);p.setString(3,uc);p.setString(4,operation);
            try(var rows=p.executeQuery()){while(rows.next()){
                try{
                    var doc=AuditDocuments.parse(rows.getString(1));var ref=doc.path("businessResultSnapshot").path("businessReference");
                    var snapshot=doc.path("businessResultSnapshot");var authorization=AuditDocuments.parse(rows.getString(4));
                    boolean clinicGranted=false;
                    for(var grant:authorization.path("actualGrantedScope"))for(var id:grant.path("clinicIds"))
                        if(uc.equals(grant.path("action").asText())&&id.asLong()==clinic)clinicGranted=true;
                    if(doc.path("schemaVersion").asInt()!=1||!operation.equals(doc.path("operationKind").asText())
                        ||!doc.path("canonicalPayloadHash").asText().matches("[a-f0-9]{64}")
                        ||!snapshot.has("before")||!snapshot.has("after")||!snapshot.path("verification").isObject()||snapshot.path("verification").isEmpty()
                        ||!"ALLOW".equals(authorization.path("decision").asText())||!uc.equals(authorization.path("evaluatedAction").asText())||!clinicGranted
                        ||!"BUSINESS_RESULT".equals(doc.path("eventPhase").asText())||!"YES".equals(doc.path("confirmedCommit").asText())
                        ||!ref.equals(AuditDocuments.parse(rows.getString(2))))continue;
                    String appointment=ref.path("appointmentId").asText();
                    if(!appointment.matches("[1-9][0-9]{0,18}")||patient==null||(!type.equals("NEW")&&!appointment.equals(input.path("originalAppointmentId").asText())))continue;
                    try(var fact=c.prepareStatement("SELECT 1 FROM adc.appointment WHERE appointment_id::text=? AND patient_id=?")){
                        fact.setString(1,appointment);fact.setLong(2,patient);try(var found=fact.executeQuery()){if(!found.next())continue;}
                    }
                    if(type.equals("CHANGE")){
                        try(var fact=c.prepareStatement("SELECT 1 FROM adc.appointment_change_history WHERE appointment_change_id::text=? AND appointment_id::text=? AND change_request_reference=?")){
                            fact.setString(1,ref.path("appointmentChangeId").asText());fact.setString(2,appointment);fact.setString(3,rows.getString(3));
                            try(var found=fact.executeQuery()){if(!found.next())continue;}
                        }
                    }
                    if(type.equals("CANCEL")){
                        try(var fact=c.prepareStatement("""
                          SELECT appointment_status,no_show_verification,cancellation_verification,reminder_arrangement,
                            EXISTS(SELECT 1 FROM adc.visit v WHERE v.appointment_id=a.appointment_id)
                          FROM adc.appointment a WHERE appointment_id::text=? AND patient_id=?
                          """)){
                            fact.setString(1,appointment);fact.setLong(2,patient);
                            try(var found=fact.executeQuery()){
                                if(!found.next()||!"已取消".equals(found.getString(1))||found.getString(2)!=null||found.getBoolean(5))continue;
                                var basis=AuditDocuments.parse(found.getString(3));var reminder=AuditDocuments.parse(found.getString(4));
                                String requestReference=rows.getString(3);
                                if(basis.path("schemaVersion").asInt()!=1||basis.path("verificationReference").asText().isBlank()
                                  ||reminder.path("schemaVersion").asInt()!=1||!"STOPPED".equals(reminder.path("state").asText())
                                  ||!requestReference.equals(basis.path("requestReference").asText())
                                  ||!requestReference.equals(reminder.path("requestReference").asText())
                                  ||!requestReference.equals(snapshot.path("verification").path("requestReference").asText()))continue;
                            }
                        }
                    }
                    valid++;
                }catch(IllegalArgumentException malformed){/* Remain pending: ordinary/opaque audit is not a success proof. */}
            }}
        }
        return valid==1;
    }
    private static String mask(String text){
        if(text==null||text.isBlank())return "未提供";
        int end=text.offsetByCodePoints(0,1);return text.substring(0,end)+"***";
    }
    private static UUID uuid(String text){UUID id=UUID.fromString(text);if(!id.toString().equals(text))throw new IllegalArgumentException("INVALID_INBOX_CURSOR");return id;}
}
