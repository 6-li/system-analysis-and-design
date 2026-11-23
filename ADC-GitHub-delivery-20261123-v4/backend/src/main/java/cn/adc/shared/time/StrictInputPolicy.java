package cn.adc.shared.time;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.regex.*;

/** Strict boundaries before PostgreSQL/JDBC can round or coerce values. */
public final class StrictInputPolicy {
 private StrictInputPolicy() {}
 public static int positiveMinutes(JsonNode value) {
  if(value==null||!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<=0)throw new IllegalArgumentException("MINUTES_MUST_BE_POSITIVE_INTEGER");
  return value.intValue();
 }
 public static String microsecondText(String value) {
  if(value==null||!value.equals(value.strip()))throw new IllegalArgumentException("INVALID_TIME");
  var m=Pattern.compile("\\.(\\d+)").matcher(value);
  if(!m.find())return value;
  String fraction=m.group(1);if(fraction.length()>6){if(fraction.substring(6).chars().anyMatch(c->c!='0'))throw new IllegalArgumentException("TIME_PRECISION_EXCEEDS_MICROSECONDS");return value.substring(0,m.start(1))+fraction.substring(0,6)+value.substring(m.end(1));}return value;
 }
 public static Instant instant(String value) {
  try {return OffsetDateTime.parse(microsecondText(value)).toInstant();}catch(DateTimeException ex){throw new IllegalArgumentException("OFFSET_TIME_REQUIRED");}
 }
 public static LocalDateTime local(String value) {
  try{return LocalDateTime.parse(microsecondText(value));}catch(DateTimeException ex){throw new IllegalArgumentException("INVALID_LOCAL_TIME");}
 }
 public static Instant eventTime(Clock clock){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
}
