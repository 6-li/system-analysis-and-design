package cn.adc.shared.audit;
import cn.adc.PgITSupport;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.JsonNode;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Table28/54 storage contract only: does not implement UC22 revision or UC03/04. */
@EnabledIfEnvironmentVariable(named="ADC_P0_IT_ENABLED",matches="true")
class OriginalAuditIT {
 @Test void original54MismatchAndDeniedRecoveryRetainApprovedTextDocumentShape()throws Exception {
  var grants=new java.util.concurrent.atomic.AtomicReference<>(Map.of("synthetic-manager",new StaffAuthorization.Grant("SYNTHETIC-v1",Set.of("CLINIC_MANAGER"),Set.of("UC21"),Set.of(1L),false)));var authorization=new StaffAuthorization(()->Optional.of("synthetic-manager"),grants::get);var service=new UcAuditService(new JdbcTransactions(PgITSupport.app()),authorization,new CommandRecovery.SuccessVerifier(){public boolean matchesCommitted(Connection c,CommandRecovery.Command x,JsonNode n){throw new AssertionError();}public boolean hasContradictorySuccess(Connection c,CommandRecovery.Command x,String r){throw new AssertionError();}},Clock.systemUTC()).commands();var key=UUID.randomUUID();var original=new CommandRecovery.Command("SYNTHETIC_REPAIR","UC21",key,AuditDocuments.JSON.createObjectNode().put("attempt",0),Set.of(1L));var t=service.begin(original);var altered=new CommandRecovery.Command(original.operation(),"UC21",key,AuditDocuments.JSON.createObjectNode().put("attempt",1),Set.of(1L));assertEquals("REJECTED",service.execute(altered,(c,l)->{throw new AssertionError();}).businessOutcome());grants.set(Map.of());assertEquals("REJECTED",service.recover(original,false).businessOutcome());
  try(var c=PgITSupport.app().getConnection();var p=c.prepareStatement("SELECT event_sequence_no,authorization_scope,evidence FROM adc.operation_audit_event WHERE request_reference=? ORDER BY event_sequence_no")){p.setString(1,t.requestReference());try(var r=p.executeQuery()){assertTrue(r.next());String originalHash=AuditDocuments.parse(r.getString(3)).path("canonicalPayloadHash").asText();assertTrue(r.next());var evidence=AuditDocuments.parse(r.getString(3));assertEquals(originalHash,evidence.path("canonicalPayloadHash").asText());assertTrue(evidence.path("observationReason").isString());var detail=AuditDocuments.parse(evidence.path("observationReason").asText());assertEquals(originalHash,detail.path("originalPayloadHash").asText());assertNotEquals(originalHash,detail.path("attemptedPayloadHash").asText());assertTrue(r.next());assertEquals("DENY",AuditDocuments.parse(r.getString(2)).path("decision").asText());assertFalse(r.next());}}
 }
 private StaffAuthorization auth(){return new StaffAuthorization(()->Optional.of("synthetic-manager"),()->Map.of("synthetic-manager",new StaffAuthorization.Grant("SYNTHETIC-v1",Set.of("CLINIC_MANAGER"),Set.of("UC21","UC22"),Set.of(1L),false)));}
 private UcAuditService service(){return new UcAuditService(new JdbcTransactions(PgITSupport.app()),auth(),new CommandRecovery.SuccessVerifier(){public boolean matchesCommitted(Connection c,CommandRecovery.Command x,JsonNode n){return false;}public boolean hasContradictorySuccess(Connection c,CommandRecovery.Command x,String r){return true;}},Clock.systemUTC());}
 @Test void ucIdentityIsPartOfRequestScopeAndOriginal54StoresStarts()throws Exception {
  var service=service().commands();var key=UUID.randomUUID();var payload=AuditDocuments.JSON.createObjectNode();var a=new CommandRecovery.Command("SYNTHETIC_INFRA", "UC21",key,payload,Set.of(1L));var b=new CommandRecovery.Command("SYNTHETIC_INFRA","UC22",key,payload,Set.of(1L));assertNotEquals(service.reference(a),service.reference(b));service.begin(a);service.begin(b);
  try(var c=PgITSupport.app().getConnection();var p=c.prepareStatement("SELECT uc_code,evidence FROM adc.operation_audit_event WHERE request_reference=?")){p.setString(1,service.reference(a));try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("UC21",r.getString(1));assertEquals("REQUEST_STARTED",AuditDocuments.parse(r.getString(2)).get("eventPhase").asText());}}
 }
 @Test void fullOriginalAndAppendSnapshotsRemainSeparateFromActualUcFacts()throws Exception {
  long patient=PgITSupport.next(),appointment=PgITSupport.next(),root=PgITSupport.next(),revision=PgITSupport.next();
  // Explicit storage fixtures, not formal appointment handling or a new status definition.
  PgITSupport.sql("INSERT INTO adc.patient(patient_id,patient_name,identity_information,contact_information) VALUES("+patient+",'SYNTHETIC','synthetic','synthetic');INSERT INTO adc.appointment(appointment_id,patient_id,schedule_id,schedule_version_no,starts_at,ends_at,treatment_request,manual_confirmation,appointment_status,reminder_arrangement,operated_by,operated_at) VALUES("+appointment+","+patient+",9002,1,'2026-11-06T01:00Z','2026-11-06T01:30Z','SYNTHETIC','SYNTHETIC','SYNTHETIC_STORAGE_ONLY','SYNTHETIC','SYNTHETIC','2026-11-05T00:00Z')");
  var svc=service();JsonNode prior=null;
  for(long id:List.of(root,revision)){
   String kind=id==root?"RESOLUTION_CREATE":"RESOLUTION_APPEND";var cmd=new CommandRecovery.Command(kind,"UC22",UUID.randomUUID(),AuditDocuments.JSON.createObjectNode().put("id",id),Set.of(1L));var ticket=svc.commands().begin(cmd);JsonNode before=prior;
   prior=new JdbcTransactions(PgITSupport.app()).execute(c->{var locks=new ResourceLockCoordinator().scope(c);locks.command(ticket.requestReference());locks.schedules(List.of(9000L));locks.dentists(List.of(1L));locks.versions(List.of(new ResourceLockCoordinator.Version(9000,1)));locks.appointments(List.of(appointment));if(before!=null)locks.roots(List.of(root));
    try(var p=c.prepareStatement("INSERT INTO adc.appointment_resolution(resolution_id,schedule_id,schedule_version_no,appointment_id,resolution_subject,resolution_result,resolution_basis,responsible_party,pending_confirmation,recorded_at) VALUES(?,9000,1,?,'SYNTHETIC','PENDING',NULL,'synthetic-manager','not a successful UC03/04 fact','2026-11-05T00:00Z')")){p.setLong(1,id);p.setLong(2,appointment);p.executeUpdate();}
    JsonNode after;try(var s=c.createStatement();var r=s.executeQuery("SELECT row_to_json(t)::text FROM adc.appointment_resolution t WHERE resolution_id="+id)){r.next();var n=(tools.jackson.databind.node.ObjectNode)AuditDocuments.parse(r.getString(1));for(String f:List.of("resolution_id","schedule_id","appointment_id"))n.put(f,n.get(f).asText());after=n;}
    var doc=AuditDocuments.JSON.createObjectNode().put("schemaVersion",1).put("operationKind",kind).put("requestReference",ticket.requestReference()).put("canonicalPayloadHash",ticket.canonicalPayloadHash()).put("rootResolutionId",Long.toString(root)).put("newResolutionId",Long.toString(id));doc.putObject("targetDraftRef").put("scheduleId","9000").put("versionNo",1);if(before==null){doc.putNull("predecessorResolutionId");doc.putNull("revisionReason");doc.putNull("before");}else{doc.put("predecessorResolutionId",Long.toString(root));doc.put("revisionReason","correct explanation, not success");doc.set("before",before);}doc.set("after",after);var v=doc.putObject("verification").put("evaluatedAt","2026-11-05T00:00Z").put("appointmentFingerprint","a".repeat(64)).put("conclusion","PENDING").put("pendingReason","no successful UC fact");v.putNull("formalEvidence");v.putArray("supportReasons");svc.appendRevision(c,locks,Set.of(1L),doc);return after;
   });
  }
  assertEquals(2,PgITSupport.count("SELECT count(*) FROM adc.appointment_resolution WHERE resolution_id IN ("+root+","+revision+")"));assertEquals(0,PgITSupport.count("SELECT count(*) FROM adc.appointment_change_history WHERE appointment_id="+appointment));
  assertEquals(1,PgITSupport.count("SELECT count(*) FROM adc.operation_audit_event WHERE evidence::jsonb->>'newResolutionId'='"+revision+"' AND evidence::jsonb->'before'->>'resolution_id'='"+root+"' AND evidence::jsonb->'verification'->>'conclusion'='PENDING'"));
 }
}
