package cn.adc.appointments;
import cn.adc.shared.audit.AuditDocuments;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class ExistingPatientLinkControllerTest {
    @Test void explicitConfirmationAndExactContractPrecedeAuthorizedAdapter(){
        var calls=new AtomicInteger();var controller=new ExistingPatientLinkController((r,c,i,l)->{calls.incrementAndGet();return AuditDocuments.JSON.createObjectNode().put("bound",true);});
        var input=AuditDocuments.JSON.createObjectNode().put("requestId","TEST-request").put("requestVersion",0).put("patientId","71004").put("verificationBasis","TEST-verified").put("identityConfirmed",false);
        assertThrows(IllegalArgumentException.class,()->controller.link(null,"TEST-csrf",input));assertEquals(0,calls.get());
        input.put("identityConfirmed",true);assertEquals(200,controller.link(null,"TEST-csrf",input).getStatusCode().value());
        input.put("applicantAccount","SPOOFED");assertThrows(IllegalArgumentException.class,()->controller.link(null,"TEST-csrf",input));assertEquals(1,calls.get());
    }
}
