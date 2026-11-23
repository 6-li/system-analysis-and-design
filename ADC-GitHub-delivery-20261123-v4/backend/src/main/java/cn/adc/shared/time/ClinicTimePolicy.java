package cn.adc.shared.time;
import java.time.*;
import java.time.zone.ZoneRulesProvider;
import java.util.*;

/** Explicit versioned clinic IANA mapping. Never consults OS/JVM/client defaults. */
public final class ClinicTimePolicy {
 public record Resolved(Instant start,Instant end,String zoneId,String policyVersion,String tzdbVersion,
                        LocalDateTime localStart,LocalDateTime localEnd,ZoneOffset startOffset,ZoneOffset endOffset,String localEndSource){}
 private final String version;private final Map<Long,ZoneId> zones;
 public ClinicTimePolicy(String version,Map<Long,String> configuration) {
  if(version==null||version.isBlank())throw new IllegalArgumentException("ZONE_POLICY_VERSION_REQUIRED");this.version=version;
  var map=new HashMap<Long,ZoneId>();configuration.forEach((id,name)->{if(id==null||name==null||!ZoneId.getAvailableZoneIds().contains(name))throw new IllegalArgumentException("IANA_ZONE_REQUIRED");map.put(id,ZoneId.of(name));});zones=Map.copyOf(map);
 }
 public Resolved resolve(long clinicId,String localText,ZoneOffset selectedOffset,int minutes) {
  if(minutes<=0)throw new IllegalArgumentException("MINUTES_MUST_BE_POSITIVE_INTEGER");
  ZoneId zone=zones.get(clinicId);if(zone==null)throw new IllegalArgumentException("CLINIC_ZONE_MISSING");
  LocalDateTime local=StrictInputPolicy.local(localText);var offsets=zone.getRules().getValidOffsets(local);
  if(offsets.isEmpty())throw new IllegalArgumentException("DST_GAP");
  if(selectedOffset==null&&offsets.size()!=1)throw new IllegalArgumentException("DST_OFFSET_SELECTION_REQUIRED");
  ZoneOffset offset=selectedOffset==null?offsets.getFirst():selectedOffset;if(!offsets.contains(offset))throw new IllegalArgumentException("OFFSET_NOT_VALID_FOR_CLINIC");
  Instant start=local.toInstant(offset),end;try{end=start.plusSeconds(Math.multiplyExact((long)minutes,60));}catch(ArithmeticException|DateTimeException ex){throw new IllegalArgumentException("TIME_RANGE_OVERFLOW");}
  var endLocal=end.atZone(zone);String tzdb=ZoneRulesProvider.getVersions(zone.getId()).lastKey();
  return new Resolved(start,end,zone.getId(),version,tzdb,local,endLocal.toLocalDateTime(),offset,endLocal.getOffset(),"DERIVED_FROM_DURATION");
 }
}
