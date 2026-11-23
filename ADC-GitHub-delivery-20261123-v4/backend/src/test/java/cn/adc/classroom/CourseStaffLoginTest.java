package cn.adc.classroom;

import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Locale;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CourseStaffLoginTest {
    @Test
    void createsDistinctStaffSessionsAndRoutesAllSevenCourseAccounts(@TempDir Path bundle) throws Exception {
        String origin = "https://course.example.invalid";
        var pages = Map.of(
                "reception", "/staff/appointments", "dentist", "/staff/visit-consent",
                "billing", "/staff/treatment-invoice", "procurement", "/staff/purchase-requirements",
                "manager", "/staff/stock-inquiry", "operations", "/staff/quarterly-preview",
                "finance", "/staff/quarterly-financial-review");
        Files.createDirectory(bundle.resolve("assets"));
        Files.writeString(bundle.resolve("index.html"), "<html><head></head><body><div id='app'></div></body></html>");
        Files.writeString(bundle.resolve("assets/index-demo.js"), "document.title = 'ADC';");
        var env = new MockEnvironment().withProperty("adc.course.public-origin", origin)
                .withProperty("adc.course.staff-bundle", bundle.toString()).withProperty("adc.course.appointment-read-enabled", "true").withProperty("adc.uc07.enabled", "true")
                .withProperty("adc.uc08.enabled", "true").withProperty("adc.uc09.enabled", "true");
        pages.keySet().forEach(role -> env.setProperty("ADC_DEMO_" + role.toUpperCase(Locale.ROOT), "TEST-" + role));
        var mvc = MockMvcBuilders.standaloneSetup(new CourseStaffLogin(env), new CourseStaffWorkspace(env)).build();
        var csrfTokens = new java.util.HashSet<String>();
        for (var entry : pages.entrySet()) {
            String role = entry.getKey();
            var old = new MockHttpSession();
            var result = mvc.perform(post("/demo/login").session(old).header("Origin", origin)
                            .param("account", role).param("password", "TEST-" + role))
                    .andExpect(status().isSeeOther()).andExpect(redirectedUrl(entry.getValue()))
                    .andExpect(header().string("Cache-Control", "no-store")).andReturn();
            assertTrue(old.isInvalid());
            var session = result.getRequest().getSession(false);
            assertNotNull(session);
            var subject = assertInstanceOf(StaffSessionIdentityAdapter.VerifiedSession.class,
                    session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE));
            assertEquals("SYNTHETIC " + role, subject.subject());
            assertTrue(csrfTokens.add(subject.csrfToken()));
            if ("dentist".equals(role)) {
                mvc.perform(get("/staff/visit-treatment").session((MockHttpSession) session))
                        .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(
                                "name='adc-uc09-csrf' content='" + subject.csrfToken() + "'")));
                mvc.perform(get("/staff/visit-consent").session((MockHttpSession) session))
                        .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(
                                "name='adc-uc08-csrf' content='" + subject.csrfToken() + "'")));
                mvc.perform(get("/staff/visit-health").session((MockHttpSession) session))
                        .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(
                                "name='adc-uc07-csrf' content='" + subject.csrfToken() + "'")));
            }
            subject.provenance().requireUsable();
            mvc.perform(get(entry.getValue()).session((MockHttpSession) session))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("id='app'")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("name='adc-staff-role' content='"+role+"'")));
            String otherPage = "dentist".equals(role) ? pages.get("finance") : pages.get("dentist");
            mvc.perform(get(otherPage).session((MockHttpSession) session)).andExpect(status().isForbidden());
        }
        mvc.perform(get(pages.get("dentist"))).andExpect(status().isSeeOther()).andExpect(redirectedUrl("/"));
        mvc.perform(get("/assets/index-demo.js")).andExpect(status().isOk())
                .andExpect(content().string("document.title = 'ADC';"));
        mvc.perform(post("/demo/login").header("Origin", origin)
                .param("account", "dentist").param("password", "TEST-reception"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/demo/login").header("Origin", "https://other.example.invalid")
                .param("account", "dentist").param("password", "TEST-dentist"))
                .andExpect(status().isForbidden());
    }
    @Test void unifiedBillingStartsAtDetailWorkbenchAndKeepsRoleAndCsrfBoundary(@TempDir Path bundle)throws Exception{
        Files.createDirectory(bundle.resolve("assets"));Files.writeString(bundle.resolve("index.html"),"<html><head></head><body><div id='app'></div></body></html>");
        var env=new MockEnvironment().withProperty("adc.course.public-origin","https://course.example.invalid").withProperty("adc.course.staff-bundle",bundle.toString()).withProperty("adc.course.ui-unification-enabled","true").withProperty("adc.course.clinical-batch-enabled","true");
        for(String role:java.util.List.of("reception","dentist","billing","procurement","manager","operations","finance"))env.setProperty("ADC_DEMO_"+role.toUpperCase(Locale.ROOT),"TEST "+role);
        var mvc=MockMvcBuilders.standaloneSetup(new CourseStaffLogin(env),new CourseStaffWorkspace(env)).build();
        var result=mvc.perform(post("/demo/login").header("Origin","https://course.example.invalid").param("account","billing").param("password","TEST billing")).andExpect(status().isSeeOther()).andExpect(redirectedUrl("/staff/billing-workbench")).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);var verified=(StaffSessionIdentityAdapter.VerifiedSession)session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
        var html=mvc.perform(get("/staff/billing-workbench").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("name='adc-uc10-csrf' content='"+verified.csrfToken()+"'"));assertTrue(html.contains("name='adc-uc12-csrf' content='"+verified.csrfToken()+"'"));assertFalse(html.contains("课程运行环境。"));
        mvc.perform(get("/staff/visit-treatment").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/staff/billing-workbench")).andExpect(status().isSeeOther());
    }

}
