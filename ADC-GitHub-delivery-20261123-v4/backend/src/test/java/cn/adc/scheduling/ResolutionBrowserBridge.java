package cn.adc.scheduling;

import com.sun.net.httpserver.HttpServer;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Test-only loopback bridge between the built Vue page and the actual MVC dispatcher. */
public final class ResolutionBrowserBridge {
    static void verify(MockMvc mvc, MockHttpSession session) throws Exception {
        verify(mvc, session, false);
    }

    static void verify(MockMvc mvc, MockHttpSession session, boolean activation) throws Exception {
        verify(mvc, session, activation ? "activation" : "resolution");
    }

    public static void verify(MockMvc mvc, MockHttpSession session, String mode) throws Exception {
        run(mvc, session, mode, null);
    }

    @FunctionalInterface
    public interface NativeCheck {
        void run(int port, java.util.concurrent.CountDownLatch recovered) throws Exception;
    }

    public static void verifyNative(MockMvc mvc, MockHttpSession session, NativeCheck check) throws Exception {
        run(mvc, session, "native", check);
    }

    private static void run(MockMvc mvc, MockHttpSession session, String mode, NativeCheck nativeCheck) throws Exception {
        var staff = Path.of("../apps/staff-web").toAbsolutePath().normalize();
        var dist = staff.resolve("dist");
        var html = Files.readString(dist.resolve("index.html"))
                .replace("</head>", "<meta name=\"adc-uc22-csrf\" content=\"SYNTHETIC-CSRF\">"
                        + "<meta name=\"adc-uc21-csrf\" content=\"SYNTHETIC-CSRF\">"
                        + "<meta name=\"adc-uc01-csrf\" content=\"SYNTHETIC-CSRF\"></head>");
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        var secret = java.util.UUID.randomUUID().toString();
        var recovered = new java.util.concurrent.CountDownLatch(1);
        var hideFirstReply = new java.util.concurrent.atomic.AtomicBoolean(true);
        server.createContext("/", exchange -> {
            boolean receiptDelivered = false;
            try (exchange) {
                var uri = exchange.getRequestURI();
                String path = uri.getPath();
                boolean nativeMini = nativeCheck != null && (path.equals("/api/v1/mini/sessions/wechat")
                        || path.equals("/api/v1/mini/requests") || path.startsWith("/api/v1/mini/submission-receipts/"));
                if (!nativeMini && !secret.equals(exchange.getRequestHeaders().getFirst("X-Synthetic-Test"))) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                byte[] bytes;
                int status = 200;
                String type;
                if (nativeMini || path.startsWith("/api/v1/staff/") || ("patient".equals(mode) && path.startsWith("/api/v1/mini/"))) {
                    var content = exchange.getRequestBody().readNBytes(8193);
                    if (content.length > 8192 || (nativeMini && path.equals("/api/v1/mini/requests")
                            && !syntheticIntake(content))) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                    var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(exchange.getRequestMethod()), uri)
                            .content(content);
                    if (path.startsWith("/api/v1/staff/")) request.session(session);
                    exchange.getRequestHeaders().forEach((name, values) -> values.forEach(value -> request.header(name, value)));
                    var response = mvc.perform(request).andReturn().getResponse();
                    status = response.getStatus();
                    bytes = response.getContentAsByteArray();
                    if (nativeMini && path.equals("/api/v1/mini/requests") && status == 201
                            && hideFirstReply.getAndSet(false)) {
                        status = 503;
                        bytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    } else if (nativeMini && path.startsWith("/api/v1/mini/submission-receipts/") && status == 200
                            && "CONFIRMED_RECEIVED".equals(cn.adc.shared.audit.AuditDocuments
                            .parse(response.getContentAsString()).path("receiptStatus").asText())) {
                        receiptDelivered = true;
                    }
                    type = "application/json; charset=utf-8";
                } else if (path.matches("/assets/[A-Za-z0-9_-]+\\.(js|css)")) {
                    bytes = Files.readAllBytes(dist.resolve(path.substring(1)));
                    type = path.endsWith(".js") ? "text/javascript" : "text/css";
                } else if (path.equals("/staff/schedule-resolutions") || path.equals("/staff/schedule-workspace")
                        || ("patient".equals(mode) && path.equals("/staff/patients"))) {
                    bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    type = "text/html; charset=utf-8";
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception failure) {
                throw new java.io.IOException(failure);
            }
            if (receiptDelivered) recovered.countDown();
        });
        server.start();
        try {
            if (nativeCheck != null) {
                nativeCheck.run(server.getAddress().getPort(), recovered);
                return;
            }
            var builder = new ProcessBuilder("node", "browser-tests/fixtures/"
                    + ("patient".equals(mode) ? "patient-live.cjs" : "resolution-live.cjs"))
                    .directory(staff.toFile()).inheritIO();
            // Browser needs neither JDBC credentials nor the database port.
            builder.environment().keySet().removeIf(key -> key.startsWith("ADC_T028_") || key.startsWith("PG"));
            builder.environment().put("ADC_RESOLUTION_TEST_ORIGIN", "http://127.0.0.1:" + server.getAddress().getPort());
            builder.environment().put("ADC_RESOLUTION_TEST_SECRET", secret);
            builder.environment().put("ADC_RESOLUTION_TEST_MODE", mode);
            var browser = builder.start();
            try {
                if (!browser.waitFor(30, TimeUnit.SECONDS) || browser.exitValue() != 0) {
                    throw new AssertionError("Synthetic Edge/MVC/database verification failed");
                }
            } finally {
                if (browser.isAlive()) browser.destroyForcibly();
            }
        } finally {
            server.stop(0);
        }
    }

    private static boolean syntheticIntake(byte[] body) {
        try {
            var request = cn.adc.shared.audit.AuditDocuments.parse(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            var identity = request.path("identity");
            return java.util.Set.of("81001", "81002").contains(request.path("intendedClinicId").asText())
                    && "SYNTHETIC-UC01-identity".equals(identity.path("patientIdentification").asText())
                    && "SYNTHETIC".equals(identity.path("applicantInformation").asText())
                    && "SYNTHETIC".equals(identity.path("contactInformation").asText())
                    && "SYNTHETIC".equals(request.path("treatmentNeed").asText());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
