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

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

final class InvoiceQuestionService implements CommandRecovery.SuccessVerifier {
    enum Operation {
        INVOICE_QUESTION_OPEN, INVOICE_CLINICAL_CONFIRM, INVOICE_CLINICAL_CORRECT,
        INVOICE_QUESTION_RECALCULATE, INVOICE_APPLICANT_CONFIRM;

        boolean clinical() { return this == INVOICE_CLINICAL_CONFIRM || this == INVOICE_CLINICAL_CORRECT; }
    }
    record Target(long clinic, long patient, long visit, long invoice, int version) {}
    record Correction(long record,long consent,String project,String unit,java.math.BigDecimal quantity,String projectKey){Correction(long record,long consent,String project,String unit,java.math.BigDecimal quantity){this(record,consent,project,unit,quantity,null);}}
    record Applicant(String identity, String acceptedAmount) {}
    record Details(String question, JsonNode handling, JsonNode invoice) {}
    private static final String UC = "UC11";
    private static final String CLINICAL = "UC11_CLINICAL";
    private ConsentProjectScopes projectScopes;
    InvoiceQuestionService enableProjectScopes(ConsentProjectScopes scopes){projectScopes=scopes;return this;}
    private boolean patientNotesEnabled;
    InvoiceQuestionService enablePatientNotes(){patientNotesEnabled=true;return this;}
    private final JdbcTransactions tx;
    private final StaffAuthorization auth;
    private final VisitHealthService.DentistIdentity dentists;
    private final TreatmentInvoiceService.Prices prices;
    private final FormalBookingService.AppointmentIds itemIds;
    private final Clock clock;
    private final CommandRecovery commands;

    InvoiceQuestionService(JdbcTransactions tx, StaffAuthorization auth, VisitHealthService.DentistIdentity dentists,
                           TreatmentInvoiceService.Prices prices, FormalBookingService.AppointmentIds itemIds,
                           EventIdAllocator auditIds, Clock clock) {
        this.tx = tx;
        this.auth = auth;
        this.dentists = dentists;
        this.prices = prices;
        this.itemIds = itemIds;
        this.clock = clock;
        this.commands = new UcAuditService(tx, auth, this, clock, auditIds).commands();
    }

    Details read(Target target, boolean clinical) {
        return tx.execute(c -> {
            authorize(c, target, clinical);
            var current = invoice(c, target);
            var document = details(c, target, current);
            authorize(c, target, clinical);
            return document;
        });
    }

    CommandRecovery.Receipt execute(Target target, UUID key, Operation operation, String note, boolean clinicalQuestion,
                                    String expectedQuestionReference, List<Correction> corrections, Applicant applicant) {
        if (note == null || note.isBlank()) throw new Rejection("INVOICE_QUESTION_BASIS_REQUIRED");
        if (operation != Operation.INVOICE_QUESTION_OPEN && (expectedQuestionReference == null || expectedQuestionReference.isBlank())) {
            throw new Rejection("INVOICE_QUESTION_REFERENCE_REQUIRED");
        }
        if ((operation == Operation.INVOICE_CLINICAL_CORRECT) != (!corrections.isEmpty())
                || (operation == Operation.INVOICE_APPLICANT_CONFIRM) != (applicant != null)) {
            throw new Rejection("INVOICE_REVIEW_INPUT_MISMATCH");
        }
        var payload = JSON.createObjectNode().put("patientId", target.patient()).put("visitId", target.visit())
                .put("invoiceId", target.invoice()).put("version", target.version()).put("note", note)
                .put("clinicalQuestion", clinicalQuestion).put("questionReference", expectedQuestionReference);
        if (corrections.size() == 1) payload.set("correction", correctionPayload(corrections.getFirst()));
        if (corrections.size() > 1) {
            var batch = payload.putArray("corrections");
            corrections.stream().sorted(java.util.Comparator.comparingLong(Correction::record))
                    .map(InvoiceQuestionService::correctionPayload).forEach(batch::add);
        }
        if (applicant != null) payload.putObject("applicant").put("identity", applicant.identity())
                .put("acceptedAmount", applicant.acceptedAmount());
        var command = new CommandRecovery.Command(operation.name(), UC, key, payload, Set.of(target.clinic()));
        String reference = commands.reference(command);
        return commands.execute(command, (c, locks) -> write(c, target, operation, note, clinicalQuestion,
                expectedQuestionReference, reference, List.copyOf(corrections), applicant));
    }

