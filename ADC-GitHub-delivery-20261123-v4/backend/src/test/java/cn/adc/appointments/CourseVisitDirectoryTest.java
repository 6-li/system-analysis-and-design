package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import cn.adc.shared.config.SourceStamp;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class CourseVisitDirectoryTest{
 @Test void typedSessionAndExactRoleRequired(){var r=new MockHttpServletRequest();assertThrows(SecurityException.class,()->CourseVisitDirectory.require(r,"SYNTHETIC dentist"));r.getSession().setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,new StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC reception","TEST csrf",new SourceStamp("SYNTHETIC","v1","TEST",Instant.now(),true)));assertDoesNotThrow(()->CourseVisitDirectory.require(r,"SYNTHETIC reception"));assertThrows(SecurityException.class,()->CourseVisitDirectory.require(r,"SYNTHETIC dentist"));assertThrows(SecurityException.class,()->CourseVisitDirectory.require(r,"SYNTHETIC billing"));}
 @Test void canonicalIdsOnly(){assertEquals(1,CourseVisitDirectory.id("1"));for(String text:new String[]{"0","-1","01"," 1","1.0","9223372036854775808"})assertThrows(IllegalArgumentException.class,()->CourseVisitDirectory.id(text));}
 @Test void billingFiltersUseExistingBusinessStates(){for(String state:new String[]{"PENDING","","待核对确认","待核实","待再次确认","待结算","已结算"})assertDoesNotThrow(()->CourseBillingDirectory.validateStatus(state));assertThrows(IllegalArgumentException.class,()->CourseBillingDirectory.validateStatus("费用待核实"));assertThrows(IllegalArgumentException.class,()->CourseBillingDirectory.validateStatus("ALL CLINICS"));}

}
