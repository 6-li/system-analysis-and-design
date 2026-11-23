package cn.adc.shared.config;
import java.time.ZoneId;import java.util.*;import java.util.function.Supplier;
public final class ClinicConfigurationAdapter implements ClinicDirectoryProvider {
 public record Entry(long id,String name,String iana,SourceStamp provenance){}
 private final ReferenceDataReader reader;private final Supplier<List<Entry>> source;
 public ClinicConfigurationAdapter(ReferenceDataReader reader,Supplier<List<Entry>> source){this.reader=reader;this.source=source;}
 public Clinic find(long id){try{var matches=source.get().stream().filter(e->e.id()==id).toList();if(matches.size()!=1)throw new ConfigurationUnavailable();var e=matches.getFirst();e.provenance().requireUsable();if(!ZoneId.getAvailableZoneIds().contains(e.iana()))throw new ConfigurationUnavailable();var existing=reader.clinic(id).orElseThrow(ConfigurationUnavailable::new);if(!Objects.equals(existing.name(),e.name()))throw new ConfigurationUnavailable();return new Clinic(id,existing.name(),ZoneId.of(e.iana()),e.provenance());}catch(RuntimeException e){throw new ConfigurationUnavailable();}}
}
