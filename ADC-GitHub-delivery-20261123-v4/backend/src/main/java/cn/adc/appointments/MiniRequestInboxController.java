package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import static cn.adc.shared.audit.AuditDocuments.JSON;

@RestController
@ConditionalOnProperty(name="adc.course.inbox-enabled",havingValue="true")
public final class MiniRequestInboxController {
    public interface Access { MiniRequestInboxService open(HttpServletRequest request); }
    private final Access access;
    public MiniRequestInboxController(Access access){this.access=access;}
    @GetMapping("/api/v1/staff/mini-requests")
    public ResponseEntity<JsonNode> list(HttpServletRequest request,@RequestParam(required=false)String cursor){
        try{return response(200,access.open(request).list(cursor));}
        catch(SecurityException denied){return response(403,JSON.createObjectNode().put("code","INBOX_ACCESS_DENIED"));}
        catch(IllegalArgumentException invalid){return response(400,JSON.createObjectNode().put("code","INVALID_INBOX_QUERY"));}
        catch(JdbcTransactions.Failure unavailable){return response(503,JSON.createObjectNode().put("code","INBOX_SOURCE_UNAVAILABLE"));}
    }
    private static ResponseEntity<JsonNode> response(int status,JsonNode body){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);}
}
