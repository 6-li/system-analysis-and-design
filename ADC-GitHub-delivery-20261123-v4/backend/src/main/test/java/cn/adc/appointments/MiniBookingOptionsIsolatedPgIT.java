package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;import java.time.ZoneId;import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class MiniBookingOptionsIsolatedPgIT {
    @Test void anonymousOptionsUseTheActualFiveColumnProjectGrant()throws Exception{
        var tx=new CourseMiniReadConfiguration().miniReadTransactions(new StandardEnvironment());
        var sessions=new MiniRequestSession(code->Optional.empty(),new MiniRequestSession.Sessions(){public String issue(String a){throw new UnsupportedOperationException();}public Optional<String> activeAccount(String t){return "SYNTHETIC-token".equals(t)?Optional.of("SYNTHETIC-account"):Optional.empty();}});
        var directory=new MiniBookingOptionsController.Directory(){public ZoneId zone(long c){assertEquals(81001,c);return ZoneId.of("Asia/Shanghai");}public boolean dentist(long c,long d){return c==81001&&java.util.Set.of(79001L,79002L,79003L).contains(d);}};
        var mvc=MockMvcBuilders.standaloneSetup(new MiniBookingOptionsController(sessions,tx,directory)).build();
        String date=System.getenv("ADC_COURSE_OPTIONS_DATE");
        var result=mvc.perform(get("/api/v1/mini/booking-options").param("clinicId","81001").param("date",date).param("projectId","85001").header("Authorization","Bearer SYNTHETIC-token"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.projects[0].projectName").value("SYNTHETIC 检查")).andExpect(jsonPath("$.slots.length()").value(42))
            .andExpect(jsonPath("$.slots[0].startsAt").value(date+"T09:00+08:00"))
            .andExpect(jsonPath("$.slots[0].availabilityStatus").value("AVAILABLE")).andExpect(jsonPath("$.reservesCapacity").value(false)).andReturn();
        Files.writeString(Path.of(System.getenv("ADC_COURSE_EVIDENCE"),"anonymous-options-response.json"),result.getResponse().getContentAsString());
        assertFalse(result.getResponse().getContentAsString().contains("patientId"));
        assertEquals("adc_course_mini_read",tx.execute(c->{try(var p=c.createStatement();var r=p.executeQuery("SELECT current_user")){r.next();return r.getString(1);}}));
    }
}
