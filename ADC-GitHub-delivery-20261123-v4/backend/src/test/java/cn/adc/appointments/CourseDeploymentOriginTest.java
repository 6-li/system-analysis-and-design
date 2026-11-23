package cn.adc.appointments;
import cn.adc.shared.config.CoursePublicOrigin;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class CourseDeploymentOriginTest {
    MockEnvironment env(){return new MockEnvironment().withProperty("adc.course.public-origin","https://106.55.173.42")
        .withProperty("adc.course.database","adc_course_validation").withProperty("adc.course.database-port","55432")
        .withProperty("adc.course.clinic-id","81001").withProperty("ADC_COURSE_DB_WRITE_PASSWORD","SYNTHETIC-test-only")
        .withProperty("ADC_COURSE_DB_PATIENT_INTAKE_PASSWORD","SYNTHETIC-test-only");}
    @Test void configuredHttpsHostLoadsBothAdaptersWithoutConnectingToDatabase(){
        assertDoesNotThrow(()->new CourseAppointmentWrite(env()));
        assertDoesNotThrow(()->new CoursePatientIntakeConfiguration().coursePatientIntake(env()));
        assertThrows(IllegalArgumentException.class,()->new CourseAppointmentWrite(env().withProperty("adc.course.database","other")));
        assertThrows(IllegalArgumentException.class,()->new CoursePatientIntakeConfiguration().coursePatientIntake(env().withProperty("adc.course.database","other")));
    }
    @Test void httpAndOriginsWithCredentialsOrPathsRemainRejected(){
        for(String value:new String[]{"http://106.55.173.42","https://user:secret@example.invalid","https://example.invalid/path","https://example.invalid?x=1","https://example.invalid#x","https://example.invalid:0"})
            assertThrows(IllegalArgumentException.class,()->CoursePublicOrigin.require(value));
        assertEquals("https://course.example.invalid",CoursePublicOrigin.require("https://course.example.invalid"));
    }
}
