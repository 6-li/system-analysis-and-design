package cn.adc.shared.audit;
import cn.adc.PgITSupport;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.JsonNode;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** T011/T012 isolated PostgreSQL regression; synthetic callbacks are not business services. */
@EnabledIfEnvironmentVariable(named="ADC_P0_IT_ENABLED",matches="true")
class RecoveryRepairIT {
 // Recovery may perform read, independent START-check and denial transactions.
 // Keep SQL/barrier deadlines unchanged; allow their separate connections to finish.
 static final int REQUEST_COMPLETION_SECONDS=15;
 long project;AtomicReference<Map<String,StaffAuthorization.Grant>> grants;StaffAuthorization auth;CommandRecovery service;
 @BeforeEach void setup()throws Exception {project=PgITSupport.project();grants=PgITSupport.grants();auth=PgITSupport.authorization(grants);service=PgITSupport.hq(PgITSupport.app(),auth);}
 List<JsonNode> rows(String ref)throws Exception {var rows=new ArrayList<JsonNode>();try(var c=PgITSupport.app().getConnection();var p=c.prepareStatement("SELECT row_to_json(t)::text FROM adc.extension_operation_audit_event t WHERE request_reference=? ORDER BY event_sequence_no")){p.setString(1,ref);try(var r=p.executeQuery()){while(r.next())rows.add(AuditDocuments.parse(r.getString(1)));}}return rows;}
 void denied(CommandRecovery.Receipt result,String ref)throws Exception {assertEquals("REJECTED",result.businessOutcome());assertNull(result.result());assertEquals(CommandRecovery.Persistence.CONFIRMED,result.auditPersistence());var last=rows(ref).getLast();assertEquals("DENY",last.path("authorization_context").path("decision").asText());assertTrue(last.path("authorization_context").path("actualGrantedScope").isEmpty());assertEquals("RECOVERY_PERMISSION_DENIED",last.path("reason").asText());}
 void insertPermission(boolean enabled)throws Exception {try(var c=PgITSupport.owner().getConnection();var s=c.createStatement()){s.execute((enabled?"GRANT INSERT ON adc.extension_operation_audit_event TO ":"REVOKE INSERT ON adc.extension_operation_audit_event FROM ")+"p0_probe_app");}}
 CommandRecovery.Command altered(CommandRecovery.Command original,int value){return new CommandRecovery.Command(original.operation(),null,original.clientKey(),AuditDocuments.JSON.createObjectNode().put("attempt",value),Set.of());}
 String hash(CommandRecovery.Command command){var x=AuditDocuments.JSON.createObjectNode().put("operation",command.operation());x.set("payload",command.payload());x.putArray("clinics");return AuditDocuments.hash(x);}
 void unchanged(List<JsonNode> before,List<JsonNode> after){assertEquals(before,after.subList(0,before.size()));}
 @Test void denialAtAllThreePostEntryChecksUsesActualSnapshot()throws Exception {
  for(int revokeAt:List.of(2,3,4)){
   var command=PgITSupport.command(project);service.begin(command);var calls=new AtomicInteger();var changing=new StaffAuthorization(()->Optional.of("synthetic-hq"),()->calls.incrementAndGet()>=revokeAt?Map.of():grants.get());var guarded=PgITSupport.hq(PgITSupport.app(),changing);denied(guarded.recover(command,false),service.reference(command));
  }
 }
 @Test void realCommandLockWaitThenConcurrentRevokeDeniesAndAudits()throws Exception {
  var cmd=PgITSupport.command(project);var ticket=service.begin(cmd);var entered=new CountDownLatch(1);var changing=new StaffAuthorization(()->Optional.of("synthetic-hq"),()->{entered.countDown();return grants.get();});var waiting=PgITSupport.hq(PgITSupport.app(),changing);
  try(var guard=PgITSupport.app().getConnection();var observer=PgITSupport.app().getConnection();var pool=Executors.newVirtualThreadPerTaskExecutor()){
   guard.setAutoCommit(false);new ResourceLockCoordinator().scope(guard).command(ticket.requestReference());var future=pool.submit(()->waiting.recover(cmd,false));assertTrue(entered.await(2,TimeUnit.SECONDS));
   boolean blocked=false;long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1500);while(System.nanoTime()<until){try(var p=observer.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event='advisory' AND cardinality(pg_blocking_pids(pid))>0)" );var r=p.executeQuery()){r.next();if(r.getBoolean(1)){blocked=true;break;}}Thread.sleep(5);}assertTrue(blocked,"must observe real blocking, not timing assumption");grants.set(Map.of());guard.rollback();denied(future.get(REQUEST_COMPLETION_SECONDS,TimeUnit.SECONDS),ticket.requestReference());
  }
 }
 @Test void revokeWhileSuccessVerifierRunsCannotReturnEarlierSuccess()throws Exception {
  var cmd=PgITSupport.command(project);assertEquals("SUCCEEDED",service.execute(cmd,(c,l)->PgITSupport.edit(c,project,"SYNTHETIC-after")).businessOutcome());String ref=service.reference(cmd);var before=rows(ref);var ready=new CountDownLatch(1);var release=new CountDownLatch(1);var actual=PgITSupport.actualVerifier();var verifier=new CommandRecovery.SuccessVerifier(){public boolean matchesCommitted(Connection c,CommandRecovery.Command x,JsonNode n)throws SQLException {ready.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new SQLException("test barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException("interrupted");}return actual.matchesCommitted(c,x,n);}public boolean hasContradictorySuccess(Connection c,CommandRecovery.Command x,String r)throws SQLException{return actual.hasContradictorySuccess(c,x,r);}};
  var guarded=new ExtensionAuditService(new JdbcTransactions(PgITSupport.app()),auth,verifier,Clock.systemUTC()).commands();try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var future=pool.submit(()->guarded.recover(cmd,false));try{assertTrue(ready.await(3,TimeUnit.SECONDS));grants.set(Map.of());}finally{release.countDown();}denied(future.get(5,TimeUnit.SECONDS),ref);}var after=rows(ref);unchanged(before,after);assertEquals(1,after.stream().filter(n->n.path("evidence").path("eventKind").asText().equals("TERMINAL")).count());
 }
 @Test void concurrentDeniedRecoveryAndRetryKeepOneTerminalAndTrueDenial()throws Exception {
  var cmd=PgITSupport.command(project);var t=service.begin(cmd);var original=rows(t.requestReference());grants.set(Map.of());try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var a=pool.submit(()->service.recover(cmd,false));var b=pool.submit(()->service.recover(cmd,false));denied(a.get(5,TimeUnit.SECONDS),t.requestReference());denied(b.get(5,TimeUnit.SECONDS),t.requestReference());}denied(service.recover(cmd,false),t.requestReference());var after=rows(t.requestReference());unchanged(original,after);assertEquals(1,after.stream().filter(n->n.path("evidence").path("eventKind").asText().equals("TERMINAL")).count());assertEquals(after.size(),after.stream().map(n->n.path("event_sequence_no").intValue()).distinct().count());
 }
 @Test void denialAuditInsertFailureIsNotReportedAsPersistedAndRetryRepairsTrace()throws Exception {
  var cmd=PgITSupport.command(project);var t=service.begin(cmd);var original=rows(t.requestReference());grants.set(Map.of());insertPermission(false);try{var r=service.recover(cmd,false);assertEquals("REJECTED",r.businessOutcome());assertEquals(CommandRecovery.Persistence.NOT_PERSISTED,r.auditPersistence());assertNull(r.result());assertEquals(original,rows(t.requestReference()));}finally{insertPermission(true);}denied(service.recover(cmd,false),t.requestReference());unchanged(original,rows(t.requestReference()));
 }
 @Test void denialT2LostCommitReplyRemainsUnknownUntilReread()throws Exception {
  var cmd=PgITSupport.command(project);var t=service.begin(cmd);grants.set(Map.of());var calls=new AtomicInteger();DataSource routing=(DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(p,m,a)->{if(m.getName().equals("getConnection"))return(calls.incrementAndGet()==2?PgITSupport.proxy():PgITSupport.app()).getConnection();throw new UnsupportedOperationException();});var result=PgITSupport.hq(routing,auth).recover(cmd,false);assertEquals("REJECTED",result.businessOutcome());assertEquals(CommandRecovery.Persistence.UNKNOWN,result.auditPersistence());assertNull(result.result());assertEquals(2,rows(t.requestReference()).size());denied(service.recover(cmd,false),t.requestReference());assertEquals(1,rows(t.requestReference()).stream().filter(n->n.path("evidence").path("eventKind").asText().equals("TERMINAL")).count());
 }
 @Test void differentPayloadEvidenceKeepsBothDigestsAndAllOriginalRows()throws Exception {
  var cmd=PgITSupport.command(project);assertEquals("SUCCEEDED",service.execute(cmd,(c,l)->PgITSupport.edit(c,project,"SYNTHETIC-after")).businessOutcome());String ref=service.reference(cmd);var before=rows(ref);var attempted=altered(cmd,1);assertEquals("REJECTED",service.execute(attempted,(c,l)->{throw new AssertionError("no replay");}).businessOutcome());var after=rows(ref);unchanged(before,after);var observation=after.getLast().path("evidence").path("observation");assertEquals(hash(cmd),observation.path("originalPayloadHash").asText());assertEquals(hash(attempted),observation.path("attemptedPayloadHash").asText());assertEquals(hash(cmd),after.getLast().path("evidence").path("requestPayloadHash").asText());
 }
 @Test void mismatchAuditFailureThenDirectRecoveryStillRejectsAndRecordsAttempt()throws Exception {
  var cmd=PgITSupport.command(project);service.begin(cmd);String ref=service.reference(cmd);var before=rows(ref);var attempted=altered(cmd,2);insertPermission(false);try{var r=service.execute(attempted,(c,l)->{throw new AssertionError();});assertEquals("REJECTED",r.businessOutcome());assertEquals(CommandRecovery.Persistence.NOT_PERSISTED,r.auditPersistence());assertEquals(before,rows(ref));}finally{insertPermission(true);}assertEquals("REJECTED",service.recover(attempted,false).businessOutcome());var after=rows(ref);unchanged(before,after);assertEquals(hash(attempted),after.getLast().path("evidence").path("observation").path("attemptedPayloadHash").asText());
 }
 @Test void mismatchLostCommitReplyDoesNotTurnKnownRejectionIntoBusinessUnknown()throws Exception {
  var cmd=PgITSupport.command(project);service.begin(cmd);String ref=service.reference(cmd);var before=rows(ref);var attempted=altered(cmd,3);var r=PgITSupport.hq(PgITSupport.proxy(),auth).execute(attempted,(c,l)->{throw new AssertionError();});assertEquals("REJECTED",r.businessOutcome());assertEquals(CommandRecovery.Persistence.UNKNOWN,r.auditPersistence());assertEquals("REJECTED",service.recover(attempted,false).businessOutcome());var after=rows(ref);unchanged(before,after);assertEquals(3,after.size());assertTrue(after.subList(1,after.size()).stream().allMatch(n->n.path("evidence").path("observation").path("attemptedPayloadHash").asText().equals(hash(attempted))));
 }
 @Test void concurrentDifferentAttemptsAreEachTraceableWithoutOverwritingOriginal()throws Exception {
  var cmd=PgITSupport.command(project);service.begin(cmd);String ref=service.reference(cmd);var before=rows(ref);var a=altered(cmd,4);var b=altered(cmd,5);try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var x=pool.submit(()->service.execute(a,(c,l)->{throw new AssertionError();}));var y=pool.submit(()->service.execute(b,(c,l)->{throw new AssertionError();}));assertEquals("REJECTED",x.get(5,TimeUnit.SECONDS).businessOutcome());assertEquals("REJECTED",y.get(5,TimeUnit.SECONDS).businessOutcome());}var after=rows(ref);unchanged(before,after);assertEquals(Set.of(hash(a),hash(b)),new HashSet<>(after.subList(1,after.size()).stream().map(n->n.path("evidence").path("observation").path("attemptedPayloadHash").asText()).toList()));assertEquals(3,after.stream().map(n->n.path("event_sequence_no").intValue()).distinct().count());
 }
 @Test void deniedMismatchedRecoveryRecordsBothAttemptAndDenialWithoutLeakingSuccess()throws Exception {
  var cmd=PgITSupport.command(project);assertEquals("SUCCEEDED",service.execute(cmd,(c,l)->PgITSupport.edit(c,project,"SYNTHETIC-after")).businessOutcome());String ref=service.reference(cmd);var before=rows(ref);var attempted=altered(cmd,6);grants.set(Map.of());denied(service.recover(attempted,false),ref);var after=rows(ref);unchanged(before,after);assertEquals(hash(attempted),after.get(before.size()).path("evidence").path("observation").path("attemptedPayloadHash").asText());assertTrue(after.subList(before.size(),after.size()).stream().allMatch(n->n.path("authorization_context").path("decision").asText().equals("DENY")));
 }
}
