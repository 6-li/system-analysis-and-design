package cn.adc.appointments;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import java.nio.file.Path;
import java.sql.*;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
@org.springframework.stereotype.Component @Profile("course-demo")
@ConditionalOnProperty(name="adc.course.ui-unification-enabled",havingValue="true")
final class CourseProjectCatalog {
 final CourseClinicalCatalog catalog;final ConsentProjectScopes scopes;
 CourseProjectCatalog(Environment env)throws java.io.IOException{
  if(!"adc_course_validation".equals(env.getRequiredProperty("adc.course.database")))throw new IllegalArgumentException("COURSE_CATALOG_SCOPE_REQUIRED");
  catalog=new CourseClinicalCatalog(Path.of(env.getRequiredProperty("adc.course.clinical-catalog-v2")));
  for(String key:java.util.List.of("COURSE_EXAM","COURSE_CLEANING","COURSE_TREATMENT"))catalog.requireKey(key);
  scopes=new ConsentProjectScopes(catalog);
 }
 Optional<TreatmentInvoiceService.Price> price(Connection c,TreatmentInvoiceService.Fact fact,CourseClinicalCatalog legacy)throws SQLException{
  try(var p=c.prepareStatement("SELECT performed_project_key FROM adc.clinical_record_version WHERE clinical_record_id=? AND record_version_no=?")){
   p.setLong(1,fact.record());p.setInt(2,fact.version());try(var r=p.executeQuery()){if(!r.next())throw new SQLException("CLINICAL_VERSION_CHANGED","40001");String key=r.getString(1);return key==null?legacy.price(fact.item(),fact.unit()):catalog.priceByKey(key,fact.unit());}
  }
 }
}
