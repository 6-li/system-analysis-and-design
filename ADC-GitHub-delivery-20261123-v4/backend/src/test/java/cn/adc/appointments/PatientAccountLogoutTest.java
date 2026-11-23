package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PatientAccountLogoutTest {
    @Test void logoutRevokesOnlyThePresentedSessionAndRejectsReuse(){
        var tokens=new WeChatCourseSessionConfiguration.ExpiringSessions(Clock.systemUTC());
        var sessions=new MiniRequestSession(code->Optional.empty(),tokens);
        String alice=tokens.issue("ACCOUNT:"+UUID.randomUUID()),bob=tokens.issue("ACCOUNT:"+UUID.randomUUID());
        var controller=new PatientAccountLogoutController(sessions);
        assertEquals(200,controller.logout("Bearer "+alice).getStatusCode().value());
        assertThrows(SecurityException.class,()->sessions.account("Bearer "+alice));
        assertNotNull(sessions.account("Bearer "+bob));
        assertThrows(SecurityException.class,()->controller.logout(null));
    }
}
