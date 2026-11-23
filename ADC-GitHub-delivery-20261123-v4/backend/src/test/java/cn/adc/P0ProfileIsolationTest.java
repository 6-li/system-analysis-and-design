package cn.adc;
import cn.adc.shared.db.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
class P0ProfileIsolationTest {
 @Test void defaultTechnicalProfileHasNoDataSource(){new ApplicationContextRunner().withUserConfiguration(P0DataSourceConfig.class).run(c->assertThat(c).doesNotHaveBean(DataSource.class));}
 @Test void enabledProfileWithoutTargetFailsBeforeConnecting(){new ApplicationContextRunner().withInitializer(c->c.getEnvironment().setActiveProfiles("p0-test")).withUserConfiguration(P0DataSourceConfig.class).run(c->assertThat(c).hasFailed());}
 @Test void targetValidationRejectsDefaultPortsExistingDatabasesAndUrlCredentials(){
  for(String url:new String[]{"jdbc:postgresql://127.0.0.1:5432/adc_p0_batch1_test","jdbc:postgresql://127.0.0.1:55555/adc_test","jdbc:postgresql://remote:55555/adc_p0_batch1_test","jdbc:postgresql://127.0.0.1:55555/adc_p0_batch1_test?password=x"})assertThrows(IllegalArgumentException.class,()->new P0DriverDataSource(url,"p0_probe_app",()->"unused"));
  assertThrows(IllegalArgumentException.class,()->new P0DriverDataSource("jdbc:postgresql://127.0.0.1:55555/adc_p0_batch1_test","postgres",()->"unused"));
 }
}
