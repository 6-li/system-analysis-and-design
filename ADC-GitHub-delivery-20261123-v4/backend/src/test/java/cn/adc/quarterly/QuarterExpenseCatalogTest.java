package cn.adc.quarterly;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.adc.shared.audit.AuditDocuments.JSON;
class QuarterExpenseCatalogTest {
 @TempDir Path dir;
 @Test void naturalQuartersHaveExplicitShanghaiHalfOpenBoundaries(){
  var q=QuarterlyWindow.of("2026-Q4");assertEquals(Instant.parse("2026-09-30T16:00:00Z"),q.start());assertEquals(Instant.parse("2026-12-31T16:00:00Z"),q.end());assertTrue(q.contains(q.start()));assertFalse(q.contains(q.end()));
  assertEquals(Duration.ofDays(91),Duration.between(QuarterlyWindow.of("2024-Q1").start(),QuarterlyWindow.of("2024-Q1").end()));
 }
 @Test void existingStatementAndReviewedHistoryIdentityRemainCompatible()throws Exception{
  var c=QuarterExpenseCatalog.read(Path.of("../tools/deploy/course-quarterly-expenses.json"));var e=c.group("2026-Q4",81001).expenses().getFirst();assertEquals(QuarterExpenseCatalog.LEGACY_ISSUE,e.marker());assertEquals("expense-1",e.factId());assertEquals("8.50",e.correction().amount());
 }
 @Test void catalogCoversTwoClinicsAndMultipleQuartersWithExplicitZeroStatements()throws Exception{
  var c=QuarterExpenseCatalog.read(Path.of("../tools/deploy/course-quarterly-expenses-v2.json"));assertEquals(1,c.group("2026-Q4",81001).expenses().size());assertTrue(c.group("2026-Q4",81002).expenses().isEmpty());assertTrue(c.group("2027-Q1",81001).expenses().isEmpty());assertThrows(IllegalArgumentException.class,()->c.group("2027-Q2",81001));
 }
 @Test void sourceIdentifiersAreNamespacedAndCorrectionValuesAreNotFixedToExample(){
  var root=JSON.createObjectNode().put("classification","SYNTHETIC").put("schemaVersion",2).put("manifestVersion","TEST v2");var groups=root.putArray("groups");
  for(long clinic:new long[]{81001,81002}){
   var g=groups.addObject().put("quarter","2027-Q1").put("clinicId",clinic).put("manifestVersion","TEST group").put("complete",true).put("verifiedBasis","TEST statement").put("includesConsumption",false);
   var e=g.putArray("expenses").addObject().put("id","same-id").put("version","1").put("occurredAt","2027-01-02T00:00:00Z").put("amount","15.00").put("category","TEST").put("verifiedBasis","TEST verified");e.putObject("correction").put("amount","12.75").put("evidence","TEST-cost-proof").put("description","TEST reviewed statement");
  }
  var c=QuarterExpenseCatalog.parse(root);var a=c.group("2027-Q1",81001).expenses().getFirst();var b=c.group("2027-Q1",81002).expenses().getFirst();assertNotEquals(a.factId(),b.factId());assertNotEquals(a.marker(),b.marker());assertEquals("12.75",a.correction().amount());assertTrue(c.find(a.marker(),81001L).isPresent());assertTrue(c.find(a.marker(),81002L).isEmpty());
 }
 @Test void uncoveredGroupIsNotConvertedIntoZero(){
  var root=JSON.createObjectNode().put("classification","SYNTHETIC").put("schemaVersion",2).put("manifestVersion","TEST");var g=root.putArray("groups").addObject().put("quarter","2027-Q1").put("clinicId",81001).put("manifestVersion","TEST").put("complete",false).put("verifiedBasis","TEST unresolved").put("includesConsumption",false);g.putArray("expenses");var c=QuarterExpenseCatalog.parse(root);assertThrows(IllegalArgumentException.class,()->c.group("2027-Q1",81001));
 }
}
