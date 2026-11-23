package cn.adc.appointments;
import cn.adc.shared.config.*;import cn.adc.shared.security.StaffAuthorization;import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.core.env.Environment;
import java.time.*;import java.util.*;
@Configuration(proxyBeanMethods=false)
@Profile("course-demo")
@ConditionalOnProperty(name="adc.course.booking-interaction-enabled",havingValue="true")
class CourseBookingInteractionConfiguration {
 @Bean BookingInteractionController.Access courseBookingInteraction(Environment env){
  if(!"adc_course_validation".equals(env.getRequiredProperty("adc.course.database")))throw new IllegalArgumentException("DEDICATED_COURSE_DATABASE_REQUIRED");
  long clinic=env.getRequiredProperty("adc.course.clinic-id",Long.class);String zone=env.getRequiredProperty("adc.course.clinic-zone");ZoneId.of(zone);
  var tx=CourseAppointmentRead.courseTransactions(env,true);var clock=Clock.systemUTC();
  var sourceTx=CourseAppointmentRead.courseTransactions(env,"adc_course_patient_intake","ADC_COURSE_DB_PATIENT_INTAKE_PASSWORD",true);
  var stamp=new SourceStamp("SYNTHETIC configured course directory","course-interactive-v1","course configuration",clock.instant(),true);
  Set<Long> dentists=Arrays.stream(env.getRequiredProperty("adc.course.synthetic-dentist-ids").split(",")).map(String::trim).map(Long::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
  return new BookingInteractionController.Access(){
   public BookingInteractionController.Context open(HttpServletRequest request){
    var identity=CourseAppointmentRead.identity(request);var auth=CourseAppointmentRead.receptionAuthorization(identity,clinic);auth.require("UC02",Set.of(clinic));
    var proposals=new BookingProposalService(tx,auth,id->{if(id!=clinic)throw new ConfigurationUnavailable();return new ClinicDirectoryProvider.Clinic(id,"SYNTHETIC course clinic",ZoneId.of(zone),stamp);},(dentist,id)->{if(id!=clinic||!dentists.contains(dentist))throw new ConfigurationUnavailable();return new DentistEligibilityProvider.Eligibility(dentist,id,"SYNTHETIC explicitly configured association",stamp);},CommittedBookingRelease::confirms,clock);
    return new BookingInteractionController.Context(proposals,auth,clinic,zone,stamp);
   }
   public InitialBookingReview.AcceptedScheme changePlan(HttpServletRequest request,String reference,long patient,tools.jackson.databind.JsonNode scheme){
    if(!env.getProperty("adc.course.uc03-enabled",Boolean.class,false))throw new cn.adc.shared.audit.CommandRecovery.Rejection("CHANGE_DISABLED");
    CourseAppointmentChange.authorization(request).require("UC03",Set.of(clinic));
    return tx.execute(c->{var plan=CourseChangeProposal.require(c,reference,patient,scheme,id->{if(id!=clinic)throw new ConfigurationUnavailable();return new ClinicDirectoryProvider.Clinic(id,"SYNTHETIC course clinic",ZoneId.of(zone),stamp);});
     if(!dentists.contains(plan.dentistId()))throw new ConfigurationUnavailable();return plan;});
   }
   public void source(HttpServletRequest request,String reference,long patient){var context=open(request);var ref=CourseMiniBookingContext.parseExisting(reference);
    if(env.getProperty("adc.course.uc03-enabled",Boolean.class,false)){
     var type=sourceTx.execute(c->{try(var q=c.prepareStatement("SELECT submitted_content->>'type' FROM adc_mini.preliminary_request WHERE request_id=?")){q.setObject(1,ref.requestId());try(var r=q.executeQuery()){return r.next()?r.getString(1):null;}}});
     if("CHANGE".equals(type)){CourseAppointmentChange.authorization(request).require("UC03",Set.of(clinic));tx.execute(c->{CourseChangeSource.require(c,reference,patient,null);return null;});return;}
    }
    var selected=new MiniRequestInboxService(sourceTx,context.auth(),clinic).selection(ref.requestId().toString(),Integer.toString(ref.requestVersion()));if(!"NEW".equals(selected.path("type").asText())||!selected.has("associatedPatientId")||(patient!=0&&!Long.toString(patient).equals(selected.path("associatedPatientId").asText())))throw new SecurityException("BOOKING_SOURCE_DENIED");}
   public tools.jackson.databind.JsonNode selected(HttpServletRequest request,String reference){var context=open(request);var ref=CourseMiniBookingContext.parseExisting(reference);
    if(env.getProperty("adc.course.uc03-enabled",Boolean.class,false)){
     var type=sourceTx.execute(c->{try(var q=c.prepareStatement("SELECT submitted_content->>'type' FROM adc_mini.preliminary_request WHERE request_id=?")){q.setObject(1,ref.requestId());try(var r=q.executeQuery()){return r.next()?r.getString(1):null;}}});
     if("CHANGE".equals(type)){source(request,reference,0);return new MiniRequestInboxService(sourceTx,CourseAppointmentChange.authorization(request),clinic).processingPreview(ref.requestId().toString(),Integer.toString(ref.requestVersion())).get("selectedScheme");}
    }
    return new MiniRequestInboxService(sourceTx,context.auth(),clinic).bookingPreview(ref.requestId().toString(),Integer.toString(ref.requestVersion())).get("selectedScheme");}
  };
 }
}
