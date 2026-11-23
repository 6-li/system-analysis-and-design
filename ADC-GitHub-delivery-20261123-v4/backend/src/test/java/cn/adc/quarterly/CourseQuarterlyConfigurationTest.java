package cn.adc.quarterly;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import cn.adc.shared.security.StaffAuthorization;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CourseQuarterlyConfigurationTest {
 @TempDir Path dir;
 @Test void originalRolesRemainSeparate(){
  var b=CourseQuarterlyConfiguration.bindings();
  var op=new StaffAuthorization(()->Optional.of("SYNTHETIC operations"),b::bindings);
  var fin=new StaffAuthorization(()->Optional.of("SYNTHETIC finance"),b::bindings);
  assertDoesNotThrow(()->op.require("UC17",Set.of(81001L)));
  assertThrows(SecurityException.class,()->op.require("UC20",Set.of(81001L)));
  assertThrows(SecurityException.class,()->fin.require("UC19",Set.of(81001L)));
  assertDoesNotThrow(()->op.require("UC17",Set.of(81001L,81002L)));
  assertDoesNotThrow(()->fin.require("UC20",Set.of(81001L,81002L)));
  var manager=new StaffAuthorization(()->Optional.of("SYNTHETIC manager"),b::bindings);
  assertThrows(SecurityException.class,()->manager.require("UC18_CORRECTION",Set.of(81002L)));
 }
 @Test void explicitScopeAndExpenseManifestRequired()throws Exception{
  var file=dir.resolve("expenses.json");Files.writeString(file,"{}");
  var env=new MockEnvironment().withProperty("adc.course.database","adc_course_validation").withProperty("adc.course.database-port","55432").withProperty("adc.course.clinic-id","81001").withProperty("adc.course.quarterly-expenses",file.toString()).withProperty("ADC_COURSE_DB_QUARTERLY_PASSWORD","TEST-only");
  assertThrows(IllegalArgumentException.class,()->new CourseQuarterlyConfiguration(env));
  env.setProperty("adc.course.database-port","5432");assertThrows(IllegalArgumentException.class,()->new CourseQuarterlyConfiguration(env));
 }
}
