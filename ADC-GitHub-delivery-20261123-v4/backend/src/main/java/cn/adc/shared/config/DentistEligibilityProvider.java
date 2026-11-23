package cn.adc.shared.config;
public interface DentistEligibilityProvider {record Eligibility(long dentistId,long clinicId,String evidenceReference,SourceStamp provenance){} Eligibility require(long dentistId,long clinicId);}