    AuditDocuments.Change write(Connection c, Target target, Operation op, String note, boolean clinicalQuestion,
                               String questionReference, String requestReference, List<Correction> corrections, Applicant applicant) throws SQLException {
        boolean clinical = op.clinical();
        authorize(c, target, clinical);
        try (var sql = c.prepareStatement("SELECT pg_advisory_xact_lock(41010,?)")) {
            sql.setInt(1, (int) ResourceLockCoordinator.key("invoice:" + target.visit()));
            sql.execute();
        }
        long dentist = authorize(c, target, clinical);
        var prior = invoice(c, target);
        requireNoPayments(c, target.invoice());
        var before = details(c, target, prior);
        var handling = before.handling();
        String actor = auth.require(clinical ? CLINICAL : UC, Set.of(target.clinic())).actor();
        if (op == Operation.INVOICE_QUESTION_OPEN) {
            var opened = JSON.createObjectNode().put("questionReference", requestReference)
                    .put("scope", clinicalQuestion ? "CLINICAL" : "PRICE").put("phase", "PENDING")
                    .put("openedBy", actor).put("openedAt", StrictInputPolicy.eventTime(clock).toString());
            updateVersion(c, target, note, opened);
            status(c, target, "待核实");
            return change(prior, target, before, note, opened, "待核实");
        }
        if (op == Operation.INVOICE_APPLICANT_CONFIRM) {
            return confirmApplicant(c, target, prior, before, questionReference, requestReference, note, applicant, actor);
        }
        if (!questionReference.equals(handling.path("questionReference").asText())
                || !"待核实".equals(prior.status())) throw new Rejection("INVOICE_QUESTION_STALE");
        boolean needsDentist = "CLINICAL".equals(handling.path("scope").asText());
        if (clinical) {
            if (!needsDentist || !"PENDING".equals(handling.path("phase").asText())) {
                throw new Rejection("INVOICE_CLINICAL_REVIEW_NOT_PENDING");
            }
            var verified = handling.deepCopy();
            if (!(verified instanceof tools.jackson.databind.node.ObjectNode document)) throw new Rejection("INVALID_QUESTION_HISTORY");
            document.put("phase", "CLINICAL_CONFIRMED");
            var confirmation = document.putObject("clinicalConfirmation")
                    .put("requestReference", requestReference).put("invoiceId", target.invoice())
                    .put("invoiceVersionNo", target.version()).put("actor", actor).put("dentistId", dentist)
                    .put("conclusion", "无需修正").put("basis", note)
                    .put("recordedAt", StrictInputPolicy.eventTime(clock).toString());
            var requested = new HashMap<Long, Correction>();
            for (var correction : corrections) {
                if (requested.putIfAbsent(correction.record(), correction) != null) {
                    throw new Rejection("DUPLICATE_CLINICAL_CORRECTION");
                }
            }
            var facts = prior.lines().stream().map(TreatmentInvoiceService.Line::fact).toList();
            var billed = facts.stream().map(TreatmentInvoiceService.Fact::record)
                    .collect(java.util.stream.Collectors.toSet());
            if (!billed.containsAll(requested.keySet())) throw new Rejection("CLINICAL_RECORD_NOT_IN_INVOICE");
            for (var fact : facts) requireCurrentClinicalFact(c, target, fact);

            if (!requested.isEmpty()) confirmation.put("conclusion", "已修正");
            var records = confirmation.putArray("clinicalVersions");
            var revisions = confirmation.putArray("revisions");
            for (var fact : facts) {
                var correction = requested.get(fact.record());
                int version = fact.version();
                if (correction != null) {
                    version = correct(c, target, fact, correction, dentist, actor, note);
                    revisions.addObject().put("clinicalRecordId", fact.record())
                            .put("previousRecordVersionNo", fact.version()).put("recordVersionNo", version);
                }
                records.addObject().put("clinicalRecordId", fact.record()).put("recordVersionNo", version);
            }
            updateVersion(c, target, before.question(), document);
            return change(prior, target, before, before.question(), document, "待核实");
        }
        String phase = handling.path("phase").asText();
        if (!(needsDentist ? "CLINICAL_CONFIRMED".equals(phase)
                : "PRICE".equals(handling.path("scope").asText()) && "PENDING".equals(phase))) {
            throw new Rejection("INVOICE_REVIEW_INCOMPLETE");
        }
        var targetVisit = new TreatmentInvoiceService.Target(target.clinic(), target.visit());
        var lines = TreatmentInvoiceService.calculate(c, targetVisit, prices);
        if (needsDentist ? !sameClinicalRefs(lines, handling.path("clinicalConfirmation").path("clinicalVersions"))
                : !lines.stream().map(TreatmentInvoiceService.Line::fact).toList()
                        .equals(prior.lines().stream().map(TreatmentInvoiceService.Line::fact).toList())) {
            throw new Rejection("CLINICAL_FACTS_CHANGED");
        }
        if (!(handling.deepCopy() instanceof tools.jackson.databind.node.ObjectNode document)) throw new Rejection("INVALID_QUESTION_HISTORY");
        document.put("phase", "RECALCULATED").put("billingReviewBasis", note).put("recalculatedBy", actor)
                .put("recalculationReference", requestReference);
        int next = Math.incrementExact(target.version());
        var updated = new TreatmentInvoiceService.Invoice(target.invoice(), next, "待再次确认",
                TreatmentInvoiceService.total(lines), lines);
        try (var sql = c.prepareStatement("""
                UPDATE adc.treatment_invoice_version SET is_current=false
                WHERE invoice_id=? AND invoice_version_no=? AND is_current
                """)) {
            bindTarget(sql, target, 1);
            TreatmentInvoiceService.written(sql.executeUpdate());
        }
        try (var sql = c.prepareStatement("""
                INSERT INTO adc.treatment_invoice_version
                    (invoice_id,invoice_version_no,total_amount,billing_question,handling_basis,is_current)
                VALUES (?,?,?,?,?,true)
                """)) {
            sql.setLong(1, target.invoice());
            sql.setInt(2, next);
            sql.setBigDecimal(3, updated.total());
            sql.setString(4, before.question());
            sql.setString(5, document.toString());
            TreatmentInvoiceService.written(sql.executeUpdate());
        }
        TreatmentInvoiceService.persistLines(c, updated, itemIds);
        status(c, target, "待再次确认");
        return change(updated, target, before, before.question(), document, updated.status());
    }

