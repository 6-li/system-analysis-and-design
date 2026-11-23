package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore.EventIdAllocator;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

final class TreatmentInvoiceService implements CommandRecovery.SuccessVerifier {
    private static final String UC = "UC10";
    private static final String OPERATION = "TREATMENT_INVOICE_GENERATE";
    record Target(long clinic, long visit) {}
    record Fact(long record, int version, String item, String unit, BigDecimal quantity) {}
    record Price(BigDecimal amount, String sourceReference, String applicableConditions) {}
    record Line(Fact fact, BigDecimal price, BigDecimal amount, String basis) {}
    record Invoice(long id, int version, String status, BigDecimal total, List<Line> lines) {}

    interface Prices {
        Optional<Price> find(Connection c, Target target, Fact fact) throws SQLException;
    }

    private final StaffAuthorization auth;
    private final Prices prices;
    private final FormalBookingService.AppointmentIds invoiceIds;
    private final FormalBookingService.AppointmentIds itemIds;
    private final CommandRecovery commands;

    TreatmentInvoiceService(JdbcTransactions tx, StaffAuthorization auth, Prices prices,
                            FormalBookingService.AppointmentIds invoiceIds, FormalBookingService.AppointmentIds itemIds,
                            EventIdAllocator auditIds, Clock clock) {
        this.auth = auth;
        this.prices = prices;
        this.invoiceIds = invoiceIds;
        this.itemIds = itemIds;
        this.commands = new UcAuditService(tx, auth, this, clock, auditIds).commands();
    }

    CommandRecovery.Receipt generate(Target target, UUID key) {
        var payload = JSON.createObjectNode().put("visitId", target.visit());
        return commands.execute(new CommandRecovery.Command(OPERATION, UC, key, payload, Set.of(target.clinic())),
                (c, locks) -> generate(c, target));
    }

    AuditDocuments.Change generate(Connection c, Target target) throws SQLException {
        requireVisit(c, target);
        try (var lock = c.prepareStatement("SELECT pg_advisory_xact_lock(41010,?)")) {
            lock.setInt(1, (int) ResourceLockCoordinator.key("invoice:" + target.visit()));
            lock.execute();
        }
        requireVisit(c, target);
        var current = read(c, target.visit(), null);
        if (current.isPresent()) return evidence(current.get(), target.visit());
        var lines = calculate(c, target, prices);
        BigDecimal total = total(lines);
        var invoice = new Invoice(id(invoiceIds, c), 1, "待核对确认", total, List.copyOf(lines));
        requireVisit(c, target);
        persist(c, target.visit(), invoice);
        return evidence(invoice, target.visit());
    }

