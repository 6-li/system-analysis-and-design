package cn.adc.shared.audit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** UT-SCH-007/012/019/020/027/028: full content, not IDs masquerading as history. */
class AuditContractTest {
 @Test void revisionRequestStartMustNotBeClassifiedAsSuccessfulRevision(){var n=AuditDocuments.JSON.createObjectNode().put("operationKind","RESOLUTION_CREATE").put("eventPhase","REQUEST_STARTED");assertEquals("START",new AuditStore.Event("1",1,"a","RESOLUTION_CREATE","STARTED",n).kind());n.remove("eventPhase");assertEquals("TERMINAL",new AuditStore.Event("2",2,"a","RESOLUTION_CREATE","SUCCEEDED",n).kind());}
 final ObjectMapper json=new ObjectMapper();
 @Test void canonicalPayloadHashIgnoresObjectOrderNotArrayOrder() throws Exception {
  assertEquals(AuditDocuments.hash(json.readTree("{\"a\":1,\"b\":[2,3]}")),AuditDocuments.hash(json.readTree("{\"b\":[2,3],\"a\":1}")));
  assertNotEquals(AuditDocuments.hash(json.readTree("[2,3]")),AuditDocuments.hash(json.readTree("[3,2]")));
 }
 @Test void identifiersAndHashesDoNotReplaceWholeSnapshots() throws Exception {
  assertThrows(IllegalArgumentException.class,()->AuditDocuments.resolutionSnapshot(json.readTree("{\"resolution_id\":\"1\"}")));
  assertThrows(IllegalArgumentException.class,()->AuditDocuments.configurationSnapshot(json.readTree("{\"project_id\":\"1\",\"hash\":\"a\"}")));
 }
 @Test void fullConfigurationAndNullDraftPublicationArePreserved() {
  var s=json.createObjectNode();s.put("project_id",1);s.put("duration_version_no",1);s.put("project_name","synthetic");s.put("duration_minutes",30);s.put("publication_status","DRAFT");s.putNull("published_at");s.putNull("published_by");s.putNull("publication_evidence");
  assertDoesNotThrow(()->AuditDocuments.configurationSnapshot(s));s.put("duration_minutes",1.5);assertThrows(IllegalArgumentException.class,()->AuditDocuments.configurationSnapshot(s));
 }
 @Test void resolutionFullElevenFieldsRetainOriginalNulls() {
  var s=json.createObjectNode();s.put("resolution_id","1");s.put("schedule_id","2");s.put("schedule_version_no",1);s.put("appointment_id","3");s.put("resolution_subject","synthetic original");s.put("resolution_result","PENDING");s.putNull("resolution_basis");s.put("responsible_party","synthetic-manager");s.put("pending_confirmation","pending real acceptance");s.put("recorded_at","2026-11-05T00:00:00Z");s.putNull("appointment_change_id");
  assertDoesNotThrow(()->AuditDocuments.resolutionSnapshot(s));s.remove("pending_confirmation");assertThrows(IllegalArgumentException.class,()->AuditDocuments.resolutionSnapshot(s));
 }
 @Test void requestScopeIsServerDerivedAndBounded() {
  UUID key=UUID.randomUUID();assertNotEquals(AuditDocuments.requestReference("a","HQCFG","DURATION_PUBLISH",key),AuditDocuments.requestReference("b","HQCFG","DURATION_PUBLISH",key));
  assertTrue(AuditDocuments.requestReference("a","HQCFG","DURATION_PUBLISH",key).length()<=200);
 }
}
