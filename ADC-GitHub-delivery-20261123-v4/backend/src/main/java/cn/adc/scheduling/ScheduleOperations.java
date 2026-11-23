package cn.adc.scheduling;

import cn.adc.shared.audit.CommandRecovery;
import tools.jackson.databind.JsonNode;
import java.util.UUID;

public interface ScheduleOperations {
    JsonNode list(Long clinic, Long dentist, String from, String to, String cursor);
    JsonNode version(long id, int version);
    JsonNode issue(long id);
    JsonNode check(JsonNode input);
    CommandRecovery.Receipt create(UUID key, JsonNode input);
    CommandRecovery.Receipt revise(UUID key, long id, JsonNode input);
    CommandRecovery.Receipt recordIssue(UUID key, JsonNode input);
}
