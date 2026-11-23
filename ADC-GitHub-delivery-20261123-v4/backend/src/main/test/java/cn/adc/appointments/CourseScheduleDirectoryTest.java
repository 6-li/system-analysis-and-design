package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class CourseScheduleDirectoryTest {
    @Test void configuredCourseDirectoryIncludesAllExistingDemoDentistsAndDeduplicates(){
        var env=new MockEnvironment().withProperty("adc.course.synthetic-dentist-ids","79001,79002,79003,79001");
        assertEquals(Set.of(79001L,79002L,79003L),CourseScheduleConfiguration.configuredDentists(env,79001));
        assertEquals(Set.of(79001L),CourseScheduleConfiguration.configuredDentists(new MockEnvironment(),79001));
    }
    @Test void malformedDirectoryAndMissingPersonalDentistAreRejected(){
        for(String value:new String[]{"79001,","79001,-1","79002","79001,unknown"}){
            assertThrows(IllegalArgumentException.class,()->CourseScheduleConfiguration.configuredDentists(new MockEnvironment().withProperty("adc.course.synthetic-dentist-ids",value),79001));
        }
    }
}
