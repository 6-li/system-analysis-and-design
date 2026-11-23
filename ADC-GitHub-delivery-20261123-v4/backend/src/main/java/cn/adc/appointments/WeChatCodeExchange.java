package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** Exchanges one WeChat code. The upstream session key stays outside the ADC session. */
final class WeChatCodeExchange implements MiniRequestSession.Accounts {
    record Reply(int status, byte[] body) {}
    @FunctionalInterface
    interface Transport {
        Reply send(HttpRequest request) throws IOException, InterruptedException;
    }

    private final String appId;
    private final String secret;
    private final Transport transport;

    WeChatCodeExchange(String appId, String secret, Transport transport) {
        if (!appId.matches("wx[0-9a-fA-F]{16}") || !secret.matches("[A-Za-z0-9]{32}")) {
            throw new IllegalArgumentException("MINI_WECHAT_CREDENTIAL_CONFIGURATION_INVALID");
        }
        this.appId = appId;
        this.secret = secret;
        this.transport = transport;
    }

    static WeChatCodeExchange create(String appId, String secret) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new WeChatCodeExchange(appId, secret, request -> {
            var reply = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(reply.statusCode(), reply.body());
        });
    }

    @Override
    public Optional<String> exchangeCode(String code) {
        if (!code.matches("[A-Za-z0-9_-]{1,128}") || code.startsWith("SYNTHETIC-")) return Optional.empty();
        URI uri = URI.create("https://api.weixin.qq.com/sns/jscode2session?appid=" + appId + "&secret=" + secret
                + "&js_code=" + code + "&grant_type=authorization_code");
        try {
            var reply = transport.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6)).GET().build());
            if (reply.status() != 200 || reply.body().length > 8192) throw unavailable();
            var body = AuditDocuments.JSON.readTree(reply.body());
            if (body == null || !body.isObject()) throw unavailable();
            if (body.has("errcode")) {
                var error = body.path("errcode");
                if (!error.isIntegralNumber()) throw unavailable();
                if (error.asInt() == 40029 || error.asInt() == 40163) return Optional.empty();
                if (error.asInt() != 0) throw unavailable();
            }
            var id = body.path("openid");
            if (!id.isTextual() || !id.asText().matches("[A-Za-z0-9_-]{1,128}")
                    || !body.path("session_key").isTextual() || body.path("session_key").asText().isBlank()) {
                throw unavailable();
            }
            return Optional.of("WX:" + AuditDocuments.hash(
                    AuditDocuments.JSON.getNodeFactory().textNode(appId + ":" + id.asText())));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException failure) {
            // Never include the request URI, provider response or exception cause in logs or API errors.
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("MINI_WECHAT_EXCHANGE_UNAVAILABLE");
    }
}
