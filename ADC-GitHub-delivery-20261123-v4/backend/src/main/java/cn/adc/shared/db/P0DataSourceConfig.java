package cn.adc.shared.db;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false) @Profile("p0-test")
public class P0DataSourceConfig {
 @Bean public DataSource p0DataSource(Environment env){return new P0DriverDataSource(env.getRequiredProperty("adc.p0.jdbc-url"),env.getRequiredProperty("adc.p0.user"),()->env.getProperty("ADC_P0_TEST_PASSWORD"));}
}
