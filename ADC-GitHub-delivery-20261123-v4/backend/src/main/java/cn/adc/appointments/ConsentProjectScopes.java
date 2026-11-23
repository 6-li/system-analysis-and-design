package cn.adc.appointments;
import cn.adc.shared.audit.AuditDocuments;
import java.sql.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
/** An explicit project set, independent of the free-text conversation explanation. */
final class ConsentProjectScopes {
 record Project(String projectKey,String catalogVersion,String name,String unit){}
 final CourseClinicalCatalog catalog;
 ConsentProjectScopes(CourseClinicalCatalog catalog){this.catalog=catalog;}
 List<Project> selected(List<String> keys,String version){
  if(keys==null||keys.isEmpty()||keys.size()>100||!catalog.version().equals(version)||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("INVALID_CONSENT_PROJECTS");
  return keys.stream().sorted().map(key->{var item=catalog.requireKey(key);return new Project(key,version,item.name(),item.unit());}).toList();
 }
 void insert(Connection c,long consent,List<Project> projects)throws SQLException{
  try(var p=c.prepareStatement("INSERT INTO adc_course_source.consent_project_scope(consent_result_id,project_key,catalog_version,project_label_snapshot,unit_snapshot) VALUES(?,?,?,?,?)")){
   for(var item:projects){p.setLong(1,consent);p.setString(2,item.projectKey());p.setString(3,item.catalogVersion());p.setString(4,item.name());p.setString(5,item.unit());if(p.executeUpdate()!=1)throw new SQLException("CONSENT_SCOPE_UNCONFIRMED");}
  }
 }
 List<Project> read(Connection c,long consent)throws SQLException{
  var values=new ArrayList<Project>();
  try(var p=c.prepareStatement("SELECT project_key,catalog_version,project_label_snapshot,unit_snapshot FROM adc_course_source.consent_project_scope WHERE consent_result_id=? ORDER BY project_key")){
   p.setLong(1,consent);try(var r=p.executeQuery()){while(r.next())values.add(new Project(r.getString(1),r.getString(2),r.getString(3),r.getString(4)));}
  }return List.copyOf(values);
 }
 void require(Connection c,long consent,long visit,String key,boolean agreed,String unit)throws SQLException{
  catalog.requireKey(key);
  try(var p=c.prepareStatement("SELECT t.consent_result,s.unit_snapshot FROM adc.treatment_consent_result t JOIN adc_course_source.consent_project_scope s ON s.consent_result_id=t.consent_result_id WHERE t.consent_result_id=? AND t.visit_id=? AND s.project_key=?")){
   p.setLong(1,consent);p.setLong(2,visit);p.setString(3,key);try(var r=p.executeQuery()){
    if(!r.next()||agreed&&(!"同意".equals(r.getString(1))||!Objects.equals(unit,r.getString(2))))throw new cn.adc.shared.audit.CommandRecovery.Rejection("TREATMENT_CONSENT_REVIEW_REQUIRED");
   }
  }
 }
 static JsonNode document(List<Project> projects){var rows=AuditDocuments.JSON.createArrayNode();for(var p:projects)rows.addObject().put("projectKey",p.projectKey()).put("catalogVersion",p.catalogVersion()).put("name",p.name()).put("unit",p.unit());return rows;}
 static String hash(List<Project> projects){return AuditDocuments.hash(document(projects));}
}
