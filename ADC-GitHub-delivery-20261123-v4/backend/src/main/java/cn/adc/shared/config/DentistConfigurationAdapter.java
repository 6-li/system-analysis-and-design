package cn.adc.shared.config;
import java.util.*;import java.util.function.Supplier;
public final class DentistConfigurationAdapter implements DentistEligibilityProvider {
 public record Entry(long id,Set<Long> clinics,String qualificationEvidence,boolean verified,SourceStamp provenance){public Entry{clinics=Set.copyOf(clinics);}}
 private final ReferenceDataReader reader;private final Supplier<List<Entry>> source;
 public DentistConfigurationAdapter(ReferenceDataReader reader,Supplier<List<Entry>> source){this.reader=reader;this.source=source;}
 public Eligibility require(long id,long clinic){try{var list=source.get().stream().filter(e->e.id()==id).toList();if(list.size()!=1)throw new ConfigurationUnavailable();var e=list.getFirst();e.provenance().requireUsable();var actual=reader.dentist(id).orElseThrow(ConfigurationUnavailable::new);if(actual.qualification()==null||actual.qualification().isBlank()||!e.verified()||!e.clinics().contains(clinic)||e.qualificationEvidence()==null||e.qualificationEvidence().isBlank())throw new ConfigurationUnavailable();return new Eligibility(id,clinic,e.qualificationEvidence(),e.provenance());}catch(RuntimeException e){throw new ConfigurationUnavailable();}}
}
