package cn.adc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.*;
@Import(TechnicalHttpTest.FailureProbe.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TechnicalHttpTest {
 @RestController
 static class FailureProbe {
  @GetMapping("/api/technical/test-failure")
  String fail() { throw new IllegalStateException("INTERNAL_SECRET_TEST_MARKER"); }
 }
 @LocalServerPort int port;
 private HttpResponse<String> request(String path, String method) throws Exception {
  return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
   .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
 }
 @Test void healthDoesNotExposeDatabaseOrDetails() throws Exception {
  var response=request("/actuator/health", "GET");
  assertEquals(200,response.statusCode()); assertTrue(response.body().contains("UP"));
  assertFalse(response.body().contains("components")); assertFalse(response.body().contains("db"));
 }
 @Test void pingWorksWithoutDatabase() throws Exception {
  var response=request("/api/technical/ping","GET");
  assertEquals(200,response.statusCode()); assertTrue(response.body().contains("adc-backend"));
 }
 @Test void unknownBusinessRouteIsNotImplemented() throws Exception {
  var response=request("/api/appointments?secret=DO_NOT_EXPOSE","GET");
  assertEquals(404,response.statusCode()); assertFalse(response.body().contains("DO_NOT_EXPOSE"));
 }
 @Test void internalFailuresDoNotExposeExceptionMessages() throws Exception {
  var response=request("/api/technical/test-failure","GET");
  assertEquals(500,response.statusCode()); assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store")); assertTrue(response.body().contains("HTTP_500"));
  assertFalse(response.body().contains("INTERNAL_SECRET_TEST_MARKER"));
  assertFalse(response.body().contains("IllegalStateException"));
 }
 @Test void methodErrorsAreSanitized() throws Exception {
  var response=request("/api/technical/ping","POST");
  assertEquals(405,response.statusCode()); assertTrue(response.headers().firstValue("Allow").orElse("").contains("GET")); assertTrue(response.body().contains("HTTP_405"));
  assertFalse(response.body().contains("stackTrace"));
 }
}
