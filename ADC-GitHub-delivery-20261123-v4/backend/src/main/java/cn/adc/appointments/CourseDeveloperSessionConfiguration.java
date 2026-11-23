package cn.adc.appointments;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.time.Clock;
import java.util.Optional;

/** Explicit synthetic developer-tools session, never a verified WeChat identity. */
@Configuration(proxyBeanMethods=false)
@Profile("course-demo")
@ConditionalOnProperty(name="adc.course.developer-mini-session-enabled",havingValue="true")
class CourseDeveloperSessionConfiguration {
    @Bean
    @ConditionalOnProperty(name="adc.course.patient-accounts-enabled",havingValue="false",matchIfMissing=true)
    MiniRequestSession courseDeveloperSessions(Environment env){
        if(env.getProperty("adc.mini.wechat-exchange-enabled",Boolean.class,false)
                || !"adc_course_validation".equals(env.getRequiredProperty("adc.course.database"))
                || env.getRequiredProperty("adc.course.database-port",Integer.class)!=55432)
            throw new IllegalArgumentException("DEDICATED_DEVELOPER_COURSE_ENVIRONMENT_REQUIRED");
        cn.adc.shared.config.CoursePublicOrigin.require(env.getRequiredProperty("adc.course.public-origin"));
        return new MiniRequestSession(code->"SYNTHETIC-code".equals(code)?Optional.of("SYNTHETIC-course-mini"):Optional.empty(),
            new WeChatCourseSessionConfiguration.ExpiringSessions(Clock.systemUTC()));
    }
}
