package cn.adc.quarterly;
import java.time.*;
/** Explicit clinic-local half-open natural quarter. Never uses the server default timezone. */
record QuarterlyWindow(Instant start,Instant end){
 static QuarterlyWindow of(String quarter){
  if(quarter==null||!quarter.matches("[0-9]{4}-Q[1-4]"))throw new IllegalArgumentException("INVALID_QUARTER");
  int year=Integer.parseInt(quarter.substring(0,4));if(year<1||year>9998)throw new IllegalArgumentException("INVALID_QUARTER_YEAR");
  int q=quarter.charAt(6)-'0';var day=LocalDate.of(year,(q-1)*3+1,1);var zone=ZoneId.of("Asia/Shanghai");
  return new QuarterlyWindow(day.atStartOfDay(zone).toInstant(),day.plusMonths(3).atStartOfDay(zone).toInstant());
 }
 boolean contains(Instant value){return value!=null&&!value.isBefore(start)&&value.isBefore(end);}
}
