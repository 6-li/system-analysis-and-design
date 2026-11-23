package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import java.time.*;import java.util.*;

/** Anonymous availability projection only. Never reserves capacity or authorizes a later booking.
 * Caller supplies authorized EFFECTIVE windows, CURRENT basis and a complete trusted occupancy read.
 * Missing or unverifiable sources must fail before invoking this planner, never become an empty list. */
final class MiniAvailabilityPlanner {
 record Window(Version schedule,long clinic,long dentist,Instant start,Instant end,String status){}
 record Occupancy(long dentist,Instant start,Instant end){}
 record Slot(Version schedule,long clinic,long dentist,Instant start,Instant end,Basis basis,boolean available){}
 static List<Slot> plan(List<Window> windows,List<Occupancy> occupied,Basis current){
  Objects.requireNonNull(windows,"EFFECTIVE_SOURCE_REQUIRED");Objects.requireNonNull(occupied,"OCCUPANCY_SOURCE_REQUIRED");Objects.requireNonNull(current,"CURRENT_DURATION_REQUIRED");
  for(var b:occupied)if(b.dentist()<=0||b.start()==null||b.end()==null||!b.start().isBefore(b.end()))throw new IllegalArgumentException("INVALID_OCCUPANCY");
  var output=new ArrayList<Slot>();long seconds=Math.multiplyExact((long)current.minutes(),60);
  for(var w:windows){
   if(w.schedule()==null||w.clinic()<=0||w.dentist()<=0||w.start()==null||w.end()==null||!w.start().isBefore(w.end()))throw new IllegalArgumentException("INVALID_WINDOW");
   if(!"EFFECTIVE".equals(w.status()))continue;
   // Candidate spacing is this project's standard duration, never a hardcoded 30-minute capacity rule.
   // Include occupied endpoints so fragmented but sufficiently long gaps can still be offered.
   var starts=new TreeSet<Instant>();var seeds=new TreeSet<Instant>();seeds.add(w.start());
   for(var b:occupied)if(b.dentist()==w.dentist()&&b.end().isAfter(w.start())&&b.end().isBefore(w.end()))seeds.add(b.end());
   for(var seed:seeds)for(var start=seed;!start.plusSeconds(seconds).isAfter(w.end());start=start.plusSeconds(seconds))starts.add(start);
   for(var start:starts){var end=start.plusSeconds(seconds);boolean available=occupied.stream().noneMatch(b->b.dentist()==w.dentist()&&start.isBefore(b.end())&&b.start().isBefore(end));
    output.add(new Slot(w.schedule(),w.clinic(),w.dentist(),start,end,current,available));}
  }
  output.sort(Comparator.comparing(Slot::start).thenComparingLong(Slot::dentist).thenComparingLong(s->s.schedule().scheduleId()).thenComparingInt(s->s.schedule().versionNo()));
  return List.copyOf(output);
 }
}