    static List<Line> calculate(Connection c, Target target, Prices prices) throws SQLException {
        var lines = new ArrayList<Line>();
        try (var query = c.prepareStatement("""
                SELECT r.clinical_record_id, v.record_version_no, v.performed_treatment, v.billing_unit, v.actual_quantity
                FROM adc.clinical_record r JOIN adc.clinical_record_version v USING (clinical_record_id)
                WHERE r.visit_id=? AND v.is_current AND v.implementation_result='已实施'
                ORDER BY r.clinical_record_id
                """)) {
            query.setLong(1, target.visit());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var fact = new Fact(rows.getLong(1), rows.getInt(2), rows.getString(3), rows.getString(4), rows.getBigDecimal(5));
                    if (blank(fact.item()) || blank(fact.unit()) || fact.quantity() == null || fact.quantity().signum() <= 0) {
                        throw new Rejection("BILLABLE_FACT_INCOMPLETE");
                    }
                    lines.add(rate(fact, prices.find(c, target, fact).orElseThrow(() -> new Rejection("PRICE_UNAVAILABLE"))));
                }
            }
        }
        if (lines.isEmpty()) throw new Rejection("NO_BILLABLE_FACTS");
        return List.copyOf(lines);
    }

    static BigDecimal total(List<Line> lines) {
        return money(lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    static Line rate(Fact fact, Price price) {
        if (price.amount() == null || price.amount().signum() < 0 || blank(price.sourceReference())
                || blank(price.applicableConditions())) throw new Rejection("PRICE_UNAVAILABLE");
        BigDecimal unitPrice;
        try {
            unitPrice = money(price.amount().setScale(2, RoundingMode.UNNECESSARY));
        } catch (ArithmeticException e) {
            throw new Rejection("PRICE_PRECISION_INVALID");
        }
        var basis = JSON.createObjectNode().put("sourceReference", price.sourceReference())
                .put("item", fact.item()).put("unit", fact.unit()).put("unitPrice", unitPrice.toPlainString())
                .put("applicableConditions", price.applicableConditions());
        return new Line(fact, unitPrice, money(unitPrice.multiply(fact.quantity()).setScale(2, RoundingMode.HALF_UP)), basis.toString());
    }

    private static BigDecimal money(BigDecimal amount) {
        if (amount.precision() - amount.scale() > 16) throw new Rejection("INVOICE_AMOUNT_OUT_OF_RANGE");
        return amount.setScale(2);
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }

    private void requireVisit(Connection c, Target target) throws SQLException {
        auth.require(UC, Set.of(target.clinic()));
        try (var sql = c.prepareStatement("""
                SELECT 1 FROM adc.visit v JOIN adc.appointment a ON a.appointment_id=v.appointment_id
                WHERE v.visit_id=? AND v.clinic_id=? AND a.appointment_status='已到诊'
                """)) {
            sql.setLong(1, target.visit());
            sql.setLong(2, target.clinic());
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new SecurityException("INVOICE_VISIT_DENIED");
            }
        }
    }

    private void persist(Connection c, long visit, Invoice bill) throws SQLException {
        try (var sql = c.prepareStatement("INSERT INTO adc.treatment_invoice (invoice_id,visit_id,invoice_status) VALUES (?,?,?)")) {
            sql.setLong(1, bill.id());
            sql.setLong(2, visit);
            sql.setString(3, bill.status());
            written(sql.executeUpdate());
        }
        try (var sql = c.prepareStatement("""
                INSERT INTO adc.treatment_invoice_version (invoice_id,invoice_version_no,total_amount,is_current)
                VALUES (?,1,?,true)
                """)) {
            sql.setLong(1, bill.id());
            sql.setBigDecimal(2, bill.total());
            written(sql.executeUpdate());
        }
        persistLines(c, bill, itemIds);
    }

    static void persistLines(Connection c, Invoice bill, FormalBookingService.AppointmentIds itemIds) throws SQLException {
        try (var sql = c.prepareStatement("""
                INSERT INTO adc.treatment_invoice_item
                    (invoice_item_id,invoice_id,invoice_version_no,clinical_record_id,record_version_no,
                     billed_item,billing_unit,billed_quantity,unit_price,line_amount,price_basis)
                VALUES (?,?,?, ?,?,?,?,?,?,?,?)
                """)) {
            for (var line : bill.lines()) {
                var fact = line.fact();
                sql.setLong(1, id(itemIds, c));
                sql.setLong(2, bill.id());
                sql.setInt(3, bill.version());
                sql.setLong(4, fact.record());
                sql.setInt(5, fact.version());
                sql.setString(6, fact.item());
                sql.setString(7, fact.unit());
                sql.setBigDecimal(8, fact.quantity());
                sql.setBigDecimal(9, line.price());
                sql.setBigDecimal(10, line.amount());
                sql.setString(11, line.basis());
                written(sql.executeUpdate());
            }
        }
    }

    static long id(FormalBookingService.AppointmentIds ids, Connection c) throws SQLException {
        long id = ids.next(c);
        if (id <= 0) throw new SQLException("INVOICE_ID_UNAVAILABLE","22003");
        return id;
    }

    static void written(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("INVOICE_WRITE_UNCONFIRMED","P0001");
    }

    static Optional<Invoice> read(Connection c, long visit, Integer version) throws SQLException {
        String selector = version == null ? "v.is_current" : "v.invoice_version_no=?";
        try (var sql = c.prepareStatement("""
                SELECT i.invoice_id,v.invoice_version_no,i.invoice_status,v.total_amount
                FROM adc.treatment_invoice i LEFT JOIN adc.treatment_invoice_version v ON i.invoice_id=v.invoice_id AND
                """ + " " + selector + " WHERE i.visit_id=?")) {
            if (version != null) sql.setInt(1, version);
            sql.setLong(version == null ? 1 : 2, visit);
            try (var row = sql.executeQuery()) {
                if (!row.next()) return Optional.empty();
                long id = row.getLong(1);
                int actualVersion = row.getInt(2);
                if (actualVersion <= 0) throw new Rejection("INVOICE_VERSION_UNAVAILABLE");
                var lines = loadLines(c, id, actualVersion);
                if (lines.isEmpty() || lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .compareTo(row.getBigDecimal(4)) != 0) throw new Rejection("INVOICE_LINES_UNVERIFIED");
                return Optional.of(new Invoice(id, actualVersion, row.getString(3), row.getBigDecimal(4), lines));
            }
        }
    }

    private static List<Line> loadLines(Connection c, long invoice, int version) throws SQLException {
        var lines = new ArrayList<Line>();
        try (var query = c.prepareStatement("""
                SELECT clinical_record_id,record_version_no,billed_item,billing_unit,billed_quantity,
                       unit_price,line_amount,price_basis
                FROM adc.treatment_invoice_item WHERE invoice_id=? AND invoice_version_no=? ORDER BY clinical_record_id
                """)) {
            query.setLong(1, invoice);
            query.setInt(2, version);
            try (var rows = query.executeQuery()) {
                while (rows.next()) lines.add(new Line(new Fact(rows.getLong(1), rows.getInt(2), rows.getString(3),
                        rows.getString(4), rows.getBigDecimal(5)), rows.getBigDecimal(6), rows.getBigDecimal(7), rows.getString(8)));
            }
        }
        return List.copyOf(lines);
    }

    static AuditDocuments.Change evidence(Invoice invoice, long visit) {
        var body = JSON.createObjectNode().put("invoiceId", Long.toString(invoice.id())).put("invoiceVersionNo", invoice.version())
                .put("visitId", Long.toString(visit)).put("total", invoice.total().toPlainString());
        var details = body.putArray("lines");
        for (var line : invoice.lines()) {
            var fact = line.fact();
            details.addObject().put("clinicalRecordId", Long.toString(fact.record())).put("recordVersionNo", fact.version())
                    .put("item", fact.item()).put("unit", fact.unit()).put("quantity", fact.quantity().stripTrailingZeros().toPlainString())
                    .put("unitPrice", line.price().toPlainString()).put("amount", line.amount().toPlainString());
        }
        var proof = JSON.createObjectNode().put("financialHash", AuditDocuments.hash(body))
                .put("priceBasisHash", AuditDocuments.hash(JSON.valueToTree(invoice.lines().stream().map(Line::basis).toList())));
        body.put("status", invoice.status());
        var ref = JSON.createObjectNode().put("invoiceId", Long.toString(invoice.id())).put("visitId", Long.toString(visit))
                .put("invoiceVersionNo", invoice.version());
        return new AuditDocuments.Change(ref, null, body, proof);
    }

    @Override
    public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
        if (!UC.equals(command.ucCode()) || !OPERATION.equals(command.operation()) || command.clinics().size() != 1) return false;
        var target = new Target(command.clinics().iterator().next(), command.payload().path("visitId").asLong());
        requireVisit(c, target);
        int version = saved.path("businessReference").path("invoiceVersionNo").asInt();
        var bill = read(c, target.visit(), version);
        return bill.isPresent() && evidence(bill.get(), target.visit()).businessReference().equals(saved.path("businessReference"))
                && evidence(bill.get(), target.visit()).verification().equals(saved.path("verification"));
    }

    @Override
    public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        return true;
    }
}
