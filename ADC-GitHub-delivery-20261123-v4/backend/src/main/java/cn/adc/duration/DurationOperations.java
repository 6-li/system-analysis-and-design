package cn.adc.duration;
import cn.adc.shared.audit.CommandRecovery;import tools.jackson.databind.JsonNode;import java.util.*;
public interface DurationOperations {
 CommandRecovery.Receipt create(UUID key,JsonNode input);CommandRecovery.Receipt edit(UUID key,long id,int version,JsonNode input);CommandRecovery.Receipt publish(UUID key,long id,int version,JsonNode input);CommandRecovery.Receipt recover(String reference);JsonNode version(long id,int version);List<JsonNode> current();Map<String,Boolean> capabilities();String requestNamespace();
 default List<JsonNode> currentPage(int limit,long after){if(limit<1||limit>100||after<0)throw new IllegalArgumentException("INVALID_PAGE");return current().stream().filter(n->n.path("project_id").longValue()>after).limit(limit+1L).toList();}
}
