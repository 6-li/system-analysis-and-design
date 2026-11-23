package cn.adc.shared.time;
import cn.adc.PgITSupport;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="ADC_P0_IT_ENABLED",matches="true")
class TimeRoundTripIT {
 @Test void jdbcOffsetDateTimeRoundTripsSameInstantAtMicrosecondBoundary()throws Exception {
  var input=StrictInputPolicy.instant("2026-11-06T10:00:00.123456+08:00");try(var c=PgITSupport.app().getConnection();var p=c.prepareStatement("SELECT ?::timestamptz(6)")){p.setObject(1,input.atOffset(ZoneOffset.ofHours(8)));try(var r=p.executeQuery()){r.next();assertEquals(input,r.getObject(1,OffsetDateTime.class).toInstant());}}
 }
 @Test void serverSessionZoneDoesNotBecomeBusinessZone()throws Exception {
  var policy=new ClinicTimePolicy("SYNTHETIC",java.util.Map.of(1L,"America/New_York"));assertThrows(IllegalArgumentException.class,()->policy.resolve(1,"2026-03-08T02:30:00",null,30));try(var c=PgITSupport.app().getConnection();var st=c.createStatement()){st.execute("SET TIME ZONE 'Asia/Tokyo'");try(var r=st.executeQuery("SELECT '2026-11-06T10:00:00.123456+08:00'::timestamptz")){r.next();assertEquals(Instant.parse("2026-11-06T02:00:00.123456Z"),r.getObject(1,OffsetDateTime.class).toInstant());}}
 }
}
