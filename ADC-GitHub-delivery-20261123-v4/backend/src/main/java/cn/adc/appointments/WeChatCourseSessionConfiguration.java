package cn.adc.appointments;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@Profile("course-demo")
@ConditionalOnProperty(name = "adc.mini.wechat-exchange-enabled", havingValue = "true")
class WeChatCourseSessionConfiguration {
    @Bean
    MiniRequestSession courseWeChatSessions(Environment env) {
        return new MiniRequestSession(WeChatCodeExchange.create(env.getRequiredProperty("ADC_WECHAT_APP_ID"),
                env.getRequiredProperty("ADC_WECHAT_APP_SECRET")), new ExpiringSessions(Clock.systemUTC()));
    }

    /** Single-instance course sessions. A restart requires a fresh WeChat login. */
    static final class ExpiringSessions implements MiniRequestSession.Sessions {
        private record Entry(String account, Instant expires) {}
        private final Clock clock;
        private final Map<String, Entry> sessions = new HashMap<>();

        ExpiringSessions(Clock clock) { this.clock = clock; }

        @Override public synchronized String issue(String account) {
            var now = clock.instant();
            sessions.values().removeIf(entry -> !entry.expires().isAfter(now));
            if (sessions.size() >= 4096) throw new IllegalStateException("MINI_SESSION_UNAVAILABLE");
            String token = UUID.randomUUID().toString();
            sessions.put(token, new Entry(account, now.plusSeconds(1800)));
            return token;
        }

        @Override public synchronized Optional<String> activeAccount(String token) {
            var entry = sessions.get(token);
            if (entry == null) return Optional.empty();
            if (entry.expires().isAfter(clock.instant())) return Optional.of(entry.account());
            sessions.remove(token);
            return Optional.empty();
        }
        @Override public synchronized void revoke(String token) { sessions.remove(token); }
    }
}
