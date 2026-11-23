package cn.adc.appointments;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.security.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import java.time.Clock;
import java.nio.file.Path;
import java.util.*;
@Configuration(proxyBeanMethods=false) @Profile("course-demo")
@ConditionalOnProperty(name="adc.course.clinical-batch-enabled",havingValue="true")
class CourseClinicalBillingConfiguration {
    @Bean CourseClinicalCatalog courseClinicalCatalog(Environment env)throws java.io.IOException{
        if(!"adc_course_validation".equals(env.getRequiredProperty("adc.course.database"))||env.getRequiredProperty("adc.course.clinic-id",Long.class)!=81001)throw new IllegalArgumentException("COURSE_CLINICAL_SCOPE_REQUIRED");
        return new CourseClinicalCatalog(Path.of(env.getRequiredProperty("adc.course.clinical-catalog")));
    }
    static StaffBindingAdapter billingBindings(){
        var stamp=new SourceStamp("SYNTHETIC scoped billing identity","course-clinical-v1","course configuration",java.time.Instant.now(),true);
        return new StaffBindingAdapter(()->List.of(
            new StaffBindingAdapter.Entry("SYNTHETIC billing",new StaffAuthorization.Grant(stamp.version(),Set.of("BILLING_STAFF"),Set.of("UC10","UC11","UC12"),Set.of(81001L),false),stamp),
            new StaffBindingAdapter.Entry("SYNTHETIC dentist",new StaffAuthorization.Grant(stamp.version(),Set.of("DENTIST"),Set.of("UC11_CLINICAL"),Set.of(81001L),false),stamp)));
    }
    @Bean TreatmentInvoiceController.Access courseInvoice(Environment env,CourseClinicalCatalog prices,org.springframework.beans.factory.ObjectProvider<CourseProjectCatalog> projects){
        return new TreatmentInvoiceController.StaffAccess(CourseAppointmentRead.courseTransactions(env,"adc_course_billing","ADC_COURSE_DB_BILLING_PASSWORD",false),billingBindings(),r->81001L,
            (c,target,fact)->projects.getIfAvailable()==null?prices.price(fact.item(),fact.unit()):projects.getIfAvailable().price(c,fact,prices),CourseClinicalIds::invoice,CourseClinicalIds::item,CourseBookingIds::audit,Clock.systemUTC());
    }
    @Bean InvoiceSettlementController.Access courseSettlement(Environment env){
        return new InvoiceSettlementController.StaffAccess(CourseAppointmentRead.courseTransactions(env,"adc_course_billing","ADC_COURSE_DB_BILLING_PASSWORD",false),billingBindings(),r->81001L,CourseClinicalIds::payment,CourseBookingIds::audit,Clock.systemUTC());
    }
    @Bean InvoiceQuestionController.Access courseInvoiceQuestion(Environment env,CourseClinicalCatalog prices,org.springframework.beans.factory.ObjectProvider<CourseProjectCatalog> projects){
        long doctor=env.getRequiredProperty("adc.course.dentist-id",Long.class);
        var base=new InvoiceQuestionController.StaffAccess(CourseAppointmentRead.courseTransactions(env,"adc_course_billing","ADC_COURSE_DB_BILLING_PASSWORD",false),billingBindings(),r->81001L,
            (actor,clinic)->"SYNTHETIC dentist".equals(actor)&&clinic==81001?doctor:0,
            (c,target,fact)->projects.getIfAvailable()==null?prices.price(fact.item(),fact.unit()):projects.getIfAvailable().price(c,fact,prices),CourseClinicalIds::item,CourseBookingIds::audit,Clock.systemUTC());
        return (request,csrf)->{var opened=base.open(request,csrf);if(env.getProperty("adc.course.quarterly-usability-enabled",Boolean.class,false))opened.service().enablePatientNotes();var catalog=projects.getIfAvailable();if(catalog!=null)opened.service().enableProjectScopes(catalog.scopes);return opened;};
    }
}
