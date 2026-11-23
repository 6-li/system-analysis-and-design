package cn.adc.appointments;

import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CourseScheduleConfigurationTest {
    @Test void deploymentKeepsVerifiedStaffSessionsAndRequiresAnExplicitClinicTimeZone() {
        var env = new MockEnvironment().withProperty("adc.course.clinic-id", "81001")
                .withProperty("adc.course.dentist-id", "79001").withProperty("adc.course.clinic-zone", "Asia/Shanghai")
                .withProperty("adc.course.database", "adc_course_validation").withProperty("adc.course.database-port", "55432")
                .withProperty("ADC_COURSE_DB_SCHEDULE_PASSWORD", "SYNTHETIC");
        var config = new CourseScheduleConfiguration();
        var access = config.courseSchedules(env);
        var anonymous = new MockHttpServletRequest();
        assertThrows(SecurityException.class, () -> access.requireCsrf(anonymous, "SYNTHETIC-CSRF"));
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC manager", "SYNTHETIC-CSRF",
                        new SourceStamp("SYNTHETIC", "course-v1", "course", Instant.now(), true)));
        access.requireCsrf(request, "SYNTHETIC-CSRF");
        env.setProperty("adc.course.clinic-zone", "ServerDefault");
        assertThrows(IllegalArgumentException.class, () -> config.courseSchedules(env));
    }
}
