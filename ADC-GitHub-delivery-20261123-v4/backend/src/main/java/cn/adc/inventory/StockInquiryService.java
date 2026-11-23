package cn.adc.inventory;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore.EventIdAllocator;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

final class StockInquiryService implements CommandRecovery.SuccessVerifier {
    private static final String UC = "UC13";
    static final String OPERATION = "STOCK_INQUIRY";
    private final StaffAuthorization auth;
    private final Clock clock;
    private final CommandRecovery commands;

    StockInquiryService(JdbcTransactions tx, StaffAuthorization auth, EventIdAllocator auditIds, Clock clock) {
        this.auth = auth;
        this.clock = clock;
        this.commands = new UcAuditService(tx, auth, this, clock, auditIds).commands();
    }

    CommandRecovery.Receipt query(long clinic, Long supply, UUID key) {
        var payload = JSON.createObjectNode();
        if (supply == null) payload.putNull("supplyId");
        else payload.put("supplyId", Long.toString(supply));
        return commands.execute(new CommandRecovery.Command(OPERATION, UC, key, payload, Set.of(clinic)),
                (c, locks) -> snapshot(c, clinic, supply));
    }

    AuditDocuments.Change snapshot(Connection c, long clinic, Long supply) throws SQLException {
        auth.require(UC, Set.of(clinic));
        var target = JSON.createObjectNode().put("clinicId", Long.toString(clinic));
        if (supply == null) target.putNull("supplyId");
        else target.put("supplyId", Long.toString(supply));
        var result = target.deepCopy().put("queriedAt", StrictInputPolicy.eventTime(clock).toString());
        var items = result.putArray("items");
        String sql = """
                SELECT m.supply_id,m.supply_name,m.stock_unit,s.stock_id,s.on_hand_quantity,
                       s.reorder_threshold,s.existing_warning
                FROM adc.medical_supply m
                LEFT JOIN adc.medical_supply_stock s ON s.supply_id=m.supply_id AND s.clinic_id=?
                """ + (supply == null ? "" : " WHERE m.supply_id=?") + " ORDER BY m.supply_name,m.supply_id";
        try (var statement = c.prepareStatement(sql)) {
            statement.setLong(1, clinic);
            if (supply != null) statement.setLong(2, supply);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    long stockId = rows.getLong(4);
                    boolean stockPresent = !rows.wasNull();
                    var item = items.addObject().put("supplyId", Long.toString(rows.getLong(1)))
                            .put("name", rows.getString(2)).put("unit", rows.getString(3))
                            .put("stockPresent", stockPresent);
                    if (stockPresent) item.put("stockId", Long.toString(stockId));
                    else item.putNull("stockId");
                    item.put("onHand", decimal(rows.getBigDecimal(5))).put("threshold", decimal(rows.getBigDecimal(6)))
                            .put("existingWarning", rows.getString(7));
                }
            }
        }
        auth.require(UC, Set.of(clinic));
        return new AuditDocuments.Change(target, null, result,
                JSON.createObjectNode().put("snapshotHash", AuditDocuments.hash(result)));
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    @Override public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) {
        if (!UC.equals(command.ucCode()) || !OPERATION.equals(command.operation()) || command.clinics().size() != 1) return false;
        long clinic = command.clinics().iterator().next();
        auth.require(UC, Set.of(clinic));
        var actual = saved.path("after");
        var target = saved.path("businessReference");
        return Long.toString(clinic).equals(target.path("clinicId").asText())
                && target.path("clinicId").equals(actual.path("clinicId"))
                && command.payload().path("supplyId").equals(actual.path("supplyId"))
                && target.path("supplyId").equals(actual.path("supplyId"))
                && AuditDocuments.hash(actual).equals(saved.path("verification").path("snapshotHash").asText());
    }

    @Override public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        return false;
    }
}
