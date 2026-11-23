package cn.adc.shared.audit;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Shared infrastructure only: trusted callbacks supply business work and real success verification. */
public final class CommandRecovery {
 public enum Persistence {CONFIRMED,NOT_PERSISTED,UNKNOWN}
 public record Receipt(String requestReference,String businessOutcome,Persistence auditPersistence,JsonNode result,
                       boolean existingResult,String successEventId,String reason){
  public Receipt(String reference,String outcome,Persistence persistence,JsonNode result,boolean existing){
   this(reference,outcome,persistence,result,existing,null,null);
  }
 }
 /** Controlled domain rejection codes may be preserved through the independent failure audit. */
 public static final class Rejection extends IllegalArgumentException {
  private final String code;
  public Rejection(String code){super(code);if(code==null||!code.matches("[A-Z0-9_]{1,64}"))throw new IllegalArgumentException("INVALID_REJECTION_CODE");this.code=code;}
 }
 public record Command(String operation,String ucCode,UUID clientKey,JsonNode payload,Set<Long> clinics){
  public Command {if(operation==null||!operation.matches("[A-Z0-9_]{1,40}"))throw new IllegalArgumentException("OPERATION_REQUIRED");Objects.requireNonNull(clientKey);payload=Objects.requireNonNull(payload).deepCopy();clinics=Set.copyOf(clinics);}
 }
 public static final class Ticket {
  private final Command command;private final String reference,owner,hash,token;private final boolean fresh;private StaffAuthorization.Context lastContext;
  private Ticket(Command command,String reference,String owner,String hash,String token,boolean fresh){this.command=command;this.reference=reference;this.owner=owner;this.hash=hash;this.token=token;this.fresh=fresh;}
  public String requestReference(){return reference;}
  public String canonicalPayloadHash(){return hash;}
 }
 @FunctionalInterface public interface Work {AuditDocuments.Change run(Connection c,ResourceLockCoordinator.Scope locks)throws Exception;}
 public interface SuccessVerifier {
  boolean matchesCommitted(Connection c,Command command,JsonNode savedChange)throws SQLException;
  boolean hasContradictorySuccess(Connection c,Command command,String requestReference)throws SQLException;
 }
 public static final class Unavailable extends RuntimeException {
  private final Receipt receipt;Unavailable(Receipt r){super("AUDIT_OR_RECOVERY_NOT_CONFIRMED");receipt=r;}public Receipt receipt(){return receipt;}
 }
 private static final Set<String> HQ=Set.of("DURATION_DRAFT_CREATE","DURATION_DRAFT_EDIT","DURATION_PUBLISH");
 private final JdbcTransactions tx;private final AuditStore store;private final StaffAuthorization auth;private final SuccessVerifier verifier;private final Clock clock;
 private final boolean courseUc22Narrow;
 public CommandRecovery(JdbcTransactions tx,AuditStore store,StaffAuthorization auth,SuccessVerifier verifier,Clock clock){this(tx,store,auth,verifier,clock,false);}
 public CommandRecovery(JdbcTransactions tx,AuditStore store,StaffAuthorization auth,SuccessVerifier verifier,Clock clock,boolean courseUc22Narrow){this.tx=Objects.requireNonNull(tx);this.store=Objects.requireNonNull(store);this.auth=Objects.requireNonNull(auth);this.verifier=Objects.requireNonNull(verifier);this.clock=Objects.requireNonNull(clock);this.courseUc22Narrow=courseUc22Narrow;}
 private boolean hq(){return store.family()==AuditStore.Family.HQ;}
 private String action(Command c){if(hq()){if(c.ucCode()!=null||!HQ.contains(c.operation()))throw new IllegalArgumentException("HEADQUARTERS_IS_NOT_ORIGINAL_UC");return c.operation().equals("DURATION_PUBLISH")?"DURATION_PUBLISH":"DURATION_EDIT";}if(c.ucCode()==null||!Set.of("UC01","UC02","UC03","UC04","UC05","UC06","UC07","UC08","UC09","UC10","UC11","UC12","UC13","UC14","UC15","UC16","UC17","UC18","UC19","UC20","UC21","UC22").contains(c.ucCode()))throw new IllegalArgumentException("P0_ORIGINAL_UC_REQUIRED");if("UC18".equals(c.ucCode())&&"QUARTER_ISSUE_CORRECT".equals(c.operation()))return "UC18_CORRECTION";return "UC11".equals(c.ucCode())&&("INVOICE_CLINICAL_CONFIRM".equals(c.operation())||"INVOICE_CLINICAL_CORRECT".equals(c.operation()))?"UC11_CLINICAL":c.ucCode();}
 private StaffAuthorization.Context context(Command c){return auth.evaluate(action(c),c.clinics());}
 private String scopedReference(String actor,Command c){action(c);String r=AuditDocuments.requestReference(actor,hq()?"HQCFG":"UC",c.operation(),c.clientKey());return hq()?r:r.replace("/v1/","/v1/"+c.ucCode()+"/");}
 public String reference(Command c){return scopedReference(context(c).actor(),c);}
 private static String payloadHash(Command c){var n=AuditDocuments.JSON.createObjectNode();n.put("operation",c.operation());if(c.ucCode()!=null)n.put("ucCode",c.ucCode());n.set("payload",c.payload());var scope=n.putArray("clinics");c.clinics().stream().sorted().forEach(scope::add);return AuditDocuments.hash(n);}
 private Receipt result(String ref,String outcome,Persistence persisted,JsonNode n,boolean existing){return new Receipt(ref,outcome,persisted,n,existing);}
 private ResourceLockCoordinator.Scope lock(Connection c,String ref)throws SQLException {var s=new ResourceLockCoordinator(courseUc22Narrow).scope(c);s.command(ref);return s;}
 private void valid(List<AuditStore.Event> events,Ticket t){
  if(events.isEmpty()||events.getFirst().sequence()!=1||!events.getFirst().kind().equals("START"))throw new IllegalArgumentException("UNVERIFIABLE_START");
  int last=0,terminals=0;for(var e:events){if(e.sequence()<=last||!e.operation().equals(t.command.operation()))throw new IllegalArgumentException("UNVERIFIABLE_SEQUENCE");last=e.sequence();if(e.kind().equals("TERMINAL"))terminals++;if(!e.evidence().isObject())throw new IllegalArgumentException("UNVERIFIABLE_DOCUMENT");String hash=e.evidence().path(hq()?"requestPayloadHash":"canonicalPayloadHash").asText();if(!hash.equals(t.hash))throw new IllegalArgumentException("REQUEST_PAYLOAD_CONFLICT");if(hq()&&(!e.evidence().path("requestOwner").asText().equals(t.owner)||!e.evidence().path("executionToken").asText().equals(t.token)))throw new IllegalArgumentException("UNVERIFIABLE_OWNERSHIP");}
  if(!events.getFirst().actor().equals(t.owner)||terminals>1)throw new IllegalArgumentException("UNVERIFIABLE_HISTORY");
 }
 private AuditStore.Event terminal(List<AuditStore.Event> e){return e.stream().filter(x->x.kind().equals("TERMINAL")).findFirst().orElse(null);}
 private ObjectNode evidence(Ticket t,String kind,String phase,String outcome,AuditDocuments.Change change,String reason){
  var n=AuditDocuments.JSON.createObjectNode();n.put("schemaVersion",1);
  if(hq()){
   n.put("eventKind",kind);n.put("logicalEventId","assigned-at-append");n.put("requestPayloadHash",t.hash);n.put("requestOwner",t.owner);n.put("executionToken",t.token);n.put("phase",phase);
   n.set("before",change==null?NullNode.getInstance():change.before());n.set("after",change==null?NullNode.getInstance():change.after());n.set("verification",change==null?AuditDocuments.JSON.createObjectNode():change.verification());n.putArray("relatedEventIds");
   if(kind.equals("OBSERVATION")){var observation=n.putObject("observation");observation.put("reason",reason==null?phase:reason);if(phase.equals("DUPLICATE_MISMATCH")){observation.put("originalPayloadHash",t.hash);observation.put("attemptedPayloadHash",payloadHash(t.command));}}else n.putNull("observation");
  }else{
   n.put("eventPhase",kind.equals("START")?"REQUEST_STARTED":kind.equals("OBSERVATION")?"RECOVERY_OBSERVATION":"BUSINESS_RESULT");n.put("operationKind",t.command.operation());n.put("canonicalPayloadHash",t.hash);n.put("confirmedCommit",outcome.equals("SUCCEEDED")?"YES":outcome.equals("UNKNOWN")||kind.equals("START")?"UNKNOWN":"NO");
   if(change==null)n.putNull("businessResultSnapshot");else {var snapshot=n.putObject("businessResultSnapshot");snapshot.set("before",change.before());snapshot.set("after",change.after());snapshot.set("businessReference",change.businessReference());snapshot.set("verification",change.verification());}
   if(reason==null)n.putNull("observationReason");else if(phase.equals("DUPLICATE_MISMATCH")){var detail=AuditDocuments.JSON.createObjectNode().put("reason",reason).put("originalPayloadHash",t.hash).put("attemptedPayloadHash",payloadHash(t.command));n.put("observationReason",detail.toString());}else n.put("observationReason",reason);
  }return n;
 }
 private JsonNode savedChange(AuditStore.Event event){if(hq()){var n=AuditDocuments.JSON.createObjectNode();n.set("before",event.evidence().get("before"));n.set("after",event.evidence().get("after"));n.set("verification",event.evidence().get("verification"));return n;}return event.evidence().get("businessResultSnapshot");}
 private AuditStore.Event append(Connection c,Ticket t,StaffAuthorization.Context actor,String kind,String phase,String outcome,String reason,AuditDocuments.Change change)throws SQLException {
  return store.append(c,t.reference,t.command.operation(),t.command.ucCode(),actor,outcome,reason,change==null?null:change.businessReference(),evidence(t,kind,phase,outcome,change,reason),StrictInputPolicy.eventTime(clock));
 }
 public Ticket begin(Command command){
  var actor=context(command);String ref=scopedReference(actor.actor(),command),hash=payloadHash(command);
  final boolean[] mismatch={false};try{return tx.execute(c->{lock(c,ref);var existing=store.read(c,ref);if(!existing.isEmpty()){
    var start=existing.getFirst();String originalHash=start.evidence().path(hq()?"requestPayloadHash":"canonicalPayloadHash").asText();String token=hq()?start.evidence().path("executionToken").asText():start.id();
    var ticket=new Ticket(command,ref,actor.actor(),originalHash,token,false);valid(existing,ticket);
    if(!originalHash.equals(hash)){mismatch[0]=true;var observer=authorizationSnapshot(command,actor);append(c,ticket,observer,"OBSERVATION","DUPLICATE_MISMATCH","OBSERVED","SAME_REQUEST_DIFFERENT_PAYLOAD",null);return new Ticket(command,ref,actor.actor(),hash,"CONFLICT",false);}return ticket;
   }
   var ticket=new Ticket(command,ref,actor.actor(),hash,UUID.randomUUID().toString(),true);var start=store.append(c,ref,command.operation(),command.ucCode(),actor,"STARTED",null,null,evidence(ticket,"START","START","STARTED",null,null),StrictInputPolicy.eventTime(clock));
   var answer=hq()?ticket:new Ticket(command,ref,actor.actor(),hash,start.id(),true);answer.lastContext=actor;return answer;
  });}catch(JdbcTransactions.Failure e){throw new Unavailable(unavailableResult(ref,mismatch[0]?"REJECTED":"UNKNOWN",e));}
 }
 public Receipt execute(Command command,Work work){try{return perform(begin(command),work);}catch(Unavailable e){return e.receipt();}}
 /** Once an original UC21 attempt is uncertain, inspect its journal without invoking Work again. */
 public Receipt executeUc21(Command command,Work work){
  if(hq()||!"UC21".equals(command.ucCode()))throw new IllegalArgumentException("UC21_COMMAND_REQUIRED");
  var attempt=execute(command,work);
  if(!"UNKNOWN".equals(attempt.businessOutcome())||attempt.auditPersistence()==Persistence.NOT_PERSISTED)return attempt;
  try{
   var observed=recover(command,false);
   return "UNKNOWN".equals(observed.businessOutcome())?attempt:observed;
  }catch(SecurityException e){
   return new Receipt(attempt.requestReference(),"REJECTED",Persistence.NOT_PERSISTED,null,true,null,"RECOVERY_PERMISSION_DENIED");
  }catch(RuntimeException e){return attempt;}
 }
 public Receipt perform(Ticket ticket,Work work){
  if(ticket.token.equals("CONFLICT"))return rejection(ticket.command,ticket.reference,Persistence.CONFIRMED,"REQUEST_PAYLOAD_CONFLICT");
  if(!ticket.fresh)return recover(ticket.command,false);
  try{return tx.execute(c->{var locks=lock(c,ticket.reference);var events=store.read(c,ticket.reference);valid(events,ticket);var current=context(ticket.command);ticket.lastContext=current;if(!current.actor().equals(ticket.owner)||!current.allowed())throw new SecurityException("EXECUTION_PERMISSION_REVOKED");
   var done=terminal(events);if(done!=null)return known(c,ticket,done,true);if(hq())locks.catalog(true);
   current=context(ticket.command);ticket.lastContext=current;if(!current.actor().equals(ticket.owner)||!current.allowed())throw new SecurityException("EXECUTION_PERMISSION_REVOKED");
   AuditDocuments.Change change=Objects.requireNonNull(work.run(c,locks));if(hq())AuditDocuments.headquartersOperation(ticket.command.operation(),change,ticket.reference,ticket.hash,current.actor());
   // Permission refresh immediately before success persistence too, not just at request start.
   current=context(ticket.command);ticket.lastContext=current;if(!current.actor().equals(ticket.owner)||!current.allowed())throw new SecurityException("EXECUTION_PERMISSION_REVOKED");
   var event=append(c,ticket,current,"TERMINAL",hq()?(ticket.command.operation().equals("DURATION_PUBLISH")?"PUBLISH_SUCCESS":"DRAFT_EDIT_SUCCESS"):"BUSINESS_RESULT","SUCCEEDED",null,change);
   return new Receipt(ticket.reference,"SUCCEEDED",Persistence.CONFIRMED,change.after(),false,event.id(),null);
  });}catch(JdbcTransactions.Failure e){if(e.unknown())return unavailableResult(ticket.reference,"UNKNOWN",e);return recordFailure(ticket,"FAILED","ROLLED_BACK","SQLSTATE="+e.sqlState());}
  catch(SecurityException e){if(ticket.lastContext!=null&&ticket.lastContext.allowed()){var x=ticket.lastContext;ticket.lastContext=new StaffAuthorization.Context(x.actor(),x.policyVersion(),x.action(),x.clinicIds(),false,x.globalCatalog(),x.roles());}return recordFailure(ticket,"REJECTED","DENIED","EXECUTION_PERMISSION_DENIED");}
  catch(IllegalArgumentException e){return recordFailure(ticket,"REJECTED","VALIDATION_REJECTED",e instanceof Rejection r?r.code:"VALIDATION_REJECTED");}
 }
 private Receipt rejection(Command command,String ref,Persistence persisted,String reason){
  return new Receipt(ref,"REJECTED",persisted,null,true,null,("UC02".equals(command.ucCode())||"UC03".equals(command.ucCode())||"UC04".equals(command.ucCode())||"UC05".equals(command.ucCode())||"UC06".equals(command.ucCode()))?reason:null);
 }
 private Receipt unavailableResult(String ref,String outcome,JdbcTransactions.Failure failure){
  return new Receipt(ref,outcome,failure.unknown()?Persistence.UNKNOWN:Persistence.NOT_PERSISTED,
          null,false,null,failure.unknown()&&!failure.commitAttempted()?"WORK_ROLLBACK_UNCONFIRMED":null);
 }
 private Receipt recordFailure(Ticket ticket,String outcome,String phase,String reason){
  try{return tx.execute(c->{lock(c,ticket.reference);var rows=store.read(c,ticket.reference);valid(rows,ticket);var current=Objects.requireNonNull(ticket.lastContext);var prior=terminal(rows);if(prior!=null){append(c,ticket,current,"OBSERVATION","FAILURE_DETAIL_ADDED","OBSERVED",reason,null);if(!context(ticket.command).allowed())return result(ticket.reference,"REJECTED",Persistence.CONFIRMED,null,true);return known(c,ticket,prior,true);}
   append(c,ticket,current,"TERMINAL",phase,outcome,reason,null);return new Receipt(ticket.reference,outcome,Persistence.CONFIRMED,null,false,null,reason);
  });}catch(JdbcTransactions.Failure e){return new Receipt(ticket.reference,outcome,e.unknown()?Persistence.UNKNOWN:Persistence.NOT_PERSISTED,null,false,null,reason);}catch(RuntimeException e){return new Receipt(ticket.reference,outcome,Persistence.NOT_PERSISTED,null,false,null,reason);}
 }
 private Receipt known(Connection c,Ticket ticket,AuditStore.Event event,boolean existing)throws SQLException {
  if(event.outcome().equals("SUCCEEDED")){var saved=savedChange(event);
   if(saved==null||!event.actor().equals(ticket.owner)
           ||(("UC21".equals(ticket.command.ucCode())||"UC01".equals(ticket.command.ucCode())||"UC02".equals(ticket.command.ucCode())||"UC03".equals(ticket.command.ucCode())||"UC04".equals(ticket.command.ucCode())||"UC05".equals(ticket.command.ucCode())||"UC06".equals(ticket.command.ucCode())||"UC08".equals(ticket.command.ucCode())||"UC09".equals(ticket.command.ucCode())||"UC10".equals(ticket.command.ucCode())||"UC11".equals(ticket.command.ucCode())||"UC12".equals(ticket.command.ucCode())||"UC13".equals(ticket.command.ucCode())||"UC14".equals(ticket.command.ucCode())||"UC15".equals(ticket.command.ucCode())||"UC16".equals(ticket.command.ucCode())||"UC17".equals(ticket.command.ucCode())||"UC18".equals(ticket.command.ucCode())||"UC19".equals(ticket.command.ucCode())||"UC20".equals(ticket.command.ucCode()))&&!"YES".equals(event.evidence().path("confirmedCommit").asText()))
           ||!verifier.matchesCommitted(c,ticket.command,saved))return result(ticket.reference,"UNKNOWN",Persistence.CONFIRMED,null,existing);
   return new Receipt(ticket.reference,"SUCCEEDED",Persistence.CONFIRMED,saved.get("after"),existing,event.id(),null);}
  return new Receipt(ticket.reference,event.outcome(),Persistence.CONFIRMED,null,existing,null,event.evidence().path("observationReason").asText(null));
 }
 private static final class RecoveryDenied extends SecurityException {
  final StaffAuthorization.Context snapshot;
  RecoveryDenied(StaffAuthorization.Context snapshot){super("RECOVERY_READ_DENIED");this.snapshot=snapshot;}
 }
 private StaffAuthorization.Context authorizationSnapshot(Command command,StaffAuthorization.Context initial){
  StaffAuthorization.Context now;
  try{now=context(command);}catch(SecurityException e){return new StaffAuthorization.Context(initial.actor(),"IDENTITY_UNAVAILABLE",action(command),command.clinics(),false,false,Set.of());}
  if(!now.actor().equals(initial.actor()))return new StaffAuthorization.Context(now.actor(),now.policyVersion(),now.action(),now.clinicIds(),false,now.globalCatalog(),now.roles());
  return now;
 }
 private StaffAuthorization.Context recoveryCheck(Command command,StaffAuthorization.Context initial){var now=authorizationSnapshot(command,initial);if(!now.allowed())throw new RecoveryDenied(now);return now;}
 private Ticket recoveryTicket(Command command,String ref,String owner,List<AuditStore.Event> rows){
  var first=rows.getFirst();String token=hq()?first.evidence().path("executionToken").asText():first.id();String hash=first.evidence().path(hq()?"requestPayloadHash":"canonicalPayloadHash").asText();var t=new Ticket(command,ref,owner,hash,token,false);valid(rows,t);return t;
 }
 /** The rejected read transaction has ended before this independent T0/T2 path. */
 private Receipt recoveryDenial(Command command,StaffAuthorization.Context initial,StaffAuthorization.Context denied){return recoveryDenial(command,initial,denied,true);}
 private Receipt recoveryDenial(Command command,StaffAuthorization.Context initial,StaffAuthorization.Context denied,boolean comparePayload){
  String ref=scopedReference(initial.actor(),command);
  try{
   Ticket ticket=tx.execute(c->{lock(c,ref);var rows=store.read(c,ref);if(!rows.isEmpty())return recoveryTicket(command,ref,initial.actor(),rows);
    var t=new Ticket(command,ref,initial.actor(),payloadHash(command),UUID.randomUUID().toString(),false);var startActor=denied.actor().equals(initial.actor())?denied:new StaffAuthorization.Context(initial.actor(),denied.policyVersion(),initial.action(),initial.clinicIds(),false,false,Set.of());var first=store.append(c,ref,command.operation(),command.ucCode(),startActor,"STARTED",null,null,evidence(t,"START","START","STARTED",null,null),StrictInputPolicy.eventTime(clock));return hq()?t:new Ticket(command,ref,initial.actor(),t.hash,first.id(),false);
   });
   return tx.execute(c->{lock(c,ref);var rows=store.read(c,ref);valid(rows,ticket);if(comparePayload&&!ticket.hash.equals(payloadHash(command)))append(c,ticket,denied,"OBSERVATION","DUPLICATE_MISMATCH","OBSERVED","SAME_REQUEST_DIFFERENT_PAYLOAD",null);
    if(terminal(rows)==null)append(c,ticket,denied,"TERMINAL","DENIED","REJECTED","RECOVERY_PERMISSION_DENIED",null);
    else append(c,ticket,denied,"OBSERVATION","FAILURE_DETAIL_ADDED","OBSERVED","RECOVERY_PERMISSION_DENIED",null);
    // This attempt was denied. A later regrant must not expose an earlier success to it.
    return new Receipt(ref,"REJECTED",Persistence.CONFIRMED,null,true,null,"RECOVERY_PERMISSION_DENIED");
   });
  }catch(JdbcTransactions.Failure e){return rejection(command,ref,e.unknown()?Persistence.UNKNOWN:Persistence.NOT_PERSISTED,"RECOVERY_PERMISSION_DENIED");}catch(IllegalArgumentException e){return rejection(command,ref,Persistence.NOT_PERSISTED,"RECOVERY_PERMISSION_DENIED");}
 }
 public Receipt recover(Command command,boolean closeIfNoCommit){
  return recover(command,closeIfNoCommit,true);
 }
 /** Owner-authorized HQ query only. No payload reconstruction, Work, replay or closure. */
 public Receipt recoverReference(String reference){
  if(!hq()||reference==null)throw new IllegalArgumentException("HQ_REFERENCE_REQUIRED");var parts=reference.split("/",-1);if(parts.length!=5||!parts[0].equals("HQCFG")||!parts[1].equals("v1")||!parts[2].matches("[a-f0-9]{32}")||!HQ.contains(parts[3]))throw new IllegalArgumentException("INVALID_REFERENCE");var command=new Command(parts[3],null,UUID.fromString(parts[4]),AuditDocuments.JSON.createObjectNode(),Set.of());if(!reference(command).equals(reference))throw new SecurityException("REQUEST_OWNER_REQUIRED");return recover(command,false,false);
 }
 private Receipt recover(Command command,boolean closeIfNoCommit,boolean comparePayload){
  var initial=context(command);String ref=scopedReference(initial.actor(),command);if(!initial.allowed())return recoveryDenial(command,initial,initial,comparePayload);
  final Receipt[] observedBusiness={null};final boolean[] mismatch={false};try{return tx.execute(c->{lock(c,ref);var current=recoveryCheck(command,initial);var rows=store.read(c,ref);current=recoveryCheck(command,initial);if(rows.isEmpty())return result(ref,"UNKNOWN",Persistence.NOT_PERSISTED,null,true);
   var t=recoveryTicket(command,ref,initial.actor(),rows);
   if(comparePayload&&!t.hash.equals(payloadHash(command))){mismatch[0]=true;append(c,t,current,"OBSERVATION","DUPLICATE_MISMATCH","OBSERVED","SAME_REQUEST_DIFFERENT_PAYLOAD",null);return rejection(command,ref,Persistence.CONFIRMED,"REQUEST_PAYLOAD_CONFLICT");}
   var done=terminal(rows);if(done!=null){var verified=known(c,t,done,true);current=recoveryCheck(command,initial);if(!verified.businessOutcome().equals("UNKNOWN"))observedBusiness[0]=verified;append(c,t,current,"OBSERVATION",verified.businessOutcome().equals("SUCCEEDED")?"RECOVERY_CONFIRMED_SUCCESS":"DUPLICATE_SAME","OBSERVED","RECOVERY_CHECK",null);return verified;}
   boolean close=closeIfNoCommit&&hq()&&!verifier.hasContradictorySuccess(c,command,ref);current=recoveryCheck(command,initial);
   if(close){append(c,t,current,"TERMINAL","RECOVERY_CLOSED_NO_COMMIT","REJECTED","CLOSED_WITH_NO_SUCCESS_COMMIT_NOT_EXECUTION_FAILURE",null);return result(ref,"REJECTED",Persistence.CONFIRMED,null,true);}
   append(c,t,current,"OBSERVATION","COMMIT_UNKNOWN","UNKNOWN","NO_VERIFIED_TERMINAL_NO_REPLAY",null);return result(ref,"UNKNOWN",Persistence.CONFIRMED,null,true);
  });}catch(RecoveryDenied e){return recoveryDenial(command,initial,e.snapshot,comparePayload);}catch(JdbcTransactions.Failure e){var saved=observedBusiness[0];return new Receipt(ref,mismatch[0]?"REJECTED":saved==null?"UNKNOWN":saved.businessOutcome(),e.unknown()?Persistence.UNKNOWN:Persistence.NOT_PERSISTED,saved==null?null:saved.result(),true,saved==null?null:saved.successEventId(),saved==null?null:saved.reason());}catch(IllegalArgumentException e){return result(ref,"UNKNOWN",Persistence.NOT_PERSISTED,null,true);}
 }
}
