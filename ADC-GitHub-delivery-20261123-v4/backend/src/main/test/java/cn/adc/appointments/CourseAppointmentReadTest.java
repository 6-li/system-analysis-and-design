package cn.adc.appointments;

import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CourseAppointmentReadTest {
    private static MockEnvironment configuration() {
        return new MockEnvironment().withProperty("adc.course.clinic-id", "81001")
                .withProperty("adc.course.database", "adc_course_test")
                .withProperty("adc.course.database-port", "55432")
                .withProperty("ADC_COURSE_DB_READ_PASSWORD", "SYNTHETIC");
    }

    @Test
    void rejectsIneligibleSessionsAndUsesExistingReadContractForReception() throws Exception {
        var controller = new CourseAppointmentRead(configuration());
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var path = "/api/v1/staff/appointments/invalid";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).session(session("dentist"))).andExpect(status().isForbidden());
        mvc.perform(get(path).session(session("reception")))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
        mvc.perform(get(path + "/schedule-support").session(session("reception")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT_ID"));
        assertThrows(IllegalArgumentException.class,
                () -> new CourseAppointmentRead(configuration().withProperty("adc.course.database", "adc_dev")));
        assertThrows(IllegalArgumentException.class,
                () -> new CourseAppointmentRead(configuration().withProperty("adc.course.database-port", "5432")));
    }

    private static MockHttpSession session(String role) {
        var session = new MockHttpSession();
        session.setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC " + role, "SYNTHETIC",
                        new SourceStamp("SYNTHETIC", "course-demo", "course", Instant.now(), true)));
        return session;
    }
}
