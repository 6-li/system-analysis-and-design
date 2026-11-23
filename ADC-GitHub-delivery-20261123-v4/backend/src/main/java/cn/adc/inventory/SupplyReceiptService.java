package cn.adc.inventory;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore.EventIdAllocator;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

final class SupplyReceiptService implements CommandRecovery.SuccessVerifier {
    private static final String UC = "UC15";
    static final String OPERATION = "SUPPLY_RECEIPT_RECORD";
    private static final String PENDING = "差异待处理";
    private static final String ACCEPTED = "已入库";
    record Target(long clinic, long requirement) {}
    record Delivery(long receiptId, long clinic, long requirement, String lookup, String externalReference) {}
    record Input(String lookup, boolean accepted, String result, String discrepancy, BigDecimal received,
                 BigDecimal cost, LocalDate expiry) {}
    record Receipt(long id, long requirement, Long stock, String external, String result, String discrepancy,
                   String status, BigDecimal quantity, BigDecimal cost, LocalDate expiry) {}
    record Inventory(long stock, BigDecimal balance) {}

    /** The trusted source must return the same globally allocated receipt ID for every reference to one delivery. */
    interface Deliveries {
        Optional<Delivery> find(Connection c, String lookup) throws SQLException;
    }

    private final StaffAuthorization auth;
    private final Deliveries deliveries;
    private final Clock clock;
    private final CommandRecovery commands;

    SupplyReceiptService(JdbcTransactions tx, StaffAuthorization auth, Deliveries deliveries,
                         EventIdAllocator auditIds, Clock clock) {
        this.auth = auth;
        this.deliveries = deliveries;
        this.clock = clock;
        this.commands = new UcAuditService(tx, auth, this, clock, auditIds).commands();
    }

    CommandRecovery.Receipt save(Target target, UUID key, Input input) {
        validate(input);
        var payload = JSON.createObjectNode().put("requirementId", target.requirement()).put("deliveryLookup", input.lookup())
                .put("accepted", input.accepted()).put("result", input.result()).put("discrepancy", input.discrepancy())
                .put("received", decimal(input.received())).put("cost", money(input.cost()))
                .put("expiry", input.expiry() == null ? null : input.expiry().toString());
        return commands.execute(new CommandRecovery.Command(OPERATION, UC, key, payload, Set.of(target.clinic())),
                (c, locks) -> save(c, target, input));
    }

    private static void validate(Input input) {
        if (blank(input.lookup()) || blank(input.result()) || (!input.accepted() && blank(input.discrepancy()))
                || (input.accepted() && (input.received() == null || input.cost() == null || input.expiry() == null))
                || (!input.accepted() && (input.received() != null || input.cost() != null || input.expiry() != null))
                || !validDecimal(input.received(), 3, true) || !validDecimal(input.cost(), 2, false)) {
            throw new Rejection("INVALID_RECEIPT_INPUT");
        }
    }

    private static boolean validDecimal(BigDecimal value, int scale, boolean positive) {
        return value == null || value.scale() >= 0 && value.scale() <= scale && value.precision() - value.scale() <= 18 - scale
                && (positive ? value.signum() > 0 : value.signum() >= 0);
    }

    AuditDocuments.Change save(Connection c, Target target, Input input) throws SQLException {
        validate(input);
        auth.require(UC, Set.of(target.clinic()));
        var source = delivery(c, target, input.lookup());
        try (var sql = c.prepareStatement("SELECT pg_advisory_xact_lock(41015,?)")) {
            sql.setInt(1, (int) ResourceLockCoordinator.key("supply-receipt:" + source.receiptId()));
            sql.execute();
        }
        String actor = auth.require(UC, Set.of(target.clinic())).actor();
        if (!source.equals(delivery(c, target, input.lookup()))) throw new Rejection("DELIVERY_EVIDENCE_CHANGED");
        long supply = requireSupply(c, target);
        var prior = load(c, source.receiptId()).orElse(null);
        if (prior != null && (prior.requirement() != target.requirement()
                || prior.external() != null && source.externalReference() != null
                        && !prior.external().equals(source.externalReference()))) {
            throw new Rejection("DELIVERY_ALREADY_LINKED");
        }
        if (prior != null && (ACCEPTED.equals(prior.status()) || !input.accepted())) {
            // The stored outcome of this delivery is authoritative, including requests with another key.
            return evidence(target, prior, null, null);
        }

        Inventory inventory = null;
        if (input.accepted()) inventory = increase(c, target.clinic(), supply, input.received());
        String discrepancy = input.discrepancy() != null ? input.discrepancy() : prior == null ? null : prior.discrepancy();
        String external = source.externalReference() != null ? source.externalReference() : prior == null ? null : prior.external();
        var saved = new Receipt(source.receiptId(), target.requirement(), inventory == null ? null : inventory.stock(),
                external, input.result(), discrepancy, input.accepted() ? ACCEPTED : PENDING,
                input.received(), input.cost(), input.expiry());
        write(c, prior, saved, actor);
        return evidence(target, saved, prior, inventory);
    }

    private Delivery delivery(Connection c, Target target, String lookup) throws SQLException {
        var row = deliveries.find(c, lookup).orElseThrow(() -> new Rejection("DELIVERY_EVIDENCE_REQUIRED"));
        if (row.receiptId() <= 0 || row.clinic() != target.clinic() || row.requirement() != target.requirement()
                || !lookup.equals(row.lookup()) || (row.externalReference() != null && row.externalReference().length() > 200)) {
            throw new Rejection("DELIVERY_SCOPE_MISMATCH");
        }
        return row;
    }

