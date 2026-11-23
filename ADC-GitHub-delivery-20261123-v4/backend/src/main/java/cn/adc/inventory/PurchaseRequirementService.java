package cn.adc.inventory;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore.EventIdAllocator;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

final class PurchaseRequirementService implements CommandRecovery.SuccessVerifier {
    private static final String UC = "UC14";
    static final String REGISTER = "PURCHASE_REQUIREMENT_REGISTER";
    static final String UNAVAILABLE = "PURCHASE_REQUIREMENT_UNAVAILABLE";
    private static final String WAITING = "待联系供应商";
    private static final String ADJUSTMENT = "无法供货待调整";

    record Input(long supply, BigDecimal quantity, String confirmationReference) {}
    record Confirmation(long clinic, long supply, BigDecimal quantity, String reference, String manager, String basis) {}
    record Saved(long id, long supply, BigDecimal quantity, String confirmation, String status, String explanation) {}
    interface Confirmations {
        Optional<Confirmation> find(Connection c, String reference) throws SQLException;
    }

    private final StaffAuthorization auth;
    private final EventIdAllocator ids;
    private final Confirmations confirmations;
    private final Clock clock;
    private final CommandRecovery commands;

    PurchaseRequirementService(JdbcTransactions tx, StaffAuthorization auth, EventIdAllocator ids,
                               Confirmations confirmations, EventIdAllocator auditIds, Clock clock) {
        this.auth = auth;
        this.ids = ids;
        this.confirmations = confirmations;
        this.clock = clock;
        this.commands = new UcAuditService(tx, auth, this, clock, auditIds).commands();
    }

    CommandRecovery.Receipt register(long clinic, UUID key, Input input) {
        validate(input);
        var payload = JSON.createObjectNode().put("supplyId", input.supply()).put("quantity", quantity(input.quantity()))
                .put("confirmationReference", input.confirmationReference());
        return commands.execute(new CommandRecovery.Command(REGISTER, UC, key, payload, Set.of(clinic)),
                (c, locks) -> register(c, clinic, input));
    }

    CommandRecovery.Receipt unavailable(long clinic, UUID key, long id, String explanation) {
        if (id <= 0 || blank(explanation)) throw new Rejection("SUPPLIER_EXPLANATION_REQUIRED");
        var payload = JSON.createObjectNode().put("requirementId", id).put("explanation", explanation);
        return commands.execute(new CommandRecovery.Command(UNAVAILABLE, UC, key, payload, Set.of(clinic)),
                (c, locks) -> unavailable(c, clinic, id, explanation));
    }

    private static void validate(Input input) {
        BigDecimal value = input.quantity();
        if (input.supply() <= 0 || value == null || value.signum() <= 0 || value.scale() < 0 || value.scale() > 3
                || value.precision() - value.scale() > 15 || blank(input.confirmationReference())) {
            throw new Rejection("INVALID_PURCHASE_REQUIREMENT");
        }
    }

