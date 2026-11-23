package cn.adc.inventory;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffBindingAdapter;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Import({StockInquiryController.class, PurchaseRequirementController.class,
        SupplyReceiptController.class, SupplyConsumptionController.class})
@ConditionalOnProperty(name = "adc.uc13.enabled", havingValue = "true")
public class ClassroomInventoryConfiguration {
    private final AtomicLong audit = new AtomicLong(76000);
    private final Clock clock = Clock.systemUTC();

    @Bean StockInquiryController.Access stock(JdbcTransactions tx, StaffBindingAdapter bindings) {
        return new StockInquiryController.StaffAccess(tx, bindings, c -> audit.incrementAndGet(), clock);
    }

    @Bean PurchaseRequirementController.Access purchase(JdbcTransactions tx, StaffBindingAdapter bindings) {
        return new PurchaseRequirementController.StaffAccess(tx, bindings, c -> 801,
                (c, ref) -> "SYNTHETIC-CONF-1".equals(ref)
                        ? Optional.of(new PurchaseRequirementService.Confirmation(81001, 101, new BigDecimal("6"),
                                ref, "SYNTHETIC manager", "SYNTHETIC confirmed demand"))
                        : Optional.empty(), c -> audit.incrementAndGet(), clock);
    }

    @Bean SupplyReceiptController.Access receipt(JdbcTransactions tx, StaffBindingAdapter bindings) {
        return new SupplyReceiptController.StaffAccess(tx, bindings,
                (c, ref) -> "SYNTHETIC DELIVERY".equals(ref)
                        ? Optional.of(new SupplyReceiptService.Delivery(1001, 81001, 801, ref, null))
                        : Optional.empty(), c -> audit.incrementAndGet(), clock);
    }

    @Bean SupplyConsumptionController.Access consumption(JdbcTransactions tx, StaffBindingAdapter bindings) {
        return new SupplyConsumptionController.StaffAccess(tx, bindings,
                (c, ref) -> "SYNTHETIC cost fact".equals(ref)
                        ? Optional.of(new SupplyConsumptionService.Evidence(1101,
                                new SupplyConsumptionService.Target(81001, 101), new BigDecimal("2.5"),
                                Instant.parse("2026-11-17T07:30:00Z"), new BigDecimal("5.75"),
                                "SYNTHETIC verified cost", "SYNTHETIC manager", ref))
                        : Optional.empty(), c -> audit.incrementAndGet(), clock);
    }
}
