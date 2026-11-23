package cn.adc.scheduling;

import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.config.ConfigurationUnavailable;
import cn.adc.shared.db.JdbcTransactions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.JsonNode;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static cn.adc.shared.audit.AuditDocuments.JSON;

@RestController
@ConditionalOnProperty(name="adc.uc21.enabled",havingValue="true")
@RequestMapping("/api/v1/staff")
public final class ScheduleController {
    private final ScheduleAccess access;
    public ScheduleController(ScheduleAccess access){this.access=access;}

    @GetMapping("/schedules")
    public ResponseEntity<?> list(HttpServletRequest request,@RequestParam(required=false) String clinicId,
                                  @RequestParam(required=false) String dentistId,@RequestParam(required=false) String from,
                                  @RequestParam(required=false) String to,@RequestParam(required=false) String cursor){
        return response(200,read(request).list(parseOptional(clinicId),parseOptional(dentistId),from,to,cursor));
    }
    @GetMapping("/schedules/{scheduleId}/versions/{versionNo}")
    public ResponseEntity<?> version(HttpServletRequest request,@PathVariable long scheduleId,@PathVariable int versionNo){
        return response(200,read(request).version(scheduleId,versionNo));
    }
    @GetMapping("/schedule-maintenance-issues/{issueId}")
    public ResponseEntity<?> issue(HttpServletRequest request,@PathVariable long issueId){
        return response(200,read(request).issue(issueId));
    }
    @PostMapping("/schedule-checks")
    public ResponseEntity<?> check(HttpServletRequest request,@RequestHeader("Idempotency-Key") String key,
                                   @RequestHeader("X-CSRF-Token") String csrf,@RequestBody JsonNode body){
        var service=write(request,csrf);parseKey(key);
        return response(200,service.check(body));
    }
    @PostMapping("/schedule-drafts")
    public ResponseEntity<?> create(HttpServletRequest request,@RequestHeader("Idempotency-Key") String key,
                                    @RequestHeader("X-CSRF-Token") String csrf,@RequestBody JsonNode body){
        return receipt(write(request,csrf).create(parseKey(key),body),ScheduleDraftService.CREATE);
    }
    @PostMapping("/schedules/{scheduleId}/draft-revisions")
    public ResponseEntity<?> revise(HttpServletRequest request,@PathVariable long scheduleId,
                                    @RequestHeader("Idempotency-Key") String key,@RequestHeader("X-CSRF-Token") String csrf,
                                    @RequestBody JsonNode body){
        return receipt(write(request,csrf).revise(parseKey(key),scheduleId,body),ScheduleDraftService.REVISE);
    }
    @PostMapping("/schedule-maintenance-issues")
    public ResponseEntity<?> recordIssue(HttpServletRequest request,@RequestHeader("Idempotency-Key") String key,
                                         @RequestHeader("X-CSRF-Token") String csrf,@RequestBody JsonNode body){
        return receipt(write(request,csrf).recordIssue(parseKey(key),body),ScheduleDraftService.ISSUE);
    }

    private ScheduleOperations read(HttpServletRequest r){
        if(access.identity(r).current().isEmpty())throw new SecurityException("AUTHENTICATION_REQUIRED");
        return access.service(r);
    }
    private ScheduleOperations write(HttpServletRequest r,String csrf){
        access.identity(r).requireCsrf(csrf);
        return read(r);
    }
    private static UUID parseKey(String key){
        if(key.isBlank())throw new IllegalArgumentException("INVALID_IDEMPOTENCY_KEY");
        // The contract accepts opaque keys. Namespace their internal UUID representation.
        return UUID.nameUUIDFromBytes(("UC21/v1/idempotency/"+key).getBytes(StandardCharsets.UTF_8));
    }
    private static Long parseOptional(String s){return s==null?null:ScheduleDraftService.decimal(s);}

