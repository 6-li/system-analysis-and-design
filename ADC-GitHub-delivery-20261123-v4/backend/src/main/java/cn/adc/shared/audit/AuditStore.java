package cn.adc.shared.audit;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Parameterized append-only storage. No table28 UPDATE or forged UC for headquarters. */
public final class AuditStore {
 public enum Family {UC,HQ}
 public record Event(String id,int sequence,String actor,String operation,String outcome,JsonNode evidence){
  public String kind(){if(evidence.has("eventKind"))return evidence.get("eventKind").asText();String p=evidence.path("eventPhase").asText();if(!p.isEmpty())return p.equals("REQUEST_STARTED")?"START":p.equals("RECOVERY_OBSERVATION")?"OBSERVATION":p.equals("BUSINESS_RESULT")?"TERMINAL":"UNVERIFIABLE";String k=evidence.path("operationKind").asText();return Set.of("RESOLUTION_CREATE","RESOLUTION_APPEND").contains(k)?"TERMINAL":"UNVERIFIABLE";}
 }
 @FunctionalInterface public interface EventIdAllocator {long next(Connection connection)throws SQLException;}
 private final Family family;private final EventIdAllocator eventIds;
 public AuditStore(Family f){this(f,null);}
 public AuditStore(Family f,EventIdAllocator eventIds){family=f;this.eventIds=eventIds;}
 public Family family(){return family;}
 public List<Event> read(Connection c,String request)throws SQLException {
  String sql=family==Family.HQ?"SELECT audit_event_id::text,event_sequence_no,actor_reference,operation_code,outcome,evidence::text FROM adc.extension_operation_audit_event WHERE request_reference=? ORDER BY event_sequence_no":"SELECT audit_event_id::text,event_sequence_no,actor_reference,operation_name,outcome,evidence FROM adc.operation_audit_event WHERE request_reference=? ORDER BY event_sequence_no";
  var rows=new ArrayList<Event>();try(var p=c.prepareStatement(sql)){p.setString(1,request);try(var r=p.executeQuery()){while(r.next()){String raw=r.getString(6);JsonNode doc;try{doc=raw==null?NullNode.getInstance():AuditDocuments.parse(raw);}catch(IllegalArgumentException e){doc=NullNode.getInstance();}String outcome=r.getString(5);rows.add(new Event(r.getString(1),r.getInt(2),r.getString(3),r.getString(4),outcome.equals("SUCCESS")?"SUCCEEDED":outcome,doc));}}}return List.copyOf(rows);
 }
 public Event append(Connection c,String request,String operation,String ucCode,StaffAuthorization.Context actor,String outcome,String reason,JsonNode businessReference,JsonNode evidence,Instant occurred)throws SQLException {
  if(c.getAutoCommit())throw new SQLException("AUDIT_REQUIRES_TRANSACTION","25000");
  int seq;try(var p=c.prepareStatement("SELECT COALESCE(max(event_sequence_no),0) FROM adc."+(family==Family.HQ?"extension_operation_audit_event":"operation_audit_event")+" WHERE request_reference=?")){p.setString(1,request);try(var r=p.executeQuery()){r.next();seq=Math.addExact(r.getInt(1),1);}}
  String id;if(family==Family.HQ){id=UUID.randomUUID().toString();((ObjectNode)evidence).put("logicalEventId",id);}else{
   if(ucCode==null||!ucCode.matches("UC(0[1-9]|1[0-9]|2[0-2])"))throw new IllegalArgumentException("ORIGINAL_UC_REQUIRED");
   if(eventIds!=null){
    long next=eventIds.next(c);if(next<=0)throw new SQLException("AUDIT_ID_UNAVAILABLE","22003");id=Long.toString(next);
   }else{
    try(var st=c.createStatement()){st.execute("SELECT pg_advisory_xact_lock(41003,54)");try(var r=st.executeQuery("SELECT COALESCE(max(audit_event_id),0) FROM adc.operation_audit_event")){r.next();id=Long.toString(Math.addExact(r.getLong(1),1));}}
   }
  }
  ObjectNode auth=AuditDocuments.JSON.createObjectNode();auth.put("schemaVersion",1);auth.put("policyVersion",actor.policyVersion());auth.put("evaluatedAction",actor.action());auth.put("decision",actor.allowed()?"ALLOW":"DENY");var roles=auth.putArray("roles");actor.roles().stream().sorted().forEach(roles::add);var scopes=auth.putArray("actualGrantedScope");if(actor.allowed()){var s=scopes.addObject();s.put("action",actor.action());s.put("scope",actor.globalCatalog()?"GLOBAL_CATALOG":"CLINICS");var clinics=s.putArray("clinicIds");actor.clinicIds().stream().sorted().forEach(clinics::add);}
  String sql=family==Family.HQ?"INSERT INTO adc.extension_operation_audit_event(audit_event_id,request_reference,event_sequence_no,operation_code,actor_reference,occurred_at,authorization_context,business_reference,outcome,reason,evidence) VALUES(?::uuid,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?::jsonb)":"INSERT INTO adc.operation_audit_event(audit_event_id,request_reference,event_sequence_no,uc_code,operation_name,actor_reference,occurred_at,authorization_scope,business_reference,outcome,reason,evidence) VALUES(?::bigint,?,?,?,?,?,?,?,?,?,?,?)";
  try(var p=c.prepareStatement(sql)){int i=1;p.setString(i++,id);p.setString(i++,request);p.setInt(i++,seq);if(family==Family.UC)p.setString(i++,ucCode);p.setString(i++,operation);p.setString(i++,actor.actor());p.setObject(i++,occurred.atOffset(ZoneOffset.UTC));p.setString(i++,auth.toString());p.setString(i++,businessReference==null||businessReference.isNull()?null:businessReference.toString());p.setString(i++,family==Family.UC&&outcome.equals("SUCCEEDED")?"SUCCESS":outcome);p.setString(i++,reason);p.setString(i,evidence.toString());
   if(p.executeUpdate()!=1)throw new SQLException("AUDIT_INSERT_NOT_CONFIRMED","P0001");}
  return new Event(id,seq,actor.actor(),operation,outcome,evidence.deepCopy());
 }
}
