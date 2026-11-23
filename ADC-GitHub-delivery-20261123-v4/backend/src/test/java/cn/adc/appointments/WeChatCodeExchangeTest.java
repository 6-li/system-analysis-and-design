package cn.adc.appointments;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class WeChatCodeExchangeTest {
    @Test
    void verifiesOneCodeAndIssuesAnExpiringLocalSessionWithoutPatientAuthority() {
        var time = new AtomicReference<>(Instant.parse("2026-11-18T09:00:00Z"));
        var clock = new Clock() {
            @Override public Instant instant() { return time.get(); }
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
        };
        var exchange = adapter(request -> {
            assertEquals("api.weixin.qq.com", request.uri().getHost());
            assertEquals("/sns/jscode2session", request.uri().getPath());
            assertTrue(request.uri().getQuery().contains("js_code=example-code"));
            return reply("{\"openid\":\"test_openid\",\"session_key\":\"test_session_key\"}");
        });
        var service = new MiniRequestSession(exchange, new WeChatCourseSessionConfiguration.ExpiringSessions(clock));
        var output = service.login(cn.adc.shared.audit.AuditDocuments.JSON.createObjectNode().put("code", "example-code"));
        assertFalse(output.path("identityVerified").asBoolean(true));
        assertEquals(2, output.size());
        String header = "Bearer " + output.path("accessToken").asText();
        assertTrue(service.account(header).matches("WX:[0-9a-f]{64}"));
        time.set(time.get().plusSeconds(1800));
        assertThrows(SecurityException.class, () -> service.account(header));
    }

    @Test
    void rejectsSyntheticAndExpiredCodesAndSanitizesUpstreamFailure() {
        var invalid = adapter(request -> reply("{\"errcode\":40029,\"errmsg\":\"test-private\"}"));
        assertTrue(invalid.exchangeCode("example-code").isEmpty());
        assertTrue(adapter(request -> { throw new AssertionError(); }).exchangeCode("SYNTHETIC-code").isEmpty());
        var unavailable = adapter(request -> reply("{\"errcode\":-1,\"errmsg\":\"test-private\"}"));
        var failure = assertThrows(IllegalStateException.class, () -> unavailable.exchangeCode("example-code"));
        assertEquals("MINI_WECHAT_EXCHANGE_UNAVAILABLE", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static WeChatCodeExchange adapter(WeChatCodeExchange.Transport transport) {
        return new WeChatCodeExchange("wx0123456789abcdef", "a".repeat(32), transport);
    }

    private static WeChatCodeExchange.Reply reply(String json) {
        return new WeChatCodeExchange.Reply(200, json.getBytes(UTF_8));
    }
}
