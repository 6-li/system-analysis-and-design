package cn.adc.shared.time;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** UT-SCH-005/006/007/008/024: actual strict input and timezone foundation. */
class ClinicTimePolicyTest {
 private final ObjectMapper json=new ObjectMapper();
 @Test void rejectsFractionalTextZeroNegativeAndOverflowMinutes() throws Exception {
  for(String s:List.of("0","-1","1.5","1.0","1e0","\"30\"","2147483648","null","true"))assertThrows(IllegalArgumentException.class,()->StrictInputPolicy.positiveMinutes(json.readTree(s)),s);
  assertEquals(1,StrictInputPolicy.positiveMinutes(json.readTree("1")));
  assertEquals(Integer.MAX_VALUE,StrictInputPolicy.positiveMinutes(json.readTree("2147483647")));
 }
 @Test void preservesMicrosecondsAndRejectsRounding() {
  assertEquals(Instant.parse("2026-11-06T02:00:00.123456Z"),StrictInputPolicy.instant("2026-11-06T10:00:00.123456000+08:00"));
  assertThrows(IllegalArgumentException.class,()->StrictInputPolicy.instant("2026-11-06T02:00:00.1234567Z"));
  assertThrows(IllegalArgumentException.class,()->StrictInputPolicy.instant("2026-11-06T02:00:00"));
 }
 @Test void missingZoneAndInvalidMappingsFailClosed() {
  assertThrows(IllegalArgumentException.class,()->new ClinicTimePolicy("v",Map.of(1L,"+08:00")));
  assertThrows(IllegalArgumentException.class,()->new ClinicTimePolicy("",Map.of()));
  assertThrows(IllegalArgumentException.class,()->new ClinicTimePolicy("v",Map.of()).resolve(1,"2026-11-06T10:00:00",null,30));
 }
 @Test void dstGapRejectedOverlapRequiresExplicitLegalOffset() {
  var p=new ClinicTimePolicy("synthetic-v1",Map.of(1L,"America/New_York"));
  assertThrows(IllegalArgumentException.class,()->p.resolve(1,"2026-03-08T02:30:00",null,30));
  assertThrows(IllegalArgumentException.class,()->p.resolve(1,"2026-11-01T01:30:00",null,30));
  assertThrows(IllegalArgumentException.class,()->p.resolve(1,"2026-11-01T01:30:00",ZoneOffset.ofHours(8),30));
  var a=p.resolve(1,"2026-11-01T01:30:00",ZoneOffset.ofHours(-4),90);
  var b=p.resolve(1,"2026-11-01T01:30:00",ZoneOffset.ofHours(-5),90);
  assertEquals(Duration.ofHours(1),Duration.between(a.start(),b.start()));
  assertEquals(Duration.ofMinutes(90),Duration.between(a.start(),a.end()));
  assertEquals("America/New_York",a.zoneId());assertFalse(a.tzdbVersion().isBlank());
 }
 @Test void crossClinicAbsoluteComparisonAndNormalOffsetValidation() {
  var p=new ClinicTimePolicy("v",Map.of(1L,"Asia/Shanghai",2L,"America/New_York"));
  var a=p.resolve(1,"2026-11-06T15:00:00",ZoneOffset.ofHours(8),30);
  var b=p.resolve(2,"2026-11-06T02:00:00",ZoneOffset.ofHours(-5),30);
  assertEquals(a.start(),b.start());
  assertThrows(IllegalArgumentException.class,()->p.resolve(1,"2026-11-06T15:00:00",ZoneOffset.UTC,30));
 }
}
