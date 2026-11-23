package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class PatientClinicalNotesTest {
 @Test void doctorNotesAreOptionalAndSeparateFromSpecialRequirements(){
  var input=new VisitTreatmentService.Input(1,true,"TEST check","次",BigDecimal.ONE,"TEST special",null,"TEST findings","TEST follow-up");
  assertDoesNotThrow(()->VisitTreatmentService.validate(input));assertEquals("TEST special",input.specialRequirements());
  assertDoesNotThrow(()->VisitTreatmentService.validate(new VisitTreatmentService.Input(1,true,"TEST check","次",BigDecimal.ONE,null,null)));
 }
 @Test void excessiveOrBlankExplanationsRejected(){
  assertThrows(IllegalArgumentException.class,()->VisitTreatmentService.validate(new VisitTreatmentService.Input(1,true,"TEST","次",BigDecimal.ONE,null,null,"x".repeat(4001),null)));
  assertThrows(IllegalArgumentException.class,()->VisitTreatmentService.validate(new VisitTreatmentService.Input(1,true,"TEST","次",BigDecimal.ONE,null,null," ",null)));
 }
}
