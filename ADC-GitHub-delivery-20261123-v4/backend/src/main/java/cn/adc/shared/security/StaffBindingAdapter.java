package cn.adc.shared.security;
import cn.adc.shared.config.SourceStamp;import java.util.*;import java.util.function.Supplier;
public final class StaffBindingAdapter {
 public record Entry(String actor,StaffAuthorization.Grant grant,SourceStamp provenance){}
 private static final Set<String> ACTIONS=Set.of("UC01","UC02","UC03","UC04","UC05","UC06","UC07","UC08","UC09","UC10","UC11","UC11_CLINICAL","UC12","UC13","UC14","UC15","UC16","UC17","UC18","UC18_CORRECTION","UC19","UC20","UC21","UC22","DURATION_EDIT","DURATION_PUBLISH");
 private static final Set<String> ROLES=Set.of("HQ","CLINIC_MANAGER","BOOKING_RECEPTION","CLINIC_RECEPTION","DENTIST","BILLING_STAFF","PROCUREMENT_STAFF","HQ_OPERATIONS","HQ_FINANCE");
 private final Supplier<List<Entry>> source;public StaffBindingAdapter(Supplier<List<Entry>> source){this.source=source;}
 public Map<String,StaffAuthorization.Grant> bindings(){try{var map=new HashMap<String,StaffAuthorization.Grant>();for(var e:source.get()){e.provenance().requireUsable();if(e.actor()==null||e.actor().isBlank()||e.actor().length()>100||!e.actor().equals(e.actor().strip())||!e.grant().policyVersion().equals(e.provenance().version())||!ACTIONS.containsAll(e.grant().actions())||!ROLES.containsAll(e.grant().roles())||map.put(e.actor(),e.grant())!=null)return Map.of();}return Map.copyOf(map);}catch(RuntimeException e){return Map.of();}}
}
