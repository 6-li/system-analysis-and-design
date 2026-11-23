package cn.adc.appointments;
import cn.adc.shared.audit.AuditDocuments;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class PatientAccountBindingControllerTest {
    @Test void bindingRequiresExplicitConfirmationBeforeAuthorizedAdapter() {
        var calls=new AtomicInteger();
        var controller=new PatientAccountBindingController((r,c,i)->calls.incrementAndGet());
        var body=AuditDocuments.JSON.createObjectNode().put("requestId","TEST-reference").put("requestVersion",1)
                .put("patientId","71004").put("verificationBasis","TEST-verified").put("identityConfirmed",false);
        assertThrows(IllegalArgumentException.class,()->controller.bind(null,"TEST-csrf",body));assertEquals(0,calls.get());
        body.put("identityConfirmed",true);
        assertTrue(controller.bind(null,"TEST-csrf",body).getBody().path("bound").asBoolean());assertEquals(1,calls.get());
    }
}
