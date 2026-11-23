package cn.adc.quarterly;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class QuarterlyMultiClinicSourceTest {
 @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler fn){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},fn);}
 static CourseQuarterlyConfiguration configuration(Path file)throws Exception{return new CourseQuarterlyConfiguration(new MockEnvironment().withProperty("adc.course.database","adc_course_validation").withProperty("adc.course.database-port","55432").withProperty("adc.course.clinic-id","81001").withProperty("adc.course.quarterly-expenses",file.toAbsolutePath().toString()).withProperty("ADC_COURSE_DB_QUARTERLY_PASSWORD","TEST only"));}
 static final class Data {
  final List<List<Object>> parameters=new ArrayList<>();
  Connection connection(){return proxy(Connection.class,(o,m,a)->{
   if(!m.getName().equals("prepareStatement"))throw new AssertionError("Unexpected connection operation "+m.getName());
   String sql=(String)a[0];assertTrue(sql.stripLeading().startsWith("SELECT "));var values=new HashMap<Integer,Object>();
   return proxy(PreparedStatement.class,(q,method,args)->{
    if(method.getName().startsWith("set")){values.put((Integer)args[0],args[1]);return null;}
    if(method.getName().equals("close"))return null;
    if(!method.getName().equals("executeQuery"))throw new AssertionError("Unexpected query operation "+method.getName());
    long clinic=(Long)values.get(1);Instant start=((OffsetDateTime)values.get(2)).toInstant(),end=((OffsetDateTime)values.get(3)).toInstant();parameters.add(List.of(clinic,start,end));
    var rows=new ArrayList<Object[]>();int period=0;
    for(String time:new String[]{"2026-11-02T00:00:00Z","2027-02-02T00:00:00Z"}){
     Instant at=Instant.parse(time);long id=clinic*100+(++period);if(at.isBefore(start)||!at.isBefore(end))continue;
     BigDecimal amount=new BigDecimal(period==1?(clinic==81001?"100.00":"200.00"):(clinic==81001?"70.00":"80.00"));var offset=at.atOffset(ZoneOffset.UTC);
     if(sql.contains("SELECT item.invoice_item_id"))rows.add(new Object[]{id,"1",offset,amount,79001L,true});
     else if(sql.contains("SELECT 'RECEIVABLE'")){rows.add(new Object[]{"RECEIVABLE","treatment_invoice/"+id,"1",offset,amount});rows.add(new Object[]{"RECEIVED","payment_transaction/"+id,"1",offset,amount});assertEquals(values.get(2),values.get(5));assertEquals(values.get(3),values.get(6));}
     else if(sql.contains("FROM adc.appointment a"))rows.add(new Object[]{id,offset,offset,"已到诊",true,false,false});
     else if(sql.contains("FROM adc.supply_consumption"))rows.add(new Object[]{id,offset,new BigDecimal(clinic==81001?"5.00":"6.00")});
     else throw new AssertionError("Unexpected SELECT "+sql);
    }
    int[] cursor={-1};return proxy(ResultSet.class,(r,operation,arg)->switch(operation.getName()){
     case "next"->++cursor[0]<rows.size();case "close"->null;
     case "getObject"->rows.get(cursor[0])[(Integer)arg[0]-1];
     case "getString"->String.valueOf(rows.get(cursor[0])[(Integer)arg[0]-1]);
     case "getLong"->((Number)rows.get(cursor[0])[(Integer)arg[0]-1]).longValue();
     case "getBoolean"->rows.get(cursor[0])[(Integer)arg[0]-1];
     case "getBigDecimal"->rows.get(cursor[0])[(Integer)arg[0]-1];default->throw new AssertionError(operation.getName());});
   });
  });}
 }
 @Test void realSourceAdapterAggregatesBothClinicsAndKeepsQuartersSeparate()throws Exception{
  var config=configuration(Path.of("../tools/deploy/course-quarterly-expenses-v2.json"));
  for(String quarter:new String[]{"2026-Q4","2027-Q1"}){
   var data=new Data();var manifest=config.source(false).resolve(data.connection(),new QuarterlyPreviewService.Target(quarter,Set.of(81001L,81002L)));var preview=QuarterlyPreviewService.summarize(manifest,Instant.parse("2027-05-01T00:00:00Z"));
   assertEquals("达到汇总要求",preview.values().path("readiness").asText());String total=quarter.equals("2026-Q4")?"300.00":"150.00";assertEquals(total,preview.values().path("receivable").asText());assertEquals(total,preview.values().path("received").asText());assertEquals(new BigDecimal(total),preview.performance().get(79001L));assertEquals(2,preview.values().path("due").asInt());assertEquals("11.00",preview.values().path("consumption").asText());assertEquals(quarter.equals("2026-Q4")?"9.00":"0.00",preview.values().path("expense").asText());
   var window=QuarterlyWindow.of(quarter);assertEquals(8,data.parameters.size());for(var parameters:data.parameters){assertEquals(window.start(),parameters.get(1));assertEquals(window.end(),parameters.get(2));}
   var frozen=new QuarterlyCalculationService.Frozen(quarter,"达到汇总要求","TEST explicit accrual basis",manifest.version(),preview.source().toString(),"TEST source",new BigDecimal(total),new BigDecimal(total),new BigDecimal(preview.values().path("expense").asText()),new BigDecimal("11.00"));
   var amounts=QuarterlyCalculationService.amounts(frozen,new QuarterlyCalculationService.Policy("TEST accrual",QuarterlyCalculationService.Revenue.RECEIVABLE,false,true));assertNull(amounts.pending());assertEquals(quarter.equals("2026-Q4")?"280.00":"139.00",amounts.net().toPlainString());assertEquals(quarter.equals("2026-Q4")?"28.00":"13.90",amounts.pool().toPlainString());
  }
 }
 @Test void genericApprovedCorrectionSourceIsScopedToItsFactAndQuarter()throws Exception{
  var root=cn.adc.shared.audit.AuditDocuments.JSON.createObjectNode().put("classification","SYNTHETIC").put("schemaVersion",2).put("manifestVersion","TEST corrections");
  var group=root.putArray("groups").addObject().put("quarter","2027-Q1").put("clinicId",81001).put("manifestVersion","TEST new quarter").put("complete",true).put("verifiedBasis","TEST statement").put("includesConsumption",false);
  var expense=group.putArray("expenses").addObject().put("id","utility-new").put("version","3").put("occurredAt","2027-01-15T00:00:00Z").put("amount","17.00").put("category","TEST utility").put("verifiedBasis","TEST original");expense.putObject("correction").put("amount","13.37").put("evidence","TEST-statement-proof").put("description","TEST independent check");
  var file=java.nio.file.Files.createTempFile("adc-quarter-correction-",".json");try{
   java.nio.file.Files.writeString(file,root.toString());var config=configuration(file);var catalog=QuarterExpenseCatalog.read(file);var entry=catalog.group("2027-Q1",81001).expenses().getFirst();
   var issue=new QuarterlyIssueService.Issue(81001L,entry.marker(),null,"待核实","SYNTHETIC-course-quarterly-v2/"+catalog.version()+"/review",null,null);var target=new QuarterlyIssueService.Target(1,2,Set.of(81001L));
   var source=((QuarterlyIssueController.StaffAccess)config.quarterlyIssue()).sources();var correction=source.verify(null,target,issue,"TEST-statement-proof","13.37");assertNotNull(correction);assertEquals("13.37",correction.value());
   var changed=new QuarterlyIssueService.Issue(81001L,entry.marker(),"13.37","待复核",issue.sourceVersion(),3L,null);assertTrue(source.recheck(null,target,changed,3).ready());
  }finally{java.nio.file.Files.deleteIfExists(file);}
 }
}