    private static long requireSupply(Connection c, Target target) throws SQLException {
        try (var sql = c.prepareStatement("""
                SELECT supply_id FROM adc.purchase_requirement WHERE purchase_requirement_id=? AND clinic_id=?
                """)) {
            sql.setLong(1, target.requirement());
            sql.setLong(2, target.clinic());
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new Rejection("PURCHASE_SCOPE_NOT_FOUND");
                return row.getLong(1);
            }
        }
    }

    private static Inventory increase(Connection c, long clinic, long supply, BigDecimal received) throws SQLException {
        try (var sql = c.prepareStatement("""
                UPDATE adc.medical_supply_stock SET on_hand_quantity=on_hand_quantity+?
                WHERE clinic_id=? AND supply_id=?
                RETURNING stock_id,on_hand_quantity
                """)) {
            sql.setBigDecimal(1, received);
            sql.setLong(2, clinic);
            sql.setLong(3, supply);
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new Rejection("VERIFIED_INVENTORY_REQUIRED");
                var updated = new Inventory(row.getLong(1), row.getBigDecimal(2));
                if (row.next()) throw new Rejection("STOCK_SCOPE_AMBIGUOUS");
                return updated;
            }
        }
    }

    private void write(Connection c, Receipt prior, Receipt saved, String actor) throws SQLException {
        String insert = """
                INSERT INTO adc.supply_receipt
                    (stock_id,delivery_reference,acceptance_result,delivery_discrepancy,discrepancy_status,
                     received_quantity,purchase_cost,expiry_date,registered_by,registered_at,receipt_id,purchase_requirement_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        String update = """
                UPDATE adc.supply_receipt SET stock_id=?,delivery_reference=?,acceptance_result=?,delivery_discrepancy=?,
                    discrepancy_status=?,received_quantity=?,purchase_cost=?,expiry_date=?,registered_by=?,registered_at=?
                WHERE receipt_id=? AND purchase_requirement_id=? AND discrepancy_status='差异待处理' AND stock_id IS NULL
                """;
        try (var sql = c.prepareStatement(prior == null ? insert : update)) {
            sql.setObject(1, saved.stock(), java.sql.Types.BIGINT);
            sql.setString(2, saved.external());
            sql.setString(3, saved.result());
            sql.setString(4, saved.discrepancy());
            sql.setString(5, saved.status());
            sql.setBigDecimal(6, saved.quantity());
            sql.setBigDecimal(7, saved.cost());
            sql.setObject(8, saved.expiry(), java.sql.Types.DATE);
            sql.setString(9, actor);
            sql.setObject(10, StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC));
            sql.setLong(11, saved.id());
            sql.setLong(12, saved.requirement());
            if (sql.executeUpdate() != 1) throw new SQLException("SUPPLY_RECEIPT_WRITE_UNCONFIRMED", "P0001");
        }
    }

    private static Optional<Receipt> load(Connection c, long receipt) throws SQLException {
        try (var sql = c.prepareStatement("""
                SELECT purchase_requirement_id,stock_id,delivery_reference,acceptance_result,delivery_discrepancy,
                       discrepancy_status,received_quantity,purchase_cost,expiry_date
                FROM adc.supply_receipt WHERE receipt_id=?
                """)) {
            sql.setLong(1, receipt);
            try (var row = sql.executeQuery()) {
                return row.next() ? Optional.of(new Receipt(receipt, row.getLong(1), row.getObject(2, Long.class),
                        row.getString(3), row.getString(4), row.getString(5), row.getString(6), row.getBigDecimal(7),
                        row.getBigDecimal(8), row.getObject(9, LocalDate.class))) : Optional.empty();
            }
        }
    }

    private static AuditDocuments.Change evidence(Target target, Receipt saved, Receipt prior, Inventory inventory) {
        var ref = JSON.createObjectNode().put("receiptId", Long.toString(saved.id()))
                .put("clinicId", Long.toString(target.clinic())).put("requirementId", Long.toString(target.requirement()));
        var snapshot = document(saved);
        var proof = JSON.createObjectNode().put("receiptHash", AuditDocuments.hash(snapshot));
        snapshot.put("clinicId", Long.toString(target.clinic()))
                .put("stockBalanceAfter", inventory == null ? null : decimal(inventory.balance()));
        return new AuditDocuments.Change(ref, prior == null ? null : document(prior), snapshot, proof);
    }

    private static tools.jackson.databind.node.ObjectNode document(Receipt row) {
        return JSON.createObjectNode().put("receiptId", Long.toString(row.id())).put("requirementId", Long.toString(row.requirement()))
                .put("stockId", row.stock() == null ? null : row.stock().toString()).put("status", row.status())
                .put("deliveryReference", row.external()).put("acceptanceResult", row.result()).put("discrepancy", row.discrepancy())
                .put("received", decimal(row.quantity())).put("cost", money(row.cost()))
                .put("expiry", row.expiry() == null ? null : row.expiry().toString());
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private static String money(BigDecimal value) { return value == null ? null : value.setScale(2).toPlainString(); }

    @Override public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
        if (!UC.equals(command.ucCode()) || !OPERATION.equals(command.operation()) || command.clinics().size() != 1) return false;
        long clinic = command.clinics().iterator().next();
        auth.require(UC, Set.of(clinic));
        var target = new Target(clinic, command.payload().path("requirementId").asLong());
        requireSupply(c, target);
        var ref = saved.path("businessReference");
        var prior = saved.path("after");
        if (ref.path("clinicId").asLong() != clinic || ref.path("requirementId").asLong() != target.requirement()) return false;
        var row = load(c, ref.path("receiptId").asLong());
        return row.isPresent() && row.get().requirement() == target.requirement()
                && (AuditDocuments.hash(document(row.get())).equals(saved.path("verification").path("receiptHash").asText())
                || PENDING.equals(prior.path("status").asText()) && ACCEPTED.equals(row.get().status()));
    }

    @Override public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        return true;
    }
}
