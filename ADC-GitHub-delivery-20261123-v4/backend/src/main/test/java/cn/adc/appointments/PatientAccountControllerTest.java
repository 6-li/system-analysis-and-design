package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;

class PatientAccountControllerTest {
    @Test void registrationAndLoginUseDistinctContractsWithoutPatientAuthority() {
        var store=new PatientAccountsTest.MemoryStore();
        var sessions=new WeChatCourseSessionConfiguration.ExpiringSessions(Clock.systemUTC());
        var controller=new PatientAccountController(new PatientAccounts(store,new PatientAccountPasswords(),sessions));
        var input=AuditDocuments.JSON.createObjectNode().put("username","TEST_user").put("password","TEST-password-only");
        var registered=controller.register(input);
        assertEquals(201,registered.getStatusCode().value());
        assertFalse(registered.getBody().path("identityVerified").asBoolean());
        assertFalse(registered.getBody().has("accessToken"));
        assertEquals("no-store",registered.getHeaders().getCacheControl());
        var logged=controller.login(input);
        assertEquals(200,logged.getStatusCode().value());
        assertTrue(sessions.activeAccount(logged.getBody().path("accessToken").asText()).isPresent());
        assertFalse(logged.getBody().has("password"));
        assertThrows(IllegalArgumentException.class,()->controller.register(input));
        input.put("password","TEST-wrong-password");
        assertThrows(SecurityException.class,()->controller.login(input));
        input.put("patientId","71004");
        assertThrows(IllegalArgumentException.class,()->controller.login(input));
    }
}
