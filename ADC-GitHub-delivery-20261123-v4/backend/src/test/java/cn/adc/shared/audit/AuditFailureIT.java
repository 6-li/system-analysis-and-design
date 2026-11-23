package cn.adc.shared.audit;
import cn.adc.PgITSupport;
import cn.adc.shared.db.*;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ADC_P0_IT_ENABLED",matches="true")
class AuditFailureIT {
 private void auditInsert(boolean allowed)throws Exception {try(var c=PgITSupport.owner().getConnection();var s=c.createStatement()){s.execute((allowed?"GRANT INSERT ON adc.extension_operation_audit_event TO ":"REVOKE INSERT ON adc.extension_operation_audit_event FROM ")+"p0_probe_app");}}
 @Test void actualAuditPermissionFailureCannotClaimFailureAuditWasSaved()throws Exception {
  var cmd=PgITSupport.command(project);var ticket=service.begin(cmd);auditInsert(false);
  try {var result=service.perform(ticket,(c,l)->PgITSupport.edit(c,project,"SYNTHETIC-must-rollback"));assertEquals("FAILED",result.businessOutcome());assertEquals(CommandRecovery.Persistence.NOT_PERSISTED,result.auditPersistence());assertEquals(1,events(ticket.requestReference()));try(var c=PgITSupport.app().getConnection()){assertEquals("SYNTHETIC-before",PgITSupport.row(c,project).get("project_name").asText());}}
  finally {auditInsert(true);}
  assertEquals("UNKNOWN",service.recover(cmd,false).businessOutcome());
 }
 @Test void actualStartAuditWriteFailureNeverRunsBusiness()throws Exception {
  auditInsert(false);try {var result=service.execute(PgITSupport.command(project),(c,l)->{throw new AssertionError("no persisted START");});assertEquals("UNKNOWN",result.businessOutcome());assertEquals(CommandRecovery.Persistence.NOT_PERSISTED,result.auditPersistence());}finally {auditInsert(true);}
 }
 @Test void failedClosureWriteDoesNotFenceWorkerUntilDurablyConfirmed()throws Exception {
  var cmd=PgITSupport.command(project);var ticket=service.begin(cmd);auditInsert(false);try {var result=service.recover(cmd,true);assertEquals("UNKNOWN",result.businessOutcome());assertEquals(CommandRecovery.Persistence.NOT_PERSISTED,result.auditPersistence());assertEquals(1,events(ticket.requestReference()));}finally {auditInsert(true);}
  assertEquals("SUCCEEDED",service.perform(ticket,(c,l)->PgITSupport.edit(c,project,"SYNTHETIC-after")).businessOutcome());
 }
 private long project;private AtomicReference<Map<String,StaffAuthorization.Grant>> grants;private StaffAuthorization auth;private CommandRecovery service;
 @BeforeEach void setup()throws Exception {project=PgITSupport.project();grants=PgITSupport.grants();auth=PgITSupport.authorization(grants);service=PgITSupport.hq(PgITSupport.app(),auth);}
 private long events(String ref)throws Exception {try(var c=PgITSupport.app().getConnection();var p=c.prepareStatement("SELECT count(*) FROM adc.extension_operation_audit_event WHERE request_reference=?")){p.setString(1,ref);try(var r=p.executeQuery()){r.next();return r.getLong(1);}}}
 private void insertInvalidAudit(Connection c)throws SQLException {try(var st=c.createStatement()){st.execute("INSERT INTO adc.extension_operation_audit_event(audit_event_id) VALUES(gen_random_uuid())");}}
 @Test void startCommitsIndependentlySuccessPersistsWholeSnapshotsAndDuplicateNeverReexecutes()throws Exception {
  var cmd=PgITSupport.command(project);var t=service.begin(cmd);assertEquals(1,events(t.requestReference()));var calls=new AtomicInteger();
  var result=service.perform(t,(c,l)->{calls.incrementAndGet();return PgITSupport.edit(c,project,"SYNTHETIC-after");});assertEquals("SUCCEEDED",result.businessOutcome());assertEquals(CommandRecovery.Persistence.CONFIRMED,result.auditPersistence());assertEquals(2,events(t.requestReference()));
  var replay=service.execute(cmd,(c,l)->{calls.incrementAndGet();throw new AssertionError("must not replay");});assertEquals("SUCCEEDED",replay.businessOutcome());assertTrue(replay.existingResult());assertEquals(1,calls.get());
  try(var c=PgITSupport.app().getConnection();var p=c.prepareStatement("SELECT evidence->'before'->'targetVersion'->>'project_name', evidence->'after'->'targetVersion'->>'project_name',authorization_context->'roles' FROM adc.extension_operation_audit_event WHERE request_reference=? AND outcome='SUCCEEDED'")){p.setString(1,t.requestReference());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("SYNTHETIC-before",r.getString(1));assertEquals("SYNTHETIC-after",r.getString(2));assertTrue(r.getString(3).contains("HQ"));}}
 }
 @Test void differentPayloadSameKeyRejectsAndRetainsOriginalHash()throws Exception {
  var cmd=PgITSupport.command(project);assertEquals("SUCCEEDED",service.execute(cmd,(c,l)->PgITSupport.edit(c,project,"SYNTHETIC-after")).businessOutcome());
  var altered=new CommandRecovery.Command(cmd.operation(),null,cmd.clientKey(),AuditDocuments.JSON.createObjectNode().put("different",true),Set.of());
  assertEquals("REJECTED",service.execute(altered,(c,l)->{throw new AssertionError("must not execute");}).businessOutcome());
  try(var c=PgITSupport.app().getConnection();var p=c.prepareStatement("SELECT count(DISTINCT evidence->>'requestPayloadHash')=1 FROM adc.extension_operation_audit_event WHERE request_reference=?")){p.setString(1,service.reference(cmd));try(var r=p.executeQuery()){r.next();assertTrue(r.getBoolean(1));}}
 }
 @Test void lateWorkerCannotExecuteAfterDurableRecoveryClosure()throws Exception {
  var cmd=PgITSupport.command(project);var ticket=service.begin(cmd);assertEquals("REJECTED",service.recover(cmd,true).businessOutcome());
  assertEquals("REJECTED",service.perform(ticket,(c,l)->{throw new AssertionError("late callback must not run");}).businessOutcome());assertEquals(2,events(ticket.requestReference()));
 }
 @Test void revokedPermissionPersistsActualDenialWithoutCallingBusiness()throws Exception {
  var cmd=PgITSupport.command(project);var t=service.begin(cmd);grants.set(Map.of());var result=service.perform(t,(c,l)->{throw new AssertionError("revoked");});assertEquals("REJECTED",result.businessOutcome());assertEquals(CommandRecovery.Persistence.CONFIRMED,result.auditPersistence());
  try(var c=PgITSupport.app().getConnection();var p=c.prepareStatement("SELECT authorization_context->>'decision',outcome FROM adc.extension_operation_audit_event WHERE request_reference=? AND event_sequence_no=2")){p.setString(1,t.requestReference());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("DENY",r.getString(1));assertEquals("REJECTED",r.getString(2));}}
 }
 @Test void revokedRecoveryReadDoesNotReturnSavedContentButIsAudited()throws Exception {
  var cmd=PgITSupport.command(project);assertEquals("SUCCEEDED",service.execute(cmd,(c,l)->PgITSupport.edit(c,project,"SYNTHETIC-after")).businessOutcome());grants.set(Map.of());var denied=service.recover(cmd,false);assertEquals("REJECTED",denied.businessOutcome());assertNull(denied.result());assertEquals(CommandRecovery.Persistence.CONFIRMED,denied.auditPersistence());
 }
 @Test void successAuditFailureRollsBackBusinessThenFailureCommitsSeparately()throws Exception {
  var cmd=PgITSupport.command(project);var result=service.execute(cmd,(c,l)->{var change=PgITSupport.edit(c,project,"SYNTHETIC-must-rollback");insertInvalidAudit(c);return change;});assertEquals("FAILED",result.businessOutcome());assertEquals(CommandRecovery.Persistence.CONFIRMED,result.auditPersistence());
  try(var c=PgITSupport.app().getConnection()){assertEquals("SYNTHETIC-before",PgITSupport.row(c,project).get("project_name").asText());}assertEquals(2,events(service.reference(cmd)));
 }
 @Test void malformedCompleteEvidenceRollsBackEvenIfBusinessStatementSucceeded()throws Exception {
  var cmd=PgITSupport.command(project);var result=service.execute(cmd,(c,l)->{PgITSupport.edit(c,project,"SYNTHETIC-must-rollback");return new AuditDocuments.Change(null,null,null,null);});assertEquals("REJECTED",result.businessOutcome());assertEquals(CommandRecovery.Persistence.CONFIRMED,result.auditPersistence());try(var c=PgITSupport.app().getConnection()){assertEquals("SYNTHETIC-before",PgITSupport.row(c,project).get("project_name").asText());}
 }
 @Test void oneTerminalAndDynamicSequencesUnderCompetingRecovery()throws Exception {
  var cmd=PgITSupport.command(project);service.begin(cmd);assertEquals("UNKNOWN",service.recover(cmd,false).businessOutcome());try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var a=pool.submit(()->service.recover(cmd,true));var b=pool.submit(()->service.recover(cmd,true));assertEquals("REJECTED",a.get(5,TimeUnit.SECONDS).businessOutcome());assertEquals("REJECTED",b.get(5,TimeUnit.SECONDS).businessOutcome());}
  try(var c=PgITSupport.app().getConnection();var p=c.prepareStatement("SELECT count(*) FILTER (WHERE evidence->>'eventKind'='TERMINAL'),count(DISTINCT event_sequence_no)=count(*),min(event_sequence_no) FILTER (WHERE evidence->>'eventKind'='TERMINAL') FROM adc.extension_operation_audit_event WHERE request_reference=?")){p.setString(1,service.reference(cmd));try(var r=p.executeQuery()){r.next();assertEquals(1,r.getInt(1));assertTrue(r.getBoolean(2));assertEquals(3,r.getInt(3));}}
 }
 @Test void recoveryRefusesUnverifiedOrContradictoryBusinessFact()throws Exception {
  var cmd=PgITSupport.command(project);var t=service.begin(cmd);var refuse=new CommandRecovery.SuccessVerifier(){public boolean matchesCommitted(Connection c,CommandRecovery.Command x,JsonNode n){return false;}public boolean hasContradictorySuccess(Connection c,CommandRecovery.Command x,String r){return true;}};
  var guarded=new ExtensionAuditService(new JdbcTransactions(PgITSupport.app()),auth,refuse,Clock.systemUTC()).commands();assertEquals("UNKNOWN",guarded.recover(cmd,true).businessOutcome());assertEquals(2,events(t.requestReference()));
 }
 @Test void priorSuccessfulEditRemainsSuccessfulAfterLaterIndependentEdit()throws Exception {
  var first=PgITSupport.command(project);assertEquals("SUCCEEDED",service.execute(first,(c,l)->PgITSupport.edit(c,project,"SYNTHETIC-after")).businessOutcome());var second=PgITSupport.command(project);assertEquals("SUCCEEDED",service.execute(second,(c,l)->PgITSupport.edit(c,project,"SYNTHETIC-after-later")).businessOutcome());assertEquals("SUCCEEDED",service.recover(first,false).businessOutcome());
 }
 @Test void actualJdbcLostSuccessCommitRecoversWithoutReexecution()throws Exception {
  var cmd=PgITSupport.command(project);var ticket=service.begin(cmd);var lossy=PgITSupport.hq(PgITSupport.proxy(),auth);var calls=new AtomicInteger();var unknown=lossy.perform(ticket,(c,l)->{calls.incrementAndGet();return PgITSupport.edit(c,project,"SYNTHETIC-after");});assertEquals("UNKNOWN",unknown.businessOutcome());assertEquals(CommandRecovery.Persistence.UNKNOWN,unknown.auditPersistence());assertEquals("SUCCEEDED",service.recover(cmd,false).businessOutcome());assertEquals(1,calls.get());
 }
 @Test void actualJdbcStartCommitUnknownDoesNotStartBusiness()throws Exception {
  var cmd=PgITSupport.command(project);var lossy=PgITSupport.hq(PgITSupport.proxy(),auth);var result=lossy.execute(cmd,(c,l)->{throw new AssertionError("unknown START must not execute");});assertEquals("UNKNOWN",result.businessOutcome());assertEquals(1,events(service.reference(cmd)));assertEquals("UNKNOWN",service.recover(cmd,false).businessOutcome());
 }
 @Test void actualJdbcCloseCommitUnknownMustBeVerifiedBeforeLateWorkerIsStopped()throws Exception {
  var cmd=PgITSupport.command(project);var ticket=service.begin(cmd);var result=PgITSupport.hq(PgITSupport.proxy(),auth).recover(cmd,true);assertEquals("UNKNOWN",result.businessOutcome());assertEquals(CommandRecovery.Persistence.UNKNOWN,result.auditPersistence());assertEquals("REJECTED",service.perform(ticket,(c,l)->{throw new AssertionError("persisted closure");}).businessOutcome());
 }
 @Test void lostObservationCommitDoesNotEraseAlreadyVerifiedSuccess()throws Exception {
  var cmd=PgITSupport.command(project);assertEquals("SUCCEEDED",service.execute(cmd,(c,l)->PgITSupport.edit(c,project,"SYNTHETIC-after")).businessOutcome());var result=PgITSupport.hq(PgITSupport.proxy(),auth).recover(cmd,false);assertEquals("SUCCEEDED",result.businessOutcome());assertEquals(CommandRecovery.Persistence.UNKNOWN,result.auditPersistence());
 }
 @Test void lostFailureAuditCommitIsSeparateFromKnownBusinessRollback()throws Exception {
  var calls=new AtomicInteger();DataSource routing=(DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(p,m,a)->{if(m.getName().equals("getConnection"))return (calls.incrementAndGet()==3?PgITSupport.proxy():PgITSupport.app()).getConnection();throw new UnsupportedOperationException();});
  var cmd=PgITSupport.command(project);var result=PgITSupport.hq(routing,auth).execute(cmd,(c,l)->{PgITSupport.edit(c,project,"SYNTHETIC-must-rollback");throw new SQLException("synthetic","23514");});assertEquals("FAILED",result.businessOutcome());assertEquals(CommandRecovery.Persistence.UNKNOWN,result.auditPersistence());assertEquals("FAILED",service.recover(cmd,false).businessOutcome());try(var c=PgITSupport.app().getConnection()){assertEquals("SYNTHETIC-before",PgITSupport.row(c,project).get("project_name").asText());}
 }
}
