package cn.adc.quarterly;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static cn.adc.shared.audit.AuditDocuments.JSON;
/** Explicit versioned synthetic expense statements. Missing coverage is never a zero statement. */
final class QuarterExpenseCatalog {
 static final String LEGACY_ISSUE="SYNTHETIC expense-1 review required v1";
 static final String PREFIX="QUARTER_EXPENSE_V2:";
 record Correction(String amount,String evidence,String description){}
 record Expense(String quarter,long clinic,String manifest,String id,String version,Instant at,BigDecimal amount,String category,String basis,Correction correction){
  boolean legacy(){return "2026-Q4".equals(quarter)&&clinic==81001&&"classroom-expenses-2026Q4-v1".equals(manifest)&&"expense-1".equals(id)&&"1".equals(version)&&new BigDecimal("9.00").equals(amount)&&Instant.parse("2026-11-01T00:00:00Z").equals(at)&&"SYNTHETIC clinic utilities".equals(category)&&"SYNTHETIC classroom verified expense statement".equals(basis)&&correction!=null&&"8.50".equals(correction.amount())&&"SYNTHETIC-EXPENSE-CORRECTION-v1".equals(correction.evidence());}
  String factId(){return legacy()?id:"operating_expense/"+clinic+"/"+quarter+"/"+id;}
  String marker(){return legacy()?LEGACY_ISSUE:PREFIX+identity().toString();}
  tools.jackson.databind.node.ObjectNode identity(){return JSON.createObjectNode().put("quarter",quarter).put("clinicId",clinic).put("manifestVersion",manifest).put("factId",id).put("factVersion",version).put("amount",amount.toPlainString()).put("occurredAt",at.toString()).put("basis",basis);}
  String proof(){return identity().put("category",category).put("verifiedBasis",basis).put("includesConsumption",false).toString();}
 }
 record Group(String quarter,long clinic,String version,boolean complete,List<Expense> expenses){}
 private final Map<String,Group> groups;private final String version;
 private QuarterExpenseCatalog(Map<String,Group> groups,String version){this.groups=Map.copyOf(groups);this.version=version;}
 static QuarterExpenseCatalog read(Path path)throws java.io.IOException{
  if(Files.size(path)>1048576)throw new IllegalArgumentException("EXPENSE_SOURCE_TOO_LARGE");
  return parse(AuditDocuments.parse(Files.readString(path,java.nio.charset.StandardCharsets.UTF_8)));
 }
 static QuarterExpenseCatalog parse(JsonNode doc){
  if(!"SYNTHETIC".equals(AuditDocuments.text(doc,"classification")))throw new IllegalArgumentException("EXPENSE_CLASSIFICATION_REQUIRED");
  var groups=new LinkedHashMap<String,Group>();
  if(!doc.has("schemaVersion")){
   AuditDocuments.fields(doc,"classification","manifestVersion","quarter","clinicId","includesConsumption","expenses");
   Group g=group(doc,true);groups.put(key(g.quarter(),g.clinic()),g);
  }else{
   AuditDocuments.fields(doc,"classification","schemaVersion","manifestVersion","groups");
   if(!doc.path("schemaVersion").isIntegralNumber()||doc.path("schemaVersion").asInt()!=2||!doc.path("groups").isArray()||doc.path("groups").size()>1000||AuditDocuments.text(doc,"manifestVersion").isBlank())throw new IllegalArgumentException("EXPENSE_CATALOG_INVALID");
   for(var row:doc.path("groups")){
    AuditDocuments.fields(row,"quarter","clinicId","manifestVersion","complete","verifiedBasis","includesConsumption","expenses");
    Group g=group(row,false);if(groups.putIfAbsent(key(g.quarter(),g.clinic()),g)!=null)throw new IllegalArgumentException("DUPLICATE_EXPENSE_GROUP");
   }
  }
  return new QuarterExpenseCatalog(groups,AuditDocuments.hash(doc));
 }
 private static Group group(JsonNode row,boolean legacy){
  String quarter=AuditDocuments.text(row,"quarter"),manifest=AuditDocuments.text(row,"manifestVersion");var window=QuarterlyWindow.of(quarter);
  if(!row.path("clinicId").isIntegralNumber()||row.path("clinicId").asLong()<1||manifest.isBlank()||!row.path("includesConsumption").isBoolean()||row.path("includesConsumption").asBoolean()||!row.path("expenses").isArray()||row.path("expenses").size()>10000)throw new IllegalArgumentException("EXPENSE_GROUP_INVALID");
  if(!legacy&&(!row.path("complete").isBoolean()||row.path("complete").asBoolean()&&AuditDocuments.text(row,"verifiedBasis").isBlank()))throw new IllegalArgumentException("EXPENSE_COVERAGE_UNVERIFIED");
  if(legacy&&row.path("expenses").isEmpty())throw new IllegalArgumentException("EXPLICIT_ZERO_STATEMENT_REQUIRED");
  long clinic=row.path("clinicId").asLong();var seen=new HashSet<String>();var result=new ArrayList<Expense>();
  for(var item:row.path("expenses")){
   if(item.has("correction"))AuditDocuments.fields(item,"id","version","occurredAt","amount","category","verifiedBasis","correction");else AuditDocuments.fields(item,"id","version","occurredAt","amount","category","verifiedBasis");
   String id=AuditDocuments.text(item,"id"),ver=AuditDocuments.text(item,"version"),amount=AuditDocuments.text(item,"amount"),category=AuditDocuments.text(item,"category"),basis=AuditDocuments.text(item,"verifiedBasis");Instant at=StrictInputPolicy.instant(AuditDocuments.text(item,"occurredAt"));
   if(id.isBlank()||ver.isBlank()||category.isBlank()||basis.isBlank()||!seen.add(id)||!money(amount)||!window.contains(at))throw new IllegalArgumentException("EXPENSE_ENTRY_UNVERIFIED");
   Correction correction=null;
   if(item.has("correction")){
    var c=item.path("correction");AuditDocuments.fields(c,"amount","evidence","description");String value=AuditDocuments.text(c,"amount"),evidence=AuditDocuments.text(c,"evidence"),description=AuditDocuments.text(c,"description");if(!money(value)||evidence.isBlank()||description.isBlank())throw new IllegalArgumentException("EXPENSE_CORRECTION_UNVERIFIED");correction=new Correction(value,evidence,description);
   }else if(legacy&&clinic==81001&&"2026-Q4".equals(quarter)&&"classroom-expenses-2026Q4-v1".equals(manifest)&&"expense-1".equals(id)&&"1".equals(ver)&&"9.00".equals(amount))correction=new Correction("8.50","SYNTHETIC-EXPENSE-CORRECTION-v1","SYNTHETIC classroom check");
   result.add(new Expense(quarter,clinic,manifest,id,ver,at,new BigDecimal(amount),category,basis,correction));
  }
  return new Group(quarter,clinic,manifest,legacy||row.path("complete").asBoolean(),List.copyOf(result));
 }
 private static boolean money(String value){return value!=null&&value.matches("[0-9]{1,16}\\.[0-9]{2}");}
 private static String key(String quarter,long clinic){return quarter+"/"+clinic;}
 Group group(String quarter,long clinic){var g=groups.get(key(quarter,clinic));if(g==null||!g.complete())throw new IllegalArgumentException("EXPENSE_COVERAGE_MISSING");return g;}
 Optional<Expense> find(String marker,Long clinic){return groups.values().stream().filter(Group::complete).flatMap(g->g.expenses().stream()).filter(e->Objects.equals(clinic,e.clinic())&&e.marker().equals(marker)).findFirst();}
 String version(){return version;}
}
