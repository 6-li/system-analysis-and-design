package cn.adc.shared.audit;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.JsonNode;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** T011/T012 deterministic unit transaction double; not PostgreSQL evidence. */
class RecoveryRepairTest {
 static Object zero(Class<?> t){if(!t.isPrimitive()||t==void.class)return null;if(t==boolean.class)return false;if(t==int.class)return 0;return null;}
 @SuppressWarnings("unchecked") static <T>T proxy(Class<T> c,InvocationHandler h){return (T)Proxy.newProxyInstance(c.getClassLoader(),new Class[]{c},h);}
 static final class Memory {
  final List<Map<Integer,Object>> committed=new ArrayList<>();boolean failInsert,loseCommit,zeroInsert;int commits,rollbacks;Runnable onOpen=()->{};
  DataSource source(){return proxy(DataSource.class,(p,m,a)->m.getName().equals("getConnection")?connection():zero(m.getReturnType()));}
  Connection connection(){onOpen.run();var pending=new ArrayList<Map<Integer,Object>>();return proxy(Connection.class,(p,m,a)->switch(m.getName()){
   case "prepareStatement" -> statement((String)a[0],pending);
   case "createStatement" -> proxy(Statement.class,(x,y,z)->zero(y.getReturnType()));
   case "commit" -> {committed.addAll(pending);pending.clear();commits++;if(loseCommit){loseCommit=false;throw new SQLException("synthetic lost reply","08006");}yield null;}
   case "rollback" -> {pending.clear();rollbacks++;yield null;}
   default -> zero(m.getReturnType());
  });}
  PreparedStatement statement(String sql,List<Map<Integer,Object>> pending){var params=new HashMap<Integer,Object>();return proxy(PreparedStatement.class,(p,m,a)->{
   if(m.getName().startsWith("set")&&a!=null&&a.length==2&&a[0] instanceof Integer)params.put((Integer)a[0],a[1]);
   if(m.getName().equals("executeUpdate")){if(failInsert)throw new SQLException("synthetic audit permission failure","42501");if(zeroInsert)return 0;pending.add(new HashMap<>(params));return 1;}
   if(m.getName().equals("executeQuery")){
    var all=new ArrayList<Map<Integer,Object>>(committed);all.addAll(pending);var matches=all.stream().filter(r->Objects.equals(r.get(2),params.get(1))).toList();var index=new AtomicInteger(-1);boolean max=sql.contains("COALESCE");
    return proxy(ResultSet.class,(x,n,v)->switch(n.getName()){
     case "next" -> index.incrementAndGet()<(max?1:matches.size());
     case "getInt" -> max?matches.stream().mapToInt(r->(Integer)r.get(3)).max().orElse(0):(Integer)matches.get(index.get()).get(3);
     case "getString" -> {var row=matches.get(index.get());int column=(Integer)v[0];boolean original=sql.contains("adc.operation_audit_event");yield (String)row.get(switch(column){case 1->1;case 3->original?6:5;case 4->original?5:4;case 5->original?10:9;case 6->original?12:11;default->throw new AssertionError();});}
     default -> zero(n.getReturnType());
    });
   }return zero(m.getReturnType());
  });}
  JsonNode authorization(int i){return AuditDocuments.parse((String)committed.get(i).get(7));}
  JsonNode evidence(int i){return AuditDocuments.parse((String)committed.get(i).get(11));}
 }
 static final class Fixture {
  final Memory db=new Memory();final AtomicInteger evaluations=new AtomicInteger();final AtomicInteger revokeAt=new AtomicInteger(Integer.MAX_VALUE);final AtomicBoolean permitted=new AtomicBoolean(true),authenticated=new AtomicBoolean(true);Runnable verify=()->{};
  final StaffAuthorization.Grant grant=new StaffAuthorization.Grant("SYNTHETIC-REPAIR-v1",Set.of("HQ"),Set.of("DURATION_EDIT"),Set.of(),true);
  final StaffAuthorization auth=new StaffAuthorization(()->authenticated.get()?Optional.of("synthetic-hq"):Optional.empty(),()->permitted.get()&&evaluations.incrementAndGet()<revokeAt.get()?Map.of("synthetic-hq",grant):Map.of());
  final CommandRecovery.Command command=new CommandRecovery.Command("DURATION_DRAFT_EDIT",null,UUID.randomUUID(),AuditDocuments.JSON.createObjectNode().put("synthetic",true),Set.of());
  CommandRecovery service(){return new ExtensionAuditService(new JdbcTransactions(db.source()),auth,new CommandRecovery.SuccessVerifier(){public boolean matchesCommitted(Connection c,CommandRecovery.Command x,JsonNode n){verify.run();return true;}public boolean hasContradictorySuccess(Connection c,CommandRecovery.Command x,String r){verify.run();return true;}},Clock.systemUTC()).commands();}
  void start(){service().begin(command);evaluations.set(0);}
  void savedSuccess(){var row=new HashMap<>(db.committed.getFirst());row.put(1,UUID.randomUUID().toString());row.put(3,2);row.put(9,"SUCCEEDED");var doc=(tools.jackson.databind.node.ObjectNode)db.evidence(0).deepCopy();doc.put("eventKind","TERMINAL").put("phase","DRAFT_EDIT_SUCCESS");doc.putObject("before");doc.putObject("after").put("privateResult","SYNTHETIC");doc.putObject("verification");row.put(11,doc.toString());db.committed.add(row);}
 }
 @Test void uc21UnknownStartInspectsWithoutExecutingWorkEvenOnSameKey(){
  var db=new Memory();var cmd=uc21Command();var service=uc21(db);db.loseCommit=true;
  var unknown=service.executeUc21(cmd,(c,l)->{throw new AssertionError("unexpected work");});
  assertEquals("UNKNOWN",unknown.businessOutcome());assertNull(unknown.result());
  var duplicate=service.executeUc21(cmd,(c,l)->{throw new AssertionError("unexpected replay");});
  assertEquals("UNKNOWN",duplicate.businessOutcome());assertEquals(unknown.requestReference(),duplicate.requestReference());
  assertEquals(0,db.committed.stream().filter(row->"SUCCESS".equals(row.get(10))).count());
 }
 @Test void uc21LostCommitReplyRecoversOriginalSuccessIdWithoutExecutingTwice(){
  var db=new Memory();var cmd=uc21Command();var service=uc21(db);var calls=new AtomicInteger();
  var success=service.executeUc21(cmd,(c,l)->{
   calls.incrementAndGet();db.loseCommit=true;
   var n=AuditDocuments.JSON.createObjectNode().put("issueId","9");
   return new AuditDocuments.Change(n,AuditDocuments.JSON.nullNode(),n,AuditDocuments.JSON.createObjectNode());
  });
  assertEquals("SUCCEEDED",success.businessOutcome());assertTrue(success.existingResult());assertEquals("2",success.successEventId());
  var same=service.executeUc21(cmd,(c,l)->{throw new AssertionError("unexpected replay");});
  assertEquals(success.successEventId(),same.successEventId());assertEquals(1,calls.get());
  var changed=new CommandRecovery.Command(cmd.operation(),cmd.ucCode(),cmd.clientKey(),
          AuditDocuments.JSON.createObjectNode().put("different",true),cmd.clinics());
  assertEquals("REJECTED",service.executeUc21(changed,(c,l)->{throw new AssertionError("unexpected replay");}).businessOutcome());
 }
 @Test void uc21AuditZeroInsertNeverReturnsDurableSuccess(){
  var db=new Memory();var cmd=uc21Command();var service=uc21(db);
  var result=service.executeUc21(cmd,(c,l)->{
   db.zeroInsert=true;
   var n=AuditDocuments.JSON.createObjectNode().put("issueId","9");
   return new AuditDocuments.Change(n,AuditDocuments.JSON.nullNode(),n,AuditDocuments.JSON.createObjectNode());
  });
  assertEquals("FAILED",result.businessOutcome());
  assertEquals(CommandRecovery.Persistence.NOT_PERSISTED,result.auditPersistence());
  assertNull(result.successEventId());
  assertEquals(1,db.committed.size());
  assertTrue(db.rollbacks>0);
 }
 private static CommandRecovery.Command uc21Command(){return new CommandRecovery.Command("MAINTENANCE_ISSUE_SAVE","UC21",
         UUID.randomUUID(),AuditDocuments.JSON.createObjectNode(),Set.of(1L));}
 private static CommandRecovery uc21(Memory db){
  var auth=new StaffAuthorization(()->Optional.of("synthetic-manager"),()->Map.of("synthetic-manager",
          new StaffAuthorization.Grant("test",Set.of("CLINIC_MANAGER"),Set.of("UC21"),Set.of(1L),false)));
  var ids=new AtomicLong();
  return new UcAuditService(new JdbcTransactions(db.source()),auth,new CommandRecovery.SuccessVerifier(){
   public boolean matchesCommitted(Connection c,CommandRecovery.Command x,JsonNode saved){return saved.path("after").path("issueId").asText().equals("9");}
   public boolean hasContradictorySuccess(Connection c,CommandRecovery.Command x,String r){return false;}
  },Clock.systemUTC(),c->ids.incrementAndGet()).commands();
 }
 @Test void revokeAfterInitialCheckMustPersistActualDenySnapshot(){var f=new Fixture();f.start();f.revokeAt.set(3);var result=f.service().recover(f.command,false);assertEquals("REJECTED",result.businessOutcome());assertEquals(CommandRecovery.Persistence.CONFIRMED,result.auditPersistence());assertEquals("DENY",f.db.authorization(1).get("decision").asText());assertTrue(f.db.authorization(1).get("actualGrantedScope").isEmpty());}
 @Test void revokeAtLaterCheckMustNotEscapeWithoutDenialAudit(){var f=new Fixture();f.start();f.revokeAt.set(4);var result=f.service().recover(f.command,false);assertEquals("REJECTED",result.businessOutcome());assertEquals("DENY",f.db.authorization(1).get("decision").asText());}
 @Test void mismatchedPayloadMustPreserveOriginalAndAttemptDigests(){var f=new Fixture();f.start();var original=f.db.evidence(0).deepCopy();var altered=new CommandRecovery.Command(f.command.operation(),null,f.command.clientKey(),AuditDocuments.JSON.createObjectNode().put("synthetic",false),Set.of());var result=f.service().execute(altered,(c,l)->{throw new AssertionError("must not run");});assertEquals("REJECTED",result.businessOutcome());var observation=f.db.evidence(1).get("observation");assertEquals(original.get("requestPayloadHash"),observation.get("originalPayloadHash"));assertNotEquals(observation.get("originalPayloadHash"),observation.get("attemptedPayloadHash"));assertNotNull(observation.get("attemptedPayloadHash"));assertEquals(original,f.db.evidence(0));}
 @Test void initiallyDeniedRecoveryPersistsDenyAndRepeatedRecoveryNeverReexecutes(){var f=new Fixture();f.start();f.permitted.set(false);for(int i=0;i<2;i++){var r=f.service().recover(f.command,false);assertEquals("REJECTED",r.businessOutcome());assertNull(r.result());assertEquals(CommandRecovery.Persistence.CONFIRMED,r.auditPersistence());assertEquals("DENY",f.db.authorization(i+1).get("decision").asText());}assertEquals(1,f.db.committed.stream().filter(row->AuditDocuments.parse((String)row.get(11)).path("eventKind").asText().equals("TERMINAL")).count());}
 @Test void deniedAuditWriteFailureIsExplicitAndRecoveryRetryRetainsDenial(){var f=new Fixture();f.start();var original=f.db.evidence(0).deepCopy();f.permitted.set(false);f.db.failInsert=true;var failed=f.service().recover(f.command,false);assertEquals("REJECTED",failed.businessOutcome());assertEquals(CommandRecovery.Persistence.NOT_PERSISTED,failed.auditPersistence());assertEquals(1,f.db.committed.size());f.db.failInsert=false;assertEquals(CommandRecovery.Persistence.CONFIRMED,f.service().recover(f.command,false).auditPersistence());assertEquals("DENY",f.db.authorization(1).get("decision").asText());assertEquals(original,f.db.evidence(0));}
 @Test void deniedCommitUnknownNeverClaimsAuditConfirmedAndCanBeRetried(){var f=new Fixture();f.start();f.permitted.set(false);f.db.loseCommit=true;var unknown=f.service().recover(f.command,false);assertEquals("REJECTED",unknown.businessOutcome());assertEquals(CommandRecovery.Persistence.UNKNOWN,unknown.auditPersistence());assertNull(unknown.result());assertEquals(CommandRecovery.Persistence.CONFIRMED,f.service().recover(f.command,false).auditPersistence());}
 @Test void directRecoveryWithDifferentPayloadIsRejectedAndAudited(){var f=new Fixture();f.start();var command=new CommandRecovery.Command(f.command.operation(),null,f.command.clientKey(),AuditDocuments.JSON.createObjectNode().put("different",true),Set.of());assertEquals("REJECTED",f.service().recover(command,false).businessOutcome());assertNotNull(f.db.evidence(1).path("observation").get("attemptedPayloadHash"));assertEquals(2,f.db.committed.size());}
 @Test void mismatchWriteFailureRemainsRejectedButNotFalselyPersisted(){var f=new Fixture();f.start();f.db.failInsert=true;var command=new CommandRecovery.Command(f.command.operation(),null,f.command.clientKey(),AuditDocuments.JSON.createObjectNode().put("different",true),Set.of());var r=f.service().execute(command,(c,l)->{throw new AssertionError();});assertEquals("REJECTED",r.businessOutcome());assertEquals(CommandRecovery.Persistence.NOT_PERSISTED,r.auditPersistence());assertEquals(1,f.db.committed.size());}
 @Test void mismatchCommitUnknownKeepsRejectionAndRetryOnlyAppendsObservation(){var f=new Fixture();f.start();f.db.loseCommit=true;var command=new CommandRecovery.Command(f.command.operation(),null,f.command.clientKey(),AuditDocuments.JSON.createObjectNode().put("different",true),Set.of());var r=f.service().execute(command,(c,l)->{throw new AssertionError();});assertEquals("REJECTED",r.businessOutcome());assertEquals(CommandRecovery.Persistence.UNKNOWN,r.auditPersistence());assertEquals("REJECTED",f.service().recover(command,false).businessOutcome());assertEquals(3,f.db.committed.size());assertTrue(f.db.committed.stream().skip(1).allMatch(row->AuditDocuments.parse((String)row.get(11)).get("eventKind").asText().equals("OBSERVATION")));}
 @Test void laterRegrantDoesNotLeakSuccessToAttemptAlreadyDenied(){var f=new Fixture();f.start();f.savedSuccess();f.revokeAt.set(2);f.db.onOpen=()->{if(f.db.rollbacks>0)f.revokeAt.set(Integer.MAX_VALUE);};var r=f.service().recover(f.command,false);assertEquals("REJECTED",r.businessOutcome());assertNull(r.result());assertEquals("DENY",f.db.authorization(2).path("decision").asText());assertEquals("SUCCEEDED",f.db.committed.get(1).get(9));}
 @Test void identityLossDuringVerificationHasExplicitDenyWithoutInventingActor(){var f=new Fixture();f.start();f.savedSuccess();f.verify=()->f.authenticated.set(false);var r=f.service().recover(f.command,false);assertEquals("REJECTED",r.businessOutcome());assertNull(r.result());assertEquals("DENY",f.db.authorization(2).path("decision").asText());assertEquals("IDENTITY_UNAVAILABLE",f.db.authorization(2).path("policyVersion").asText());assertEquals("synthetic-hq",f.db.committed.get(2).get(5));}
 @Test void mismatchObservesLatestDenialAndDoesNotStorePayloadText(){var f=new Fixture();f.start();f.revokeAt.set(2);var command=new CommandRecovery.Command(f.command.operation(),null,f.command.clientKey(),AuditDocuments.JSON.createObjectNode().put("private","SYNTHETIC_PRIVATE_MARKER"),Set.of());var r=f.service().execute(command,(c,l)->{throw new AssertionError();});assertEquals("REJECTED",r.businessOutcome());assertEquals("DENY",f.db.authorization(1).path("decision").asText());assertFalse(f.db.evidence(1).toString().contains("SYNTHETIC_PRIVATE_MARKER"));}
 @Test void missingRequestRevokedDuringReadStillCreatesIndependentDenialTrace(){var f=new Fixture();f.revokeAt.set(3);var r=f.service().recover(f.command,false);assertEquals("REJECTED",r.businessOutcome());assertEquals(CommandRecovery.Persistence.CONFIRMED,r.auditPersistence());assertEquals(2,f.db.committed.size());assertEquals("DENY",f.db.authorization(0).path("decision").asText());assertEquals("DENY",f.db.authorization(1).path("decision").asText());assertEquals("START",f.db.evidence(0).path("eventKind").asText());assertEquals("TERMINAL",f.db.evidence(1).path("eventKind").asText());assertTrue(f.db.rollbacks>0);}
}
