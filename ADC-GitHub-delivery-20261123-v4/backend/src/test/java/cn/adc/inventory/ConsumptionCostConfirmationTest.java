package cn.adc.inventory;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class ConsumptionCostConfirmationTest {
 final Clock clock=Clock.fixed(Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
 @Test void chosenPastTimeAndExactCourseCost(){
  var input=new ConsumptionCostConfirmation.Input(81001,9000001,new BigDecimal("1.235"),clock.instant().minusSeconds(600),"SYNTHETIC manager checked actual fact");
  assertEquals("2.47",ConsumptionCostConfirmation.cost(input,clock).toPlainString());
 }
 @Test void futureUnscopedAndMissingBasisAreRejected(){
  assertThrows(IllegalArgumentException.class,()->ConsumptionCostConfirmation.cost(new ConsumptionCostConfirmation.Input(81001,9000001,new BigDecimal("2"),clock.instant().plusSeconds(1),"checked"),clock));
  assertThrows(IllegalArgumentException.class,()->ConsumptionCostConfirmation.cost(new ConsumptionCostConfirmation.Input(81002,9000001,new BigDecimal("2"),clock.instant(),"checked"),clock));
  assertThrows(IllegalArgumentException.class,()->ConsumptionCostConfirmation.cost(new ConsumptionCostConfirmation.Input(81001,9000001,new BigDecimal("2"),clock.instant(),""),clock));
 }
}