    private static JsonNode correctionPayload(Correction correction) {
        var body=JSON.createObjectNode().put("record", correction.record()).put("consent", correction.consent())
                .put("project", correction.project()).put("unit", correction.unit())
                .put("quantity", correction.quantity().toPlainString());
        if(correction.projectKey()!=null)body.put("projectKey",correction.projectKey());
        return body;
    }

    private static boolean sameClinicalRefs(java.util.List<TreatmentInvoiceService.Line> lines, JsonNode refs) {
        if (!refs.isArray() || lines.size() != refs.size()) return false;
        for (int i = 0; i < lines.size(); i++) {
            var fact = lines.get(i).fact();
            var reference = refs.get(i);
            if (fact.record() != reference.path("clinicalRecordId").asLong()
                    || fact.version() != reference.path("recordVersionNo").asInt()) return false;
        }
        return true;
    }

    private int correct(Connection c, Target target, TreatmentInvoiceService.Fact old, Correction update,
                        long dentist, String actor, String basis) throws SQLException {
        VisitTreatmentService.validate(new VisitTreatmentService.Input(update.consent(), true, update.project(),
                update.unit(), update.quantity(), null, null));
        String projectKey=update.projectKey();
        if(projectScopes!=null){
            String priorKey;
            try(var p=c.prepareStatement("SELECT performed_project_key FROM adc.clinical_record_version WHERE clinical_record_id=? AND record_version_no=?")){p.setLong(1,old.record());p.setInt(2,old.version());try(var r=p.executeQuery()){if(!r.next())throw new Rejection("CLINICAL_FACTS_CHANGED");priorKey=r.getString(1);}}
            if(projectKey==null&&priorKey!=null){if(!old.item().equals(update.project())||!old.unit().equals(update.unit()))throw new Rejection("PROJECT_SELECTION_REQUIRED");projectKey=priorKey;}
        }else if(projectKey!=null)throw new Rejection("PROJECT_KEYS_NOT_ENABLED");
        if(projectKey!=null){var item=projectScopes.catalog.requireKey(projectKey);if(!item.name().equals(update.project())||!item.unit().equals(update.unit()))throw new Rejection("PROJECT_CATALOG_CHANGED");projectScopes.require(c,update.consent(),target.visit(),projectKey,true,update.unit());}
        else {
            if(projectScopes!=null&&!projectScopes.read(c,update.consent()).isEmpty())throw new Rejection("PROJECT_SELECTION_REQUIRED");
        try (var sql = c.prepareStatement("""
                SELECT 1 FROM adc.treatment_consent_result WHERE consent_result_id=? AND visit_id=?
                  AND proposed_treatment=? AND consent_result='同意'
                """)) {
            sql.setLong(1, update.consent());
            sql.setLong(2, target.visit());
            sql.setString(3, update.project());
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new Rejection("TREATMENT_CONSENT_REVIEW_REQUIRED");
            }
        }
        }
        if (old.item().equals(update.project()) && old.unit().equals(update.unit())
                && old.quantity().compareTo(update.quantity()) == 0) throw new Rejection("CLINICAL_CORRECTION_UNCHANGED");
        int next = Math.incrementExact(old.version());
        try (var sql = c.prepareStatement("""
                UPDATE adc.clinical_record_version SET is_current=false
                WHERE clinical_record_id=? AND record_version_no=? AND is_current AND responsible_dentist_id=?
                """)) {
            sql.setLong(1, old.record());
            sql.setInt(2, old.version());
            sql.setLong(3, dentist);
            TreatmentInvoiceService.written(sql.executeUpdate());
        }
        try (var sql = c.prepareStatement("""
                INSERT INTO adc.clinical_record_version
                    (clinical_record_id,record_version_no,consent_result_id,responsible_dentist_id,performed_treatment,
                     billing_unit,actual_quantity,implementation_result,special_requirements,non_performance_reason,
                     revision_basis,recorded_by,recorded_at,is_current %s %s)
                SELECT clinical_record_id,?,?,?,?,?,?,'已实施',special_requirements,non_performance_reason,?,?,?,true %s %s
                FROM adc.clinical_record_version WHERE clinical_record_id=? AND record_version_no=? AND NOT is_current
                """.formatted(patientNotesEnabled?",patient_findings,aftercare_instructions":"",projectScopes!=null?",performed_project_key":"",patientNotesEnabled?",patient_findings,aftercare_instructions":"",projectScopes!=null?",?":""))) {
            sql.setInt(1, next);
            sql.setLong(2, update.consent());
            sql.setLong(3, dentist);
            sql.setString(4, update.project());
            sql.setString(5, update.unit());
            sql.setBigDecimal(6, update.quantity());
            sql.setString(7, basis);
            sql.setString(8, actor);
            sql.setObject(9, StrictInputPolicy.eventTime(clock).atOffset(java.time.ZoneOffset.UTC));
            if(projectScopes!=null){sql.setString(10,projectKey);sql.setLong(11,old.record());sql.setInt(12,old.version());}else{sql.setLong(10, old.record());sql.setInt(11, old.version());}
            TreatmentInvoiceService.written(sql.executeUpdate());
        }
        return next;
    }

    private AuditDocuments.Change confirmApplicant(Connection c, Target target, TreatmentInvoiceService.Invoice invoice,
                                                    Details before, String questionReference, String requestReference,
                                                    String note, Applicant applicant, String actor) throws SQLException {
        if (applicant == null || !"待再次确认".equals(invoice.status())
                || !"RECALCULATED".equals(before.handling().path("phase").asText())
                || !questionReference.equals(before.handling().path("questionReference").asText())
                || !invoice.total().toPlainString().equals(applicant.acceptedAmount())) {
            throw new Rejection("APPLICANT_CONFIRMATION_STALE");
        }
        var at = StrictInputPolicy.eventTime(clock);
        var confirmation = JSON.createObjectNode().put("applicant", applicant.identity())
                .put("basis", note).put("recordedBy", actor).put("requestReference", requestReference)
                .put("invoiceId", target.invoice()).put("invoiceVersionNo", target.version())
                .put("questionReference", questionReference).put("acceptedAmount", applicant.acceptedAmount())
                .put("confirmedAt", at.toString());
        try (var sql = c.prepareStatement("""
                UPDATE adc.treatment_invoice_version SET confirmation_information=?, confirmed_at=?
                WHERE invoice_id=? AND invoice_version_no=? AND is_current
                  AND confirmation_information IS NULL AND confirmed_at IS NULL
                """)) {
            sql.setString(1, confirmation.toString());
            sql.setObject(2, at.atOffset(java.time.ZoneOffset.UTC));
            bindTarget(sql, target, 3);
            TreatmentInvoiceService.written(sql.executeUpdate());
        }
        status(c, target, "待结算");
        var billing = new TreatmentInvoiceService.Invoice(invoice.id(), invoice.version(), "待结算", invoice.total(), invoice.lines());
        var result = change(billing, target, before, before.question(), before.handling(), billing.status());
        ((tools.jackson.databind.node.ObjectNode)result.after()).set("confirmation", confirmation);
        return result;
    }

    private long authorize(Connection c, Target target, boolean clinical) throws SQLException {
        if (clinical) return TreatingDentistAccess.require(c, auth, dentists, CLINICAL, target.clinic(), target.patient(), target.visit());
        auth.require(UC, Set.of(target.clinic()));
        try (var sql = c.prepareStatement("""
                SELECT 1 FROM adc.visit v JOIN adc.appointment a ON a.appointment_id=v.appointment_id
                WHERE v.visit_id=? AND v.clinic_id=? AND a.patient_id=? AND a.appointment_status='已到诊'
                """)) {
            sql.setLong(1, target.visit());
            sql.setLong(2, target.clinic());
            sql.setLong(3, target.patient());
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new SecurityException("INVOICE_QUESTION_SCOPE_DENIED");
            }
        }
        return 0;
    }

    private static TreatmentInvoiceService.Invoice invoice(Connection c, Target target) throws SQLException {
        var invoice = TreatmentInvoiceService.read(c, target.visit(), null)
                .orElseThrow(() -> new Rejection("INVOICE_NOT_FOUND"));
        if (invoice.id() != target.invoice() || invoice.version() != target.version()) throw new Rejection("INVOICE_VERSION_STALE");
        return invoice;
    }

    private static Details details(Connection c, Target target, TreatmentInvoiceService.Invoice invoice) throws SQLException {
        try (var sql = c.prepareStatement("""
                SELECT billing_question,handling_basis,confirmation_information,confirmed_at
                FROM adc.treatment_invoice_version WHERE invoice_id=? AND invoice_version_no=? AND is_current
                """)) {
            bindTarget(sql, target, 1);
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new Rejection("INVOICE_VERSION_STALE");
                var body = TreatmentInvoiceService.evidence(invoice, target.visit()).after().deepCopy();
                if (body instanceof tools.jackson.databind.node.ObjectNode document) {
                    document.put("confirmationInformation", row.getString(3)).put("confirmedAt", row.getString(4));
                }
                String basis = row.getString(2);
                return new Details(row.getString(1), basis == null ? JSON.createObjectNode() : AuditDocuments.parse(basis), body);
            }
        }
    }

    private static void requireNoPayments(Connection c, long invoice) throws SQLException {
        try (var sql = c.prepareStatement("""
                SELECT 1 FROM adc.payment_transaction
                WHERE invoice_id=? AND (received_amount IS NOT NULL OR received_at IS NOT NULL
                  OR local_settlement_status='待核验') LIMIT 1
                """)) {
            sql.setLong(1, invoice);
            try (var row = sql.executeQuery()) {
                if (row.next()) throw new Rejection("INVOICE_ALREADY_PAID");
            }
        }
    }

    private static void requireCurrentClinicalFact(Connection c, Target target, TreatmentInvoiceService.Fact fact)
            throws SQLException {
        try (var sql = c.prepareStatement("""
                SELECT 1 FROM adc.clinical_record r JOIN adc.clinical_record_version v USING (clinical_record_id)
                WHERE r.visit_id=? AND r.clinical_record_id=? AND v.record_version_no=? AND v.is_current
                """)) {
            sql.setLong(1, target.visit());
            sql.setLong(2, fact.record());
            sql.setInt(3, fact.version());
            try (var row = sql.executeQuery()) {
                if (!row.next()) throw new Rejection("CLINICAL_FACTS_CHANGED");
            }
        }
    }

    private static void updateVersion(Connection c, Target target, String question, JsonNode handling) throws SQLException {
        try (var sql = c.prepareStatement("""
                UPDATE adc.treatment_invoice_version SET billing_question=?,handling_basis=?
                WHERE invoice_id=? AND invoice_version_no=? AND is_current
                """)) {
            sql.setString(1, question);
            sql.setString(2, handling.toString());
            bindTarget(sql, target, 3);
            TreatmentInvoiceService.written(sql.executeUpdate());
        }
    }

    private static void status(Connection c, Target target, String status) throws SQLException {
        try (var sql = c.prepareStatement("UPDATE adc.treatment_invoice SET invoice_status=? WHERE invoice_id=? AND visit_id=?")) {
            sql.setString(1, status);
            sql.setLong(2, target.invoice());
            sql.setLong(3, target.visit());
            TreatmentInvoiceService.written(sql.executeUpdate());
        }
    }

    private static void bindTarget(java.sql.PreparedStatement stmt, Target target, int start) throws SQLException {
        stmt.setLong(start, target.invoice());
        stmt.setInt(start + 1, target.version());
    }

    private static AuditDocuments.Change change(TreatmentInvoiceService.Invoice invoice, Target target, Details before,
                                                String question, JsonNode handling, String status) {
        var billing = TreatmentInvoiceService.evidence(invoice, target.visit());
        var prior = JSON.createObjectNode().put("question", before.question());
        prior.set("handling", before.handling());
        prior.set("invoice", before.invoice());
        var after = JSON.createObjectNode().put("question", question).put("status", status);
        after.set("handling", handling);
        after.set("invoice", billing.after());
        return new AuditDocuments.Change(billing.businessReference(), prior, after, billing.verification());
    }

    @Override
    public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
        if (!UC.equals(command.ucCode()) || command.clinics().size() != 1) return false;
        var request = command.payload();
        var target = new Target(command.clinics().iterator().next(), request.path("patientId").asLong(),
                request.path("visitId").asLong(), request.path("invoiceId").asLong(), request.path("version").asInt());
        authorize(c, target, Operation.valueOf(command.operation()).clinical());
        int version = saved.path("businessReference").path("invoiceVersionNo").asInt();
        var actual = TreatmentInvoiceService.read(c, target.visit(), version);
        if (actual.isEmpty() || actual.get().id() != target.invoice()) return false;
        var evidence = TreatmentInvoiceService.evidence(actual.get(), target.visit());
        return evidence.businessReference().equals(saved.path("businessReference"))
                && evidence.verification().equals(saved.path("verification"));
    }

    @Override
    public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        return true;
    }
}
