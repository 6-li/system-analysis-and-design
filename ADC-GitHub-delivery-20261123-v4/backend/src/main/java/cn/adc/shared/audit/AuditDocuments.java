package cn.adc.shared.audit;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import cn.adc.shared.time.StrictInputPolicy;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Controlled full-document validation. Cross-record/business truth remains a separate verifier. */
public final class AuditDocuments {
 public static final ObjectMapper JSON=new ObjectMapper();
 private AuditDocuments(){}
 public static JsonNode parse(String s){try{return JSON.readTree(s);}catch(Exception e){throw new IllegalArgumentException("INVALID_AUDIT_DOCUMENT");}}
 public static String hash(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalArgumentException("INVALID_PAYLOAD");}}
 private static JsonNode canonical(JsonNode n){if(n==null)throw new IllegalArgumentException("PAYLOAD_REQUIRED");if(n.isObject()){var out=JSON.createObjectNode();n.propertyNames().stream().sorted().forEach(k->out.set(k,canonical(n.get(k))));return out;}if(n.isArray()){var a=JSON.createArrayNode();n.forEach(v->a.add(canonical(v)));return a;}return n.deepCopy();}
 public static String requestReference(String actor,String family,String operation,UUID key){if(actor==null||actor.isBlank()||operation==null||!operation.matches("[A-Z0-9_]{1,40}")||!Set.of("HQCFG","UC").contains(family))throw new IllegalArgumentException("REQUEST_SCOPE_REQUIRED");return family+"/v1/"+hash(JSON.getNodeFactory().textNode(actor)).substring(0,32)+"/"+operation+"/"+Objects.requireNonNull(key);}
 public static void fields(JsonNode n,String... fields){if(n==null||!n.isObject()||!new HashSet<>(n.propertyNames()).equals(Set.of(fields)))throw new IllegalArgumentException("COMPLETE_CONTROLLED_DOCUMENT_REQUIRED");}
 public static String text(JsonNode n,String field){JsonNode v=n==null?null:n.get(field);if(v==null||!v.isString()||v.asText().isBlank())throw new IllegalArgumentException("DOCUMENT_TEXT_REQUIRED_"+field);return v.asText();}
 private static void nullableText(JsonNode n,String f){if(!n.get(f).isNull())text(n,f);}
 private static long id(JsonNode n,String f){String s=text(n,f);if(!s.matches("-?[0-9]+"))throw new IllegalArgumentException("INVALID_ID");try{return Long.parseLong(s);}catch(NumberFormatException e){throw new IllegalArgumentException("INVALID_ID");}}
 public static void resolutionSnapshot(JsonNode n){
  fields(n,"resolution_id","schedule_id","schedule_version_no","appointment_id","resolution_subject","resolution_result","resolution_basis","responsible_party","pending_confirmation","recorded_at","appointment_change_id");
  for(String f:List.of("resolution_id","schedule_id","appointment_id"))id(n,f);
  if(!n.get("schedule_version_no").isIntegralNumber()||!n.get("schedule_version_no").canConvertToInt())throw new IllegalArgumentException("INVALID_VERSION");
  for(String f:List.of("resolution_subject","resolution_result","responsible_party"))text(n,f);
  for(String f:List.of("resolution_basis","pending_confirmation"))nullableText(n,f);
  StrictInputPolicy.instant(text(n,"recorded_at"));if(!n.get("appointment_change_id").isNull())id(n,"appointment_change_id");
 }
 public static void configurationSnapshot(JsonNode n){
  fields(n,"project_id","duration_version_no","project_name","duration_minutes","publication_status","published_at","published_by","publication_evidence");
  if(!n.get("project_id").isIntegralNumber()||!n.get("project_id").canConvertToLong()||n.get("project_id").longValue()<=0)throw new IllegalArgumentException("INVALID_PROJECT");
  StrictInputPolicy.positiveMinutes(n.get("duration_version_no"));StrictInputPolicy.positiveMinutes(n.get("duration_minutes"));String projectName=text(n,"project_name");if(projectName.codePointCount(0,projectName.length())>200)throw new IllegalArgumentException("NAME_TOO_LONG");
  String state=text(n,"publication_status");if(!Set.of("DRAFT","CURRENT","SUPERSEDED").contains(state))throw new IllegalArgumentException("INVALID_PUBLICATION_STATE");
  if(state.equals("DRAFT")){if(!n.get("published_at").isNull()||!n.get("published_by").isNull()||!n.get("publication_evidence").isNull())throw new IllegalArgumentException("DRAFT_HAS_PUBLICATION");}
  else {StrictInputPolicy.instant(text(n,"published_at"));if(text(n,"published_by").length()>100||!n.get("publication_evidence").isObject())throw new IllegalArgumentException("PUBLICATION_EVIDENCE_REQUIRED");var p=n.get("publication_evidence");if(!p.path("schemaVersion").isIntegralNumber()||p.path("schemaVersion").intValue()!=1)throw new IllegalArgumentException("PUBLICATION_VERSION");if(!text(p,"requestReference").matches("[A-Za-z0-9_:/.-]{1,200}")||!text(p,"canonicalPayloadHash").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("PUBLICATION_REQUEST_REQUIRED");}
 }
 public static void headquartersChange(Change change){
  for(JsonNode envelope:List.of(change.before(),change.after())){
   fields(envelope,"targetVersion","previousCurrentVersion");for(String f:List.of("targetVersion","previousCurrentVersion"))if(!envelope.get(f).isNull())configurationSnapshot(envelope.get(f));
  }
  if(change.after().get("targetVersion").isNull()||change.businessReference()==null||!change.businessReference().isObject())throw new IllegalArgumentException("REAL_TARGET_REQUIRED");
  var ref=change.businessReference();long project;try{project=Long.parseLong(text(ref,"projectId"));}catch(NumberFormatException e){throw new IllegalArgumentException("INVALID_PROJECT");}
  if(project<=0||!ref.path("versionNo").isIntegralNumber()||!ref.path("versionNo").canConvertToInt())throw new IllegalArgumentException("INVALID_VERSION_REFERENCE");
  var after=change.after().get("targetVersion");if(after.get("project_id").longValue()!=project||after.get("duration_version_no").intValue()!=ref.get("versionNo").intValue())throw new IllegalArgumentException("TARGET_REFERENCE_MISMATCH");
 }
 public static void headquartersOperation(String operation,Change change,String request,String payloadHash,String actor){
  headquartersChange(change);var before=change.before().get("targetVersion");var after=change.after().get("targetVersion");
  if(operation.equals("DURATION_DRAFT_CREATE")){if(!before.isNull()||!after.get("publication_status").asText().equals("DRAFT"))throw new IllegalArgumentException("CREATE_DRAFT_DOCUMENT_MISMATCH");}
  else {
   if(before.isNull()||!before.get("publication_status").asText().equals("DRAFT")||before.get("project_id").longValue()!=after.get("project_id").longValue()||before.get("duration_version_no").intValue()!=after.get("duration_version_no").intValue())throw new IllegalArgumentException("IMMUTABLE_VERSION_OR_DRAFT_MISMATCH");
   if(operation.equals("DURATION_DRAFT_EDIT")){if(!after.get("publication_status").asText().equals("DRAFT"))throw new IllegalArgumentException("EDIT_CANNOT_PUBLISH");}
   else if(operation.equals("DURATION_PUBLISH")){
    if(!after.get("publication_status").asText().equals("CURRENT")||!after.get("published_by").asText().equals(actor))throw new IllegalArgumentException("PUBLICATION_DOCUMENT_MISMATCH");
    var evidence=after.get("publication_evidence");fields(evidence,"schemaVersion","operationKind","requestReference","canonicalPayloadHash","actorReference","authorizationScope","reason","expectedDraftFingerprint","previousCurrentRef","before","after","recordedAt");
    if(!text(evidence,"operationKind").equals("DURATION_PUBLISH")||!text(evidence,"requestReference").equals(request)||!text(evidence,"canonicalPayloadHash").equals(payloadHash)||!text(evidence,"actorReference").equals(actor)||!text(evidence,"expectedDraftFingerprint").equals(hash(before)))throw new IllegalArgumentException("PUBLICATION_REQUEST_MISMATCH");
    text(evidence,"authorizationScope");text(evidence,"reason");StrictInputPolicy.instant(text(evidence,"recordedAt"));
    fields(evidence.get("after"),"projectId","versionNo","projectName","durationMinutes","publicationStatus","publishedAt","publishedBy");
    if(!text(evidence.get("after"),"projectId").equals(after.get("project_id").asText())||evidence.get("after").path("versionNo").intValue()!=after.get("duration_version_no").intValue()||!text(evidence.get("after"),"projectName").equals(after.get("project_name").asText())||evidence.get("after").path("durationMinutes").intValue()!=after.get("duration_minutes").intValue()||!text(evidence.get("after"),"publicationStatus").equals("CURRENT")||!text(evidence.get("after"),"publishedBy").equals(actor)||!StrictInputPolicy.instant(text(evidence.get("after"),"publishedAt")).equals(StrictInputPolicy.instant(text(after,"published_at"))))throw new IllegalArgumentException("PUBLISHED_CONTENT_MISMATCH");
    var old=change.before().get("previousCurrentVersion");var superseded=change.after().get("previousCurrentVersion");
    if(old.isNull()){if(!superseded.isNull()||!evidence.get("previousCurrentRef").isNull()||!evidence.get("before").isNull())throw new IllegalArgumentException("INITIAL_PUBLICATION_HISTORY_MISMATCH");}
    else {if(superseded.isNull()||!old.get("publication_status").asText().equals("CURRENT")||!superseded.get("publication_status").asText().equals("SUPERSEDED"))throw new IllegalArgumentException("SUPERSEDED_HISTORY_REQUIRED");var x=(ObjectNode)old.deepCopy();var y=(ObjectNode)superseded.deepCopy();x.remove("publication_status");y.remove("publication_status");if(!x.equals(y))throw new IllegalArgumentException("PUBLISHED_HISTORY_CHANGED");}
   }else throw new IllegalArgumentException("UNAPPROVED_OPERATION");
  }
 }
 public static void revision(JsonNode n){
  fields(n,"schemaVersion","operationKind","requestReference","canonicalPayloadHash","targetDraftRef","rootResolutionId","predecessorResolutionId","newResolutionId","revisionReason","before","after","verification");
  if(n.path("schemaVersion").intValue()!=1||!text(n,"canonicalPayloadHash").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("REVISION_METADATA_REQUIRED");
  String kind=text(n,"operationKind");if(!Set.of("RESOLUTION_CREATE","RESOLUTION_APPEND").contains(kind))throw new IllegalArgumentException("INVALID_REVISION_KIND");
  resolutionSnapshot(n.get("after"));if(kind.equals("RESOLUTION_CREATE")){if(!n.get("before").isNull()||!n.get("predecessorResolutionId").isNull())throw new IllegalArgumentException("ORIGINAL_HAS_PREDECESSOR");}
  else {resolutionSnapshot(n.get("before"));text(n,"revisionReason");if(!text(n.get("before"),"resolution_id").equals(text(n,"predecessorResolutionId")))throw new IllegalArgumentException("PREDECESSOR_MISMATCH");}
  if(!text(n,"newResolutionId").equals(text(n.get("after"),"resolution_id")))throw new IllegalArgumentException("NEW_ID_MISMATCH");
  var ref=n.get("targetDraftRef");if(!text(ref,"scheduleId").equals(text(n.get("after"),"schedule_id"))||ref.path("versionNo").intValue()!=n.get("after").get("schedule_version_no").intValue())throw new IllegalArgumentException("DRAFT_REFERENCE_MISMATCH");
  String root=text(n,"rootResolutionId");if(kind.equals("RESOLUTION_CREATE")&&!root.equals(text(n,"newResolutionId")))throw new IllegalArgumentException("ORIGINAL_ROOT_MISMATCH");
  if(kind.equals("RESOLUTION_APPEND")){for(String f:List.of("schedule_id","schedule_version_no","appointment_id"))if(!n.get("before").get(f).equals(n.get("after").get(f)))throw new IllegalArgumentException("REVISION_TARGET_CHANGED");if(text(n,"newResolutionId").equals(text(n,"predecessorResolutionId")))throw new IllegalArgumentException("REVISION_MUST_APPEND");}
  var v=n.get("verification");fields(v,"evaluatedAt","appointmentFingerprint","conclusion","formalEvidence","supportReasons","pendingReason");StrictInputPolicy.instant(text(v,"evaluatedAt"));if(!text(v,"appointmentFingerprint").matches("[a-f0-9]{64}")||!Set.of("VERIFIED","PENDING","STALE","INCONSISTENT").contains(text(v,"conclusion"))||!v.get("supportReasons").isArray())throw new IllegalArgumentException("VERIFICATION_REQUIRED");for(var reason:v.get("supportReasons"))if(!reason.isString()||reason.asText().isBlank())throw new IllegalArgumentException("SUPPORT_REASON_REQUIRED");nullableText(v,"pendingReason");if(!v.get("formalEvidence").isNull()&&!v.get("formalEvidence").isObject())throw new IllegalArgumentException("FORMAL_EVIDENCE_OBJECT_REQUIRED");
 }
 public record Change(JsonNode businessReference,JsonNode before,JsonNode after,JsonNode verification){
  public Change {businessReference=businessReference==null?NullNode.getInstance():businessReference.deepCopy();before=before==null?NullNode.getInstance():before.deepCopy();after=after==null?NullNode.getInstance():after.deepCopy();verification=verification==null?AuditDocuments.JSON.createObjectNode():verification.deepCopy();if(!verification.isObject())throw new IllegalArgumentException("VERIFICATION_REQUIRED");}
 }
}
