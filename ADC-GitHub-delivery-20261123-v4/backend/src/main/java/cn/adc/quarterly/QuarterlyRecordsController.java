package cn.adc.quarterly;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import static cn.adc.quarterly.QuarterlyCalculationController.*;

@RestController
@ConditionalOnProperty(name="adc.course.quarterly-usability-enabled",havingValue="true")
@RequestMapping("/api/v1/staff/quarterly/records")
final class QuarterlyRecordsController {
    interface Access { QuarterlyRecordsService open(HttpServletRequest req,String csrf); }
    private final Access access;
    QuarterlyRecordsController(Access access){this.access=access;}
    @GetMapping("/previews") ResponseEntity<JsonNode> previews(HttpServletRequest req,@RequestHeader("X-CSRF-Token") String csrf,@RequestParam String quarter,@RequestParam String clinics,@RequestParam(defaultValue="9223372036854775807") String beforeId,@RequestParam(defaultValue="20") int limit){return response(200,access.open(req,csrf).list(true,quarter,scope(clinics),id(beforeId),limit));}
    @GetMapping("/calculations") ResponseEntity<JsonNode> calculations(HttpServletRequest req,@RequestHeader("X-CSRF-Token") String csrf,@RequestParam String quarter,@RequestParam String clinics,@RequestParam(defaultValue="9223372036854775807") String beforeId,@RequestParam(defaultValue="20") int limit){return response(200,access.open(req,csrf).list(false,quarter,scope(clinics),id(beforeId),limit));}
    @GetMapping("/previews/{statisticId}") ResponseEntity<JsonNode> preview(HttpServletRequest req,@RequestHeader("X-CSRF-Token") String csrf,@PathVariable String statisticId,@RequestParam String clinics){return response(200,access.open(req,csrf).preview(id(statisticId),scope(clinics)));}
    @GetMapping("/calculations/{calculationId}") ResponseEntity<JsonNode> calculation(HttpServletRequest req,@RequestHeader("X-CSRF-Token") String csrf,@PathVariable String calculationId,@RequestParam int version,@RequestParam String clinics){return response(200,access.open(req,csrf).calculation(id(calculationId),version,scope(clinics)));}
    @ExceptionHandler(SecurityException.class) ResponseEntity<JsonNode> denied(SecurityException e){return error("AUTHENTICATION_REQUIRED".equals(e.getMessage())?401:403,"QUARTER_RECORD_ACCESS_DENIED",null);}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<JsonNode> invalid(){return error(400,"QUARTER_RECORD_UNAVAILABLE",null);}
    @ExceptionHandler(JdbcTransactions.Failure.class) ResponseEntity<JsonNode> unavailable(){return error(503,"QUARTER_RECORD_UNAVAILABLE",null);}
}
