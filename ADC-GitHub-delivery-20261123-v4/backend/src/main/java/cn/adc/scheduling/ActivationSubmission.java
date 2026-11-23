package cn.adc.scheduling;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Internal entry point for SCH-OP-009. Only the server supplies the final scan. */
final class ActivationSubmission {
    private static final Set<String> FIELDS = Set.of("expectedDraftToken", "previousImpactCheckToken", "coordinationBasis");
    private final JdbcTransactions transactions;
    private final StaffAuthorization authorization;
    private final Clock clock;
    private final ScheduleActivationService service = new ScheduleActivationService();
    private final CommandRecovery journal;

    ActivationSubmission(JdbcTransactions transactions, StaffAuthorization authorization, Clock clock,
                         AuditStore.EventIdAllocator auditIds) {
        this(transactions,authorization,clock,auditIds,false);
    }
    ActivationSubmission(JdbcTransactions transactions, StaffAuthorization authorization, Clock clock,
                         AuditStore.EventIdAllocator auditIds,boolean courseUc22Narrow) {
        this.transactions = transactions;
        this.authorization = authorization;
        this.clock = clock;
        this.journal = service.bind(transactions, authorization, clock, auditIds,courseUc22Narrow);
    }

    CommandRecovery.Receipt activate(long schedule, int version, String key, JsonNode input,
                                     ScheduleActivationScan scan) {
        if (schedule <= 0 || version <= 0 || key.isBlank() || !input.isObject()
                || !FIELDS.containsAll(input.propertyNames())) throw new IllegalArgumentException("INVALID_ACTIVATION");
        String draftToken = AuditDocuments.text(input, "expectedDraftToken");
        String impactToken = input.has("previousImpactCheckToken")
                ? AuditDocuments.text(input, "previousImpactCheckToken") : null;
        if (input.has("coordinationBasis")) AuditDocuments.text(input, "coordinationBasis");
        var versions = transactions.execute(c -> new JdbcScheduleStore().versions(c, schedule));
        var draft = versions.stream().filter(row -> row.versionNo() == version).findFirst()
                .orElseThrow(NoSuchElementException::new);
        var clinics = versions.stream().map(ScheduleStateToken.Version::clinicId).collect(Collectors.toUnmodifiableSet());
        authorization.require("UC22", clinics);
        var payload = AuditDocuments.JSON.createObjectNode().put("scheduleId", Long.toString(schedule))
                .put("versionNo", version).put("checkToken", impactToken);
        payload.set("input", input);
        var requestKey = UUID.nameUUIDFromBytes(("UC22/activate/v1/" + key).getBytes(StandardCharsets.UTF_8));
        var command = new CommandRecovery.Command("ACTIVATE", "UC22", requestKey, payload, clinics);
        return journal.execute(command, service.work(draft, draftToken, impactToken, authorization, (c, target) -> {
            var observation = scan.read(c, target);
            if (impactToken != null && !impactToken.equals(scan.impactToken(c, target, observation.effective()))) {
                throw new CommandRecovery.Rejection("ACTIVATION_CHECK_STALE");
            }
            return observation;
        }, clock));
    }
}
