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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

final class SupplyConsumptionService implements CommandRecovery.SuccessVerifier {
    private static final String UC = "UC16";
    static final String OPERATION = "SUPPLY_CONSUMPTION_RECORD";
    record Target(long clinic, long supply) {}
    record Input(String proofReference, BigDecimal quantity, Instant occurredAt) {}
    record Evidence(long id, Target target, BigDecimal quantity, Instant occurredAt, BigDecimal cost,
                    String pricingBasis, String verifiedBy, String sourceReference) {}
    record Stored(long id, long stock, BigDecimal quantity, BigDecimal cost, String document,
                  String verifiedBy, Instant occurredAt, String sourceReference) {}

    /** Resolve an authorized, verified cost fact. All aliases for one consumption must share a stable table42 ID. */
    interface CostFacts {
        Optional<Evidence> find(Connection connection, String reference) throws SQLException;
    }

    private final StaffAuthorization auth;
    private final CostFacts facts;
    private final Clock clock;
    private final CommandRecovery commands;

    SupplyConsumptionService(JdbcTransactions tx, StaffAuthorization auth, CostFacts facts, EventIdAllocator auditIds, Clock clock) {
        this.auth = auth;
        this.facts = facts;
        this.clock = clock;
        this.commands = new UcAuditService(tx, auth, this, clock, auditIds).commands();
    }

    CommandRecovery.Receipt save(Target target, UUID key, Input input) {
        validate(input);
        var payload = JSON.createObjectNode().put("supplyId", target.supply()).put("proofReference", input.proofReference())
                .put("quantity", quantity(input.quantity())).put("occurredAt", input.occurredAt().toString());
        return commands.execute(new CommandRecovery.Command(OPERATION, UC, key, payload, Set.of(target.clinic())),
                (c, locks) -> save(c, target, input));
    }

    private static void validate(Input input) {
        if (blank(input.proofReference()) || !fits(input.quantity(), 3) || input.quantity().signum() <= 0
                || input.occurredAt() == null || input.occurredAt().getNano() % 1_000 != 0) {
            throw new Rejection("INVALID_CONSUMPTION_INPUT");
        }
    }

    AuditDocuments.Change save(Connection c, Target target, Input input) throws SQLException {
        validate(input);
        auth.require(UC, Set.of(target.clinic()));
        var verified = verify(c, target, input);
        try (var sql = c.prepareStatement("SELECT pg_advisory_xact_lock(41016,?)")) {
            sql.setInt(1, (int) ResourceLockCoordinator.key("supply-consumption:" + verified.id()));
            sql.execute();
        }
        String actor = auth.require(UC, Set.of(target.clinic())).actor();
        if (!verified.equals(verify(c, target, input))) throw new Rejection("CONSUMPTION_COST_CHANGED");
        if (load(c, target, verified.id()).isPresent()) throw new Rejection("CONSUMPTION_ALREADY_RECORDED");

        long stock;
        BigDecimal balance;
        try (var sql = c.prepareStatement("""
                UPDATE adc.medical_supply_stock SET on_hand_quantity=on_hand_quantity-?
                WHERE clinic_id=? AND supply_id=? AND on_hand_quantity>=?
                RETURNING stock_id,on_hand_quantity
                """)) {
            sql.setBigDecimal(1, input.quantity());
            sql.setLong(2, target.clinic());
            sql.setLong(3, target.supply());
            sql.setBigDecimal(4, input.quantity());
            try (var rows = sql.executeQuery()) {
                if (!rows.next()) throw new Rejection("STOCK_INSUFFICIENT_OR_MISSING");
                stock = rows.getLong(1);
                balance = rows.getBigDecimal(2);
                if (rows.next()) throw new Rejection("STOCK_SCOPE_AMBIGUOUS");
            }
        }
        var document = JSON.createObjectNode().put("reference", input.proofReference())
                .put("actualQuantity", quantity(input.quantity())).put("actualCost", money(verified.cost()))
                .put("pricingBasis", verified.pricingBasis()).put("verifiedBy", verified.verifiedBy());
        var stored = new Stored(verified.id(), stock, input.quantity(), verified.cost(), document.toString(),
                verified.verifiedBy(), input.occurredAt(), verified.sourceReference());
        persist(c, stored, actor);
        var reference = reference(target, stored.id());
        var result = reference.deepCopy().put("stockId", Long.toString(stock)).put("quantity", quantity(input.quantity()))
                .put("cost", money(verified.cost())).put("occurredAt", input.occurredAt().toString())
                .put("stockBalanceAfter", quantity(balance));
        return new AuditDocuments.Change(reference, null, result,
                JSON.createObjectNode().put("recordHash", fingerprint(stored)));
    }