    AuditDocuments.Change register(Connection c, long clinic, Input input) throws SQLException {
        validate(input);
        String actor = auth.require(UC, Set.of(clinic)).actor();
        try (var sql = c.prepareStatement("""
                SELECT 1 FROM adc.clinic c CROSS JOIN adc.medical_supply s
                WHERE c.clinic_id=? AND s.supply_id=?
                """)) {
            sql.setLong(1, clinic);
            sql.setLong(2, input.supply());
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new Rejection("PURCHASE_SCOPE_NOT_FOUND");
            }
        }
        var confirmed = confirmations.find(c, input.confirmationReference())
                .orElseThrow(() -> new Rejection("MANAGER_CONFIRMATION_REQUIRED"));
        if (confirmed.clinic() != clinic || confirmed.supply() != input.supply() || confirmed.quantity() == null
                || confirmed.quantity().compareTo(input.quantity()) != 0
                || !input.confirmationReference().equals(confirmed.reference()) || blank(confirmed.manager())
                || blank(confirmed.basis())) {
            throw new Rejection("MANAGER_CONFIRMATION_MISMATCH");
        }
        String basis = JSON.createObjectNode().put("reference", confirmed.reference()).put("manager", confirmed.manager())
                .put("clinicId", Long.toString(clinic)).put("supplyId", Long.toString(input.supply()))
                .put("quantity", quantity(input.quantity())).put("basis", confirmed.basis()).toString();
        long id = ids.next(c);
        if (id <= 0) throw new SQLException("PURCHASE_REQUIREMENT_ID_UNAVAILABLE", "22003");
        try (var sql = c.prepareStatement("""
                INSERT INTO adc.purchase_requirement
                    (purchase_requirement_id,clinic_id,supply_id,required_quantity,requirement_confirmation,
                     procurement_status,registered_by,registered_at)
                VALUES (?,?,?,?,?,?,?,?)
                """)) {
            sql.setLong(1, id);
            sql.setLong(2, clinic);
            sql.setLong(3, input.supply());
            sql.setBigDecimal(4, input.quantity());
            sql.setString(5, basis);
            sql.setString(6, WAITING);
            sql.setString(7, actor);
            sql.setObject(8, StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC));
            written(sql.executeUpdate());
        }
        return evidence(clinic, new Saved(id, input.supply(), input.quantity(), basis, WAITING, null), null);
    }

    AuditDocuments.Change unavailable(Connection c, long clinic, long id, String explanation) throws SQLException {
        auth.require(UC, Set.of(clinic));
        Saved prior = load(c, clinic, id).orElseThrow(() -> new Rejection("PURCHASE_REQUIREMENT_NOT_FOUND"));
        if (!WAITING.equals(prior.status())) throw new Rejection("PURCHASE_REQUIREMENT_STALE");
        try (var sql = c.prepareStatement("""
                UPDATE adc.purchase_requirement
                SET procurement_status=?,unavailable_supply_explanation=?
                WHERE purchase_requirement_id=? AND clinic_id=? AND procurement_status=?
                """)) {
            sql.setString(1, ADJUSTMENT);
            sql.setString(2, explanation);
            sql.setLong(3, id);
            sql.setLong(4, clinic);
            sql.setString(5, WAITING);
            if (sql.executeUpdate() != 1) throw new Rejection("PURCHASE_REQUIREMENT_STALE");
        }
        return evidence(clinic, new Saved(id, prior.supply(), prior.quantity(), prior.confirmation(), ADJUSTMENT, explanation),
                projection(clinic, prior));
    }

    private static Optional<Saved> load(Connection c, long clinic, long id) throws SQLException {
        try (var sql = c.prepareStatement("""
                SELECT supply_id,required_quantity,requirement_confirmation,procurement_status,unavailable_supply_explanation
                FROM adc.purchase_requirement WHERE purchase_requirement_id=? AND clinic_id=?
                """)) {
            sql.setLong(1, id);
            sql.setLong(2, clinic);
            try (var row = sql.executeQuery()) {
                return row.next() ? Optional.of(new Saved(id, row.getLong(1), row.getBigDecimal(2), row.getString(3),
                        row.getString(4), row.getString(5))) : Optional.empty();
            }
        }
    }

    private static JsonNode projection(long clinic, Saved saved) {
        return JSON.createObjectNode().put("requirementId", Long.toString(saved.id())).put("clinicId", Long.toString(clinic))
                .put("supplyId", Long.toString(saved.supply())).put("quantity", quantity(saved.quantity()))
                .put("status", saved.status()).put("explanation", saved.explanation());
    }

    private static AuditDocuments.Change evidence(long clinic, Saved saved, JsonNode prior) {
        var ref = JSON.createObjectNode().put("requirementId", Long.toString(saved.id())).put("clinicId", Long.toString(clinic));
        var proof = JSON.createObjectNode().put("requirementHash", fingerprint(saved));
        return new AuditDocuments.Change(ref, prior, projection(clinic, saved), proof);
    }

    private static String fingerprint(Saved saved) {
        return AuditDocuments.hash(JSON.createArrayNode().add(saved.supply()).add(quantity(saved.quantity())).add(saved.confirmation()));
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static String quantity(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }

    private static void written(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("PURCHASE_WRITE_UNCONFIRMED", "P0001");
    }

    @Override public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
        if (!UC.equals(command.ucCode()) || command.clinics().size() != 1) return false;
        long clinic = command.clinics().iterator().next();
        auth.require(UC, Set.of(clinic));
        var ref = saved.path("businessReference");
        if (ref.path("clinicId").asLong() != clinic) return false;
        var row = load(c, clinic, ref.path("requirementId").asLong());
        if (row.isEmpty() || !fingerprint(row.get()).equals(saved.path("verification").path("requirementHash").asText())) return false;
        return REGISTER.equals(command.operation()) || UNAVAILABLE.equals(command.operation())
                && projection(clinic, row.get()).equals(saved.path("after"));
    }

    @Override public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        return true;
    }
}
