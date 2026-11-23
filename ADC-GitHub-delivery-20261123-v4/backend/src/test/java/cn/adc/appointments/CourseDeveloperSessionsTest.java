package cn.adc.appointments;
import cn.adc.shared.audit.AuditDocuments;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class CourseDeveloperSessionsTest {
    MockEnvironment env(){return new MockEnvironment().withProperty("adc.course.database","adc_course_validation")
        .withProperty("adc.course.database-port","55432").withProperty("adc.course.public-origin","https://106.55.173.42");}
    @Test void syntheticLoginIsNotVerifiedIdentityAndUsesExistingIntakeAccount(){
        var session=new CourseDeveloperSessionConfiguration().courseDeveloperSessions(env());
        var result=session.login(AuditDocuments.parse("{\"code\":\"SYNTHETIC-code\"}"));
        assertFalse(result.path("identityVerified").asBoolean(true));
        assertEquals("SYNTHETIC-course-mini",session.account("Bearer "+result.path("accessToken").asText()));
        assertThrows(SecurityException.class,()->session.login(AuditDocuments.parse("{\"code\":\"not-a-real-wechat-login\"}")));
    }
    @Test void productionMixAndWrongDatabaseRemainBlocked(){
        var config=new CourseDeveloperSessionConfiguration();
        assertThrows(IllegalArgumentException.class,()->config.courseDeveloperSessions(env().withProperty("adc.mini.wechat-exchange-enabled","true")));
        assertThrows(IllegalArgumentException.class,()->config.courseDeveloperSessions(env().withProperty("adc.course.database-port","5432")));
        assertThrows(IllegalArgumentException.class,()->config.courseDeveloperSessions(env().withProperty("adc.course.database","other")));
    }
}