    private Evidence verify(Connection c, Target target, Input input) throws SQLException {
        var evidence = facts.find(c, input.proofReference()).orElseThrow(() -> new Rejection("CONSUMPTION_COST_REVIEW_REQUIRED"));
        if (evidence.id() <= 0 || !target.equals(evidence.target()) || evidence.quantity() == null
                || input.quantity().compareTo(evidence.quantity()) != 0 || !input.occurredAt().equals(evidence.occurredAt())
                || !fits(evidence.cost(), 2) || evidence.cost().signum() < 0 || blank(evidence.pricingBasis())
                || blank(evidence.verifiedBy()) || evidence.verifiedBy().length() > 100
                || (evidence.sourceReference() != null && evidence.sourceReference().length() > 200)) {
            throw new Rejection("CONSUMPTION_COST_REVIEW_REQUIRED");
        }
        return evidence;
    }

    private void persist(Connection c, Stored saved, String actor) throws SQLException {
        try (var sql = c.prepareStatement("""
                INSERT INTO adc.supply_consumption
                    (consumption_id,stock_id,consumed_quantity,consumption_cost,cost_basis,verified_by,
                     occurred_at,registered_at,registered_by,consumption_source_reference)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """)) {
            sql.setLong(1, saved.id());
            sql.setLong(2, saved.stock());
            sql.setBigDecimal(3, saved.quantity());
            sql.setBigDecimal(4, saved.cost());
            sql.setString(5, saved.document());
            sql.setString(6, saved.verifiedBy());
            sql.setObject(7, saved.occurredAt().atOffset(ZoneOffset.UTC));
            sql.setObject(8, StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC));
            sql.setString(9, actor);
            sql.setString(10, saved.sourceReference());
            if (sql.executeUpdate() != 1) throw new SQLException("CONSUMPTION_WRITE_UNCONFIRMED", "P0001");
        }
    }

    private static Optional<Stored> load(Connection c, Target target, long id) throws SQLException {
        try (var sql = c.prepareStatement("""
                SELECT x.stock_id,x.consumed_quantity,x.consumption_cost,x.cost_basis,x.verified_by,
                       x.occurred_at,x.consumption_source_reference
                FROM adc.supply_consumption x JOIN adc.medical_supply_stock s ON s.stock_id=x.stock_id
                WHERE x.consumption_id=? AND s.clinic_id=? AND s.supply_id=?
                """)) {
            sql.setLong(1, id);
            sql.setLong(2, target.clinic());
            sql.setLong(3, target.supply());
            try (var row = sql.executeQuery()) {
                return row.next() ? Optional.of(new Stored(id, row.getLong(1), row.getBigDecimal(2), row.getBigDecimal(3),
                        row.getString(4), row.getString(5), row.getObject(6, OffsetDateTime.class).toInstant(), row.getString(7)))
                        : Optional.empty();
            }
        }
    }

    private static tools.jackson.databind.node.ObjectNode reference(Target target, long id) {
        return JSON.createObjectNode().put("consumptionId", Long.toString(id))
                .put("clinicId", Long.toString(target.clinic())).put("supplyId", Long.toString(target.supply()));
    }

    private static String fingerprint(Stored row) {
        return AuditDocuments.hash(JSON.createArrayNode().add(row.stock()).add(quantity(row.quantity())).add(money(row.cost()))
                .add(row.document()).add(row.verifiedBy()).add(row.occurredAt().toString()).add(row.sourceReference()));
    }

    private static boolean fits(BigDecimal value, int scale) {
        return value != null && value.scale() >= 0 && value.scale() <= scale && value.precision() - value.scale() <= 18 - scale;
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static String quantity(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String money(BigDecimal value) { return value.setScale(2).toPlainString(); }

    @Override public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
        if (!UC.equals(command.ucCode()) || !OPERATION.equals(command.operation()) || command.clinics().size() != 1) return false;
        var target = new Target(command.clinics().iterator().next(), command.payload().path("supplyId").asLong());
        auth.require(UC, Set.of(target.clinic()));
        var ref = saved.path("businessReference");
        long id = ref.path("consumptionId").asLong();
        if (id <= 0 || !reference(target, id).equals(ref)) return false;
        var actual = load(c, target, id);
        return actual.isPresent()
                && fingerprint(actual.get()).equals(saved.path("verification").path("recordHash").asText());
    }

    @Override public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        return true;
    }
}
