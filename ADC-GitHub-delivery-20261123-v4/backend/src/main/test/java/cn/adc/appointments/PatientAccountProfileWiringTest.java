package cn.adc.appointments;
import cn.adc.shared.db.JdbcTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
class PatientAccountProfileWiringTest {
    private ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(MiniPatientProfileController.class)
        .withBean(MiniRequestSession.class,()->new MiniRequestSession(code->Optional.empty(),new WeChatCourseSessionConfiguration.ExpiringSessions(Clock.systemUTC())))
        .withBean("miniReadTransactions",JdbcTransactions.class,()->new JdbcTransactions(mock(DataSource.class)))
        .withBean(MiniAppointmentReadController.PatientScope.class,()->new CourseMiniPatientScope(Map.of()));}
    @Test void accountModeExposesExistingProfileReadWithoutASeparateForgottenFlag(){
        context().withPropertyValues("adc.course.patient-accounts-enabled=true").run(c->assertThat(c).hasSingleBean(MiniPatientProfileController.class));
    }
    @Test void bothFlagsOffKeepProfileReadDisabled(){
        context().withPropertyValues("adc.course.patient-accounts-enabled=false","adc.mini.profile-read-enabled=false").run(c->assertThat(c).doesNotHaveBean(MiniPatientProfileController.class));
    }
}
