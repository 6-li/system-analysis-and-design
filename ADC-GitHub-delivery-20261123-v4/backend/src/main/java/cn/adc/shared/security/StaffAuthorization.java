package cn.adc.shared.security;
import java.util.*;
import java.util.function.Supplier;

/** Authorization is evaluated afresh at each execution/recovery boundary. */
public final class StaffAuthorization {
 public record Grant(String policyVersion,Set<String> roles,Set<String> actions,Set<Long> clinicIds,boolean globalCatalog){
  public Grant {if(policyVersion==null||policyVersion.isBlank())throw new IllegalArgumentException("AUTH_POLICY_VERSION_REQUIRED");roles=Set.copyOf(roles);actions=Set.copyOf(actions);clinicIds=Set.copyOf(clinicIds);}
 }
 public record Context(String actor,String policyVersion,String action,Set<Long> clinicIds,boolean allowed,boolean globalCatalog,Set<String> roles){}
 private static final Map<String,String> ROLE=Map.ofEntries(
  Map.entry("UC21","CLINIC_MANAGER"),Map.entry("UC22","CLINIC_MANAGER"),
  Map.entry("UC01","BOOKING_RECEPTION"),Map.entry("UC02","BOOKING_RECEPTION"),
  Map.entry("UC03","BOOKING_RECEPTION"),Map.entry("UC04","BOOKING_RECEPTION"),
  Map.entry("UC05","CLINIC_RECEPTION"),Map.entry("UC06","CLINIC_RECEPTION"),
  Map.entry("UC07","DENTIST"),Map.entry("UC08","DENTIST"),Map.entry("UC09","DENTIST"),Map.entry("UC10","BILLING_STAFF"),Map.entry("UC11","BILLING_STAFF"),Map.entry("UC12","BILLING_STAFF"),Map.entry("UC13","CLINIC_MANAGER"),Map.entry("UC14","PROCUREMENT_STAFF"),Map.entry("UC15","PROCUREMENT_STAFF"),Map.entry("UC16","CLINIC_MANAGER"),Map.entry("UC17","HQ_OPERATIONS"),Map.entry("UC18","HQ_OPERATIONS"),Map.entry("UC19","HQ_OPERATIONS"),Map.entry("UC20","HQ_FINANCE"),Map.entry("UC18_CORRECTION","CLINIC_MANAGER"),Map.entry("UC11_CLINICAL","DENTIST"),Map.entry("DURATION_EDIT","HQ"),Map.entry("DURATION_PUBLISH","HQ"));
 private final StaffIdentityProvider identities;private final Supplier<Map<String,Grant>> bindings;
 public StaffAuthorization(StaffIdentityProvider identities,Supplier<Map<String,Grant>> bindings){this.identities=Objects.requireNonNull(identities);this.bindings=Objects.requireNonNull(bindings);}
 public Context evaluate(String action,Set<Long> clinics){
  if(!ROLE.containsKey(action))throw new IllegalArgumentException("UNAPPROVED_ACTION");
  String actor=identities.current().filter(s->!s.isBlank()&&s.length()<=100&&s.equals(s.strip())).orElseThrow(()->new SecurityException("AUTHENTICATION_REQUIRED"));
  Grant g=bindings.get().get(actor);Set<Long> scope=Set.copyOf(clinics);boolean hq=action.startsWith("DURATION_");
  boolean allowed=g!=null&&g.roles().contains(ROLE.get(action))&&g.actions().contains(action)&&(hq?g.globalCatalog():!scope.isEmpty()&&g.clinicIds().containsAll(scope));
  return new Context(actor,g==null?"UNBOUND":g.policyVersion(),action,scope,allowed,g!=null&&g.globalCatalog(),g==null?Set.of():g.roles());
 }
 public Context require(String action,Set<Long> clinics){var c=evaluate(action,clinics);if(!c.allowed())throw new SecurityException("ACTION_OR_SCOPE_DENIED");return c;}
 public Set<Long> uc21Clinics(){
  String actor=identities.current().orElseThrow(()->new SecurityException("AUTHENTICATION_REQUIRED"));
  var grant=bindings.get().get(actor);
  if(grant==null||grant.clinicIds().isEmpty())throw new SecurityException("ACTION_OR_SCOPE_DENIED");
  var checked=require("UC21",grant.clinicIds());
  if(!actor.equals(checked.actor()))throw new SecurityException("ACTION_OR_SCOPE_DENIED");
  return checked.clinicIds();
 }
}