    static ResponseEntity<?> receipt(CommandRecovery.Receipt receipt,String operation){
        boolean issue=ScheduleDraftService.ISSUE.equals(operation);
        String ref=receipt.requestReference();
        boolean completed="SUCCEEDED".equals(receipt.businessOutcome());
        if("UNKNOWN".equals(receipt.businessOutcome())){
            if("WORK_ROLLBACK_UNCONFIRMED".equals(receipt.reason()))
                return error(503,"WORK_ROLLBACK_UNCONFIRMED","事务结束待核对，请保留请求引用并勿自动重试",ref);
            if(receipt.auditPersistence()==CommandRecovery.Persistence.NOT_PERSISTED&&!receipt.existingResult())
                return error(503,"DEPENDENCY_UNAVAILABLE","当前无法开始或核对该操作",ref);
            if(issue)return error(503,"COMMIT_OUTCOME_UNKNOWN","提交结果待核对，请保留请求引用并勿自动重试",ref);
            return response(202,receiptBody(receipt,operation,"UNKNOWN","UNKNOWN","提交结果待核对，请勿自动重试"));
        }
        if(completed){
            var r=receipt.result();
            if(receipt.successEventId()==null||r==null||
                    !(issue?r.path("issueId").isString():r.path("scheduleRef").isObject()
                            &&ScheduleDraftService.validAppointmentImpact(r.path("appointmentImpact")))){
                if(issue)return error(503,"COMMIT_OUTCOME_UNKNOWN","提交结果待核对，请勿自动重试",ref);
                return response(202,receiptBody(receipt,operation,"UNKNOWN","UNKNOWN","提交结果待核对，请勿自动重试"));
            }
            var body=receiptBody(receipt,operation,issue?"PERSISTED_INCOMPLETE":"COMPLETED",
                    issue?"MAINTENANCE_INCOMPLETE":"DRAFT",issue?"已记录未完成事项":"已保存待生效草案");
            body.put("existingResult",receipt.existingResult());
            var links=body.putObject("links").put("originalUcSuccessEventId",receipt.successEventId());
            if(issue)links.put("issueId",r.path("issueId").asText());
            else {
                links.set("scheduleRef",r.path("scheduleRef"));
                body.set("appointmentImpact",r.path("appointmentImpact"));
            }
            return response(200,body);
        }
        String reason=receipt.reason();
        if("EXECUTION_PERMISSION_DENIED".equals(reason)||"RECOVERY_PERMISSION_DENIED".equals(reason))
            return error(403,"FORBIDDEN",failureMessage(receipt),ref);
        if("EXISTING_BASIS_REQUIRES_REVISION".equals(reason)||"COORDINATION_REQUIRED".equals(reason)
                ||"EFFECTIVE_SCHEDULE_CONFLICT".equals(reason)
                ||"HISTORICAL_BASIS_NOT_ELIGIBLE".equals(reason)||"INVALID_WINDOW".equals(reason)
                ||"OFFSET_NOT_VALID_FOR_CLINIC".equals(reason))return error(422,reason,failureMessage(receipt),ref);
        if("FAILED".equals(receipt.businessOutcome()))return error(503,"OPERATION_FAILED",failureMessage(receipt),ref);
        return error(409,"STALE_VERSION".equals(reason)?reason:"REQUEST_CONFLICT",failureMessage(receipt),ref);
    }
    private static tools.jackson.databind.node.ObjectNode receiptBody(CommandRecovery.Receipt r,String operation,String status,String outcome,String message){
        return JSON.createObjectNode().put("requestReference",r.requestReference()).put("ucCode","UC21")
                .put("status",status).put("operationKind",operation).put("businessOutcome",outcome)
                .put("auditPersistence",r.auditPersistence().name()).put("message",message);
    }
    private static String failureMessage(CommandRecovery.Receipt r){
        return switch(r.auditPersistence()){
            case NOT_PERSISTED -> "操作未完成，失败审计未持久化";
            case UNKNOWN -> "操作未完成，失败审计待核对";
            case CONFIRMED -> "操作未完成，请核对输入和当前版本";
        };
    }
    @ExceptionHandler(SecurityException.class)
    ResponseEntity<?> denied(SecurityException e){
        int status="AUTHENTICATION_REQUIRED".equals(e.getMessage())?401:403;
        return error(status,status==401?"AUTHENTICATION_REQUIRED":"FORBIDDEN","当前身份无法办理",null);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException e){
        if("NOT_FOUND".equals(e.getMessage()))return error(404,"NOT_FOUND","安排或事项不可用",null);
        if("BASIS_MISMATCH".equals(e.getMessage()))return error(409,"BASIS_MISMATCH","路径与基础版本不符",null);
        if("ISSUE_BASIS_UNVERIFIED".equals(e.getMessage()))
            return error(409,"ISSUE_BASIS_UNVERIFIED","无法核实事项关联或基础版本",null);
        return error(400,"INVALID_INPUT","请核对输入",null);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,ServletRequestBindingException.class,MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed(){return error(400,"INVALID_INPUT","请核对输入",null);}
    @ExceptionHandler({JdbcTransactions.Failure.class,ConfigurationUnavailable.class})
    ResponseEntity<?> unavailable(){return error(503,"DEPENDENCY_UNAVAILABLE","安排服务暂不可用",null);}
    private static ResponseEntity<?> error(int status,String code,String message,String ref){
        var body=JSON.createObjectNode().put("code",code).put("message",message);
        if(ref!=null)body.put("requestReference",ref);
        return response(status,body);
    }
    private static ResponseEntity<?> response(int status,JsonNode body){
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
