package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses the real scoped PostgreSQL role and production file-based grant adapter; no SQL writes here. */
class MiniPatientProfileIsolatedPgIT {
    @Test void verifiedSelfArchiveUsesRealReadRoleAndObservesRevocation() throws Exception {
        var env=new StandardEnvironment();
        var config=new CourseMiniReadConfiguration();
        var tx=config.miniReadTransactions(env);
        var file=Path.of(env.getRequiredProperty("adc.course.patient-grants-file"));
        var grant=AuditDocuments.JSON.createObjectNode().put("schemaVersion",1);
        grant.putArray("grants").addObject().put("account","SYNTHETIC-profile-self")
                .put("authorizationId","SYNTHETIC-self-71").put("patientId","71").put("kind","SELF")
                .put("version",1).putArray("permissions").add(MiniPatientProfileController.PERMISSION);
        Files.writeString(file,grant.toString());
        var sessions=new MiniRequestSession(code -> Optional.empty(),new MiniRequestSession.Sessions(){
            public String issue(String account){throw new UnsupportedOperationException();}
            public Optional<String> activeAccount(String token){return "SYNTHETIC-profile-token".equals(token)?Optional.of("SYNTHETIC-profile-self"):Optional.empty();}
        });
        var mvc=MockMvcBuilders.standaloneSetup(new MiniPatientProfileController(sessions,tx,config.courseMiniScope(env))).build();
        mvc.perform(get("/api/v1/mini/me")).andExpect(status().isUnauthorized());
        var response=mvc.perform(get("/api/v1/mini/me").param("patientId","72")
                .header("Authorization","Bearer SYNTHETIC-profile-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.status").value("VERIFIED")).andExpect(jsonPath("$.patientId").value("71"))
                .andExpect(jsonPath("$.patientName").value("SYNTHETIC 本人"))
                .andExpect(jsonPath("$.contactInformation").value("self@example.invalid")).andReturn();
        assertFalse(response.getResponse().getContentAsString().contains("other@example.invalid"));
        Files.writeString(Path.of(System.getenv("ADC_COURSE_EVIDENCE"),"profile-service-response.json"),response.getResponse().getContentAsString());
        Files.writeString(file,"{\"schemaVersion\":1,\"grants\":[]}");
        mvc.perform(get("/api/v1/mini/me").header("Authorization","Bearer SYNTHETIC-profile-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNLINKED"))
                .andExpect(jsonPath("$.patientName").doesNotExist());
        assertEquals("adc_course_mini_read",tx.execute(c->{try(var p=c.createStatement();var r=p.executeQuery("SELECT current_user")){r.next();return r.getString(1);}}));
    }
}
