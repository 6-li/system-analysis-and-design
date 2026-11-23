package cn.adc.appointments;

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
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

final class InvoiceSettlementService implements CommandRecovery.SuccessVerifier {
    private static final String UC = "UC12";
    static final String START = "DEMO_SETTLEMENT_START";
    static final String VERIFY = "DEMO_SETTLEMENT_VERIFY";
    static final String METHOD = "模拟支付";
    record Target(long clinic, long visit, long invoice, int version) {}
    record Confirmation(String applicant, String amount, String basis) {}
    record Payment(long id, int version, BigDecimal amount, boolean settled) {}

    private final StaffAuthorization auth;
    private final FormalBookingService.AppointmentIds ids;
    private final Clock clock;
    private final CommandRecovery commands;

    InvoiceSettlementService(JdbcTransactions tx, StaffAuthorization auth, FormalBookingService.AppointmentIds ids,
                             EventIdAllocator auditIds, Clock clock) {
        this.auth = auth;
        this.ids = ids;
        this.clock = clock;
        this.commands = new UcAuditService(tx, auth, this, clock, auditIds).commands();
    }

    CommandRecovery.Receipt execute(Target target, String op, UUID key, Long paymentId, Confirmation confirmation) {
        if (!(START.equals(op) && paymentId == null || VERIFY.equals(op) && paymentId != null && paymentId > 0 && confirmation == null)) {
            throw new Rejection("INVALID_SETTLEMENT_OPERATION");
        }
        var payload = JSON.createObjectNode().put("visitId", target.visit()).put("invoiceId", target.invoice()).put("version", target.version());
        if (paymentId != null) payload.put("paymentId", paymentId);
        if (confirmation != null) payload.putObject("confirmation").put("applicant", confirmation.applicant())
                .put("amount", confirmation.amount()).put("basis", confirmation.basis());
        return commands.execute(new CommandRecovery.Command(op, UC, key, payload, Set.of(target.clinic())),
                (c, locks) -> transact(c, target, op, paymentId, confirmation));
    }

    AuditDocuments.Change transact(Connection c, Target target, String op, Long paymentId, Confirmation confirmation) throws SQLException {
        authorize(c, target);
        try (var lock = c.prepareStatement("SELECT pg_advisory_xact_lock(41010,?)")) {
            lock.setInt(1, (int) ResourceLockCoordinator.key("invoice:" + target.visit()));
            lock.execute();
        }
        String actor = authorize(c, target);
        var bill = TreatmentInvoiceService.read(c, target.visit(), null).orElseThrow(() -> new Rejection("INVOICE_NOT_FOUND"));
        if (bill.id() != target.invoice() || bill.version() != target.version()) throw new Rejection("INVOICE_VERSION_STALE");
        var payment = existing(c, target.invoice());
        if (payment != null && (payment.version() != target.version() || payment.amount().compareTo(bill.total()) != 0)) {
            throw new Rejection("SETTLEMENT_RECONCILIATION_REQUIRED");
        }
        if (payment != null && payment.settled() && !"已结算".equals(bill.status())) {
            throw new Rejection("SETTLEMENT_RECONCILIATION_REQUIRED");
        }
        if (START.equals(op) && payment != null && payment.settled()) return evidence(target, payment);
        if (VERIFY.equals(op) && (payment == null || payment.id() != paymentId)) {
            throw new Rejection("SETTLEMENT_PAYMENT_MISMATCH");
        }
        if (payment != null && payment.settled()) return evidence(target, payment);
        if (START.equals(op) && payment == null && "待核对确认".equals(bill.status()) && bill.version() == 1) {
            confirmFirstInvoice(c, target, bill, confirmation, actor);
        } else {
            requireConfirmed(c, target, bill);
        }
        if (START.equals(op)) {
            if (payment != null) return evidence(target, payment);
            var created = new Payment(TreatmentInvoiceService.id(ids, c), target.version(), bill.total(), false);
            try (var sql = c.prepareStatement("""
                    INSERT INTO adc.payment_transaction
                        (payment_id,invoice_id,invoice_version_no,payment_method,requested_amount,
                         local_settlement_status,operated_by,recorded_at)
                    VALUES (?,?,?,?,?,'待核验',?,?)
                    """)) {
                sql.setLong(1, created.id());
                sql.setLong(2, target.invoice());
                sql.setInt(3, target.version());
                sql.setString(4, METHOD);
                sql.setBigDecimal(5, created.amount());
                sql.setString(6, actor);
                sql.setObject(7, StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC));
                TreatmentInvoiceService.written(sql.executeUpdate());
            }
            return evidence(target, created);
        }

