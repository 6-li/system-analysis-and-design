package cn.adc.shared.config;
import cn.adc.shared.security.StaffAuthorization;import java.util.*;
/** Internal readiness only: no schedule/appointment writes or public UC endpoint. */
public final class ConfigurationReadiness {
 public record Readiness(boolean ready,List<String> missing){public Readiness{missing=List.copyOf(missing);}}
 private final ClinicDirectoryProvider clinics;private final DentistEligibilityProvider dentists;private final StaffAuthorization auth;
 public ConfigurationReadiness(ClinicDirectoryProvider clinics,DentistEligibilityProvider dentists,StaffAuthorization auth){this.clinics=clinics;this.dentists=dentists;this.auth=auth;}
 public Readiness clinicCapability(long clinic,long dentist,String action){var missing=new ArrayList<String>();try{clinics.find(clinic);}catch(RuntimeException e){missing.add("VERIFIED_CLINIC_ZONE");}try{dentists.require(dentist,clinic);}catch(RuntimeException e){missing.add("VERIFIED_DENTIST_ASSOCIATION_QUALIFICATION");}try{auth.require(action,Set.of(clinic));}catch(RuntimeException e){missing.add("AUTHORIZED_STAFF_SCOPE");}return new Readiness(missing.isEmpty(),missing);}
 public Readiness headquartersPublication(){try{auth.require("DURATION_PUBLISH",Set.of());return new Readiness(true,List.of());}catch(RuntimeException e){return new Readiness(false,List.of("AUTHORIZED_HEADQUARTERS_PUBLISH"));}}
}
