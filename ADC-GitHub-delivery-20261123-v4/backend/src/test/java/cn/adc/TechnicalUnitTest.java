package cn.adc;

import cn.adc.platform.TechnicalController;
import cn.adc.platform.TechnicalErrorHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import static org.assertj.core.api.Assertions.assertThat;

/** ENG-001/002: unit tests without a Spring context or database. */
class TechnicalUnitTest {
 @Test void pingReportsTechnicalServiceOnly() {
  assertThat(new TechnicalController().ping()).containsExactlyInAnyOrderEntriesOf(
    java.util.Map.of("service", "adc-backend", "status", "UP"));
 }
 @Test void unknownFailureHasSafeMessageAndNoCache() {
  var response = new TechnicalErrorHandler().handle(new IllegalStateException("synthetic-secret"));
  assertThat(response.getStatusCode().value()).isEqualTo(500);
  assertThat(response.getBody()).containsEntry("code", "HTTP_500");
  assertThat(response.getBody().toString()).doesNotContain("synthetic-secret", "IllegalStateException");
  assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
 }
 @Test void methodBoundaryPreservesStatusAndAllowHeader() {
  var response = new TechnicalErrorHandler().handle(
    new HttpRequestMethodNotSupportedException("POST", java.util.List.of("GET")));
  assertThat(response.getStatusCode().value()).isEqualTo(405);
  assertThat(response.getBody()).containsEntry("code", "HTTP_405");
  assertThat(response.getHeaders().getFirst("Allow")).isEqualTo("GET");
  assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
 }
}
