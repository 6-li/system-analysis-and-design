package cn.adc.shared.audit;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;
import java.sql.*;
import java.time.Clock;
import java.util.Set;

/** Original table54 remains original UC01—22. Revision evidence is not business success authority. */
public final class UcAuditService {
 private final StaffAuthorization auth;private final Clock clock;private final AuditStore store;private final CommandRecovery commands;
 public UcAuditService(JdbcTransactions tx,StaffAuthorization auth,CommandRecovery.SuccessVerifier actualVerifier,Clock clock){this(tx,auth,actualVerifier,clock,null);}
 public UcAuditService(JdbcTransactions tx,StaffAuthorization auth,CommandRecovery.SuccessVerifier actualVerifier,Clock clock,AuditStore.EventIdAllocator ids){this(tx,auth,actualVerifier,clock,ids,false);}
 public UcAuditService(JdbcTransactions tx,StaffAuthorization auth,CommandRecovery.SuccessVerifier actualVerifier,Clock clock,AuditStore.EventIdAllocator ids,boolean courseUc22Narrow){this.auth=auth;this.clock=clock;store=new AuditStore(AuditStore.Family.UC,ids);commands=new CommandRecovery(tx,store,auth,actualVerifier,clock,courseUc22Narrow);}
 public CommandRecovery commands(){return commands;}
 public AuditStore.Event appendRevision(Connection c,ResourceLockCoordinator.Scope locks,Set<Long> clinics,JsonNode fullDocument)throws SQLException {
  var actor=auth.require("UC22",clinics);AuditDocuments.revision(fullDocument);String request=AuditDocuments.text(fullDocument,"requestReference");
  locks.requireCommand(c,request);var events=store.read(c,request);if(events.isEmpty()||!events.getFirst().kind().equals("START")||!events.getFirst().actor().equals(actor.actor())||!events.getFirst().evidence().path("canonicalPayloadHash").asText().equals(fullDocument.get("canonicalPayloadHash").asText())||events.stream().anyMatch(e->e.kind().equals("TERMINAL")))throw new IllegalArgumentException("REVISION_START_OR_REQUEST_MISMATCH");
  return store.append(c,request,fullDocument.get("operationKind").asText(),"UC22",actor,"SUCCEEDED",fullDocument.path("revisionReason").isNull()?null:fullDocument.path("revisionReason").asText(),fullDocument.get("targetDraftRef"),fullDocument,StrictInputPolicy.eventTime(clock));
 }
}