        // Classroom simulator derives a deterministic receipt from the previously committed local payment.
        // It has no provider credentials, network call or caller-supplied success decision.
        var now = StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC);
        String reference = "SYNTHETIC-UC12-" + payment.id();
        try (var sql = c.prepareStatement("""
                UPDATE adc.payment_transaction SET transaction_result='模拟成功',received_amount=?,received_at=?,
                    receipt_basis=?,verification_result=?,external_transaction_reference=?,local_settlement_status='已结算'
                WHERE payment_id=? AND invoice_id=? AND invoice_version_no=? AND payment_method=?
                  AND local_settlement_status='待核验' AND received_amount IS NULL
                """)) {
            sql.setBigDecimal(1, payment.amount());
            sql.setObject(2, now);
            sql.setString(3, reference);
            sql.setString(4, "SYNTHETIC 本地校验：账单版本、交易号、申请金额一致");
            sql.setString(5, reference);
            sql.setLong(6, payment.id());
            sql.setLong(7, target.invoice());
            sql.setInt(8, target.version());
            sql.setString(9, METHOD);
            TreatmentInvoiceService.written(sql.executeUpdate());
        }
        try (var sql = c.prepareStatement("""
                UPDATE adc.treatment_invoice SET invoice_status='已结算'
                WHERE invoice_id=? AND visit_id=? AND invoice_status='待结算'
                """)) {
            sql.setLong(1, target.invoice());
            sql.setLong(2, target.visit());
            TreatmentInvoiceService.written(sql.executeUpdate());
        }
        return evidence(target, new Payment(payment.id(), payment.version(), payment.amount(), true));
    }

    private String authorize(Connection c, Target target) throws SQLException {
        String actor = auth.require(UC, Set.of(target.clinic())).actor();
        try (var sql = c.prepareStatement("SELECT 1 FROM adc.visit WHERE visit_id=? AND clinic_id=?")) {
            sql.setLong(1, target.visit());
            sql.setLong(2, target.clinic());
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new SecurityException("SETTLEMENT_ACCESS_DENIED");
            }
        }
        return actor;
    }

    private void confirmFirstInvoice(Connection c, Target target, TreatmentInvoiceService.Invoice bill,
                                     Confirmation confirmation, String actor) throws SQLException {
        if (confirmation == null || confirmation.applicant().isBlank() || confirmation.basis().isBlank()
                || !bill.total().toPlainString().equals(confirmation.amount())) {
            throw new Rejection("INVOICE_CONFIRMATION_REQUIRED");
        }
        var now = StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC);
        var proof = JSON.createObjectNode().put("applicant", confirmation.applicant())
                .put("invoiceId", target.invoice()).put("invoiceVersionNo", target.version())
                .put("acceptedAmount", confirmation.amount()).put("basis", confirmation.basis())
                .put("recordedBy", actor).put("confirmedAt", now.toInstant().toString());
        try (var sql = c.prepareStatement("""
                UPDATE adc.treatment_invoice_version SET confirmation_information=?,confirmed_at=?
                WHERE invoice_id=? AND invoice_version_no=? AND is_current
                  AND confirmation_information IS NULL AND confirmed_at IS NULL
                """)) {
            sql.setString(1, proof.toString());
            sql.setObject(2, now);
            sql.setLong(3, target.invoice());
            sql.setInt(4, target.version());
            TreatmentInvoiceService.written(sql.executeUpdate());
        }
        try (var sql = c.prepareStatement("""
                UPDATE adc.treatment_invoice SET invoice_status='待结算'
                WHERE invoice_id=? AND visit_id=? AND invoice_status='待核对确认'
                """)) {
            sql.setLong(1, target.invoice());
            sql.setLong(2, target.visit());
            TreatmentInvoiceService.written(sql.executeUpdate());
        }
    }

    private static void requireConfirmed(Connection c, Target target, TreatmentInvoiceService.Invoice invoice) throws SQLException {
        if (!"待结算".equals(invoice.status())) throw new Rejection("INVOICE_CONFIRMATION_REQUIRED");
        try (var sql = c.prepareStatement("""
                SELECT confirmation_information FROM adc.treatment_invoice_version
                WHERE invoice_id=? AND invoice_version_no=? AND is_current AND confirmed_at IS NOT NULL
                """)) {
            sql.setLong(1, target.invoice());
            sql.setInt(2, target.version());
            try (var row = sql.executeQuery()) {
                if (!row.next() || row.getString(1) == null) throw new Rejection("INVOICE_CONFIRMATION_REQUIRED");
                var proof = AuditDocuments.parse(row.getString(1));
                if (proof.path("invoiceId").asLong() != target.invoice() || proof.path("invoiceVersionNo").asInt() != target.version()
                        || !invoice.total().toPlainString().equals(proof.path("acceptedAmount").asText())) {
                    throw new Rejection("INVOICE_CONFIRMATION_STALE");
                }
            }
        }
    }

    private static Payment existing(Connection c, long invoice) throws SQLException {
        try (var sql = c.prepareStatement("""
                SELECT payment_id,invoice_version_no,payment_method,requested_amount,local_settlement_status,
                       received_amount,received_at,receipt_basis,transaction_result,verification_result,external_transaction_reference
                FROM adc.payment_transaction WHERE invoice_id=?
                """)) {
            sql.setLong(1, invoice);
            try (var row = sql.executeQuery()) {
                if (!row.next()) return null;
                long id = row.getLong(1);
                String status = row.getString(5);
                BigDecimal amount = row.getBigDecimal(4);
                boolean settled = "已结算".equals(status);
                String expected = "SYNTHETIC-UC12-" + id;
                if (!METHOD.equals(row.getString(3)) || amount == null
                        || (settled ? row.getBigDecimal(6) == null || row.getBigDecimal(6).compareTo(amount) != 0
                            || row.getObject(7) == null || !expected.equals(row.getString(8))
                            || !"模拟成功".equals(row.getString(9)) || row.getString(10) == null
                            || !expected.equals(row.getString(11))
                            : !"待核验".equals(status) || row.getBigDecimal(6) != null || row.getObject(7) != null)) {
                    throw new Rejection("SETTLEMENT_RECONCILIATION_REQUIRED");
                }
                var result = new Payment(id, row.getInt(2), amount, settled);
                if (row.next()) throw new Rejection("SETTLEMENT_RECONCILIATION_REQUIRED");
                return result;
            }
        }
    }

    private static AuditDocuments.Change evidence(Target target, Payment payment) {
        var reference = JSON.createObjectNode().put("invoiceId", Long.toString(target.invoice()))
                .put("invoiceVersionNo", target.version()).put("paymentId", Long.toString(payment.id()));
        var result = reference.deepCopy().put("visitId", Long.toString(target.visit())).put("amount", payment.amount().toPlainString())
                .put("status", payment.settled() ? "已结算" : "待核验").put("synthetic", true);
        if (payment.settled()) result.put("receipt", "SYNTHETIC-UC12-" + payment.id());
        return new AuditDocuments.Change(reference, null, result, JSON.createObjectNode().put("synthetic", true));
    }

    @Override public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
        if (!UC.equals(command.ucCode()) || command.clinics().size() != 1) return false;
        var input = command.payload();
        var target = new Target(command.clinics().iterator().next(), input.path("visitId").asLong(),
                input.path("invoiceId").asLong(), input.path("version").asInt());
        authorize(c, target);
        var payment = existing(c, target.invoice());
        var bill = TreatmentInvoiceService.read(c, target.visit(), null);
        var result = saved.path("after");
        return payment != null && bill.isPresent() && bill.get().id() == target.invoice()
                && bill.get().version() == target.version() && payment.version() == target.version()
                && evidence(target, payment).businessReference().equals(saved.path("businessReference"))
                && payment.amount().toPlainString().equals(result.path("amount").asText())
                && (!"已结算".equals(result.path("status").asText()) || payment.settled() && "已结算".equals(bill.get().status()));
    }

    @Override public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        return true;
    }
}
