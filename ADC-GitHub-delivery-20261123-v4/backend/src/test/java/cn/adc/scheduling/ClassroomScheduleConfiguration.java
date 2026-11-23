package cn.adc.scheduling;

import cn.adc.shared.config.ClinicDirectoryProvider.Clinic;
import cn.adc.shared.config.ConfigurationUnavailable;
import cn.adc.shared.config.DentistEligibilityProvider.Eligibility;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffBindingAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Import({ScheduleController.class, ResolutionController.class})
@ConditionalOnProperty(name = "adc.uc21.enabled", havingValue = "true")
public class ClassroomScheduleConfiguration {
    @Bean ScheduleAccessFactory classroomSchedules(JdbcTransactions tx, StaffBindingAdapter bindings) {
        var clock = Clock.systemUTC();
        var source = new SourceStamp("SYNTHETIC classroom schedules", "1", "isolated", clock.instant(), true);
        var ids = new AtomicLong(88000);
        return new ScheduleAccessFactory(tx, bindings, () -> source,
                clinic -> {
                    if (clinic != 81001) throw new ConfigurationUnavailable();
                    return new Clinic(clinic, "ADC 近邻门诊（演示）", ZoneId.of("Asia/Shanghai"), source);
                },
                (dentist, clinic) -> {
                    if (dentist != 79001 || clinic != 81001) throw new ConfigurationUnavailable();
                    return new Eligibility(dentist, clinic, "SYNTHETIC qualification", source);
                },
                (clinic, dentist, start, end, submitted, conflicts) -> Optional.empty(),
                clock, c -> ids.incrementAndGet(), c -> ids.incrementAndGet());
    }
}
