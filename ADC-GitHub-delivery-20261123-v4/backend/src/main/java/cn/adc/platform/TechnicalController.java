package cn.adc.platform;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
/** Technical bootstrap only; no patient, staff or appointment operations. */
@RestController
public class TechnicalController {
 @GetMapping("/api/technical/ping")
 public Map<String, String> ping() { return Map.of("service", "adc-backend", "status", "UP"); }
}
