package cn.adc.platform;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;
@RestControllerAdvice
public class TechnicalErrorHandler {
 private static final Logger LOG = LoggerFactory.getLogger(TechnicalErrorHandler.class);
 @ExceptionHandler(Exception.class)
 public ResponseEntity<Map<String, Object>> handle(Exception exception) {
  int status = exception instanceof ErrorResponse error ? error.getStatusCode().value() : 500;
  // Do not log request bodies, query strings, exception messages or secrets.
  if (status >= 500) LOG.error("Technical request failed; type={}", exception.getClass().getSimpleName());
  HttpHeaders headers = exception instanceof ErrorResponse error ? error.getHeaders() : new HttpHeaders();
  return ResponseEntity.status(status).headers(headers).cacheControl(CacheControl.noStore()).body(Map.of("status", status,
    "code", "HTTP_" + status, "message", status >= 500 ? "服务暂不可用" : "请求无法处理"));
 }
}
