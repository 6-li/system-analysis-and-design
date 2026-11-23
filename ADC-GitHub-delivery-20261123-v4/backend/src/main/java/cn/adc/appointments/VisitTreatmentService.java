package cn.adc.appointments;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

final class VisitTreatmentService implements CommandRecovery.SuccessVerifier {
    private static final String UC = "UC09";
    private static final String OPERATION = "ACTUAL_TREATMENT_RECORD";
    record Context(long clinic, long patient, long visit) {}
    record Consent(long id,String scope,String outcome,List<ConsentProjectScopes.Project> projectScopes){Consent(long id,String scope,String outcome){this(id,scope,outcome,List.of());}}
    record Input(long consent, boolean performed, String project, String unit, BigDecimal quantity,
                 String specialRequirements, String nonPerformanceReason,String patientFindings,String aftercareInstructions,String projectKey) {
        Input(long consent,boolean performed,String project,String unit,BigDecimal quantity,String special,String reason,String findings,String aftercare){this(consent,performed,project,unit,quantity,special,reason,findings,aftercare,null);}
        Input(long consent,boolean performed,String project,String unit,BigDecimal quantity,String special,String reason){this(consent,performed,project,unit,quantity,special,reason,null,null,null);}
    }

    private ConsentProjectScopes projectScopes;
    VisitTreatmentService enableProjectScopes(ConsentProjectScopes scopes){projectScopes=scopes;return this;}
    private boolean patientNotesEnabled;
    VisitTreatmentService enablePatientNotes(){patientNotesEnabled=true;return this;}
    private final JdbcTransactions tx;
    private final StaffAuthorization auth;
    private final VisitHealthService.DentistIdentity dentists;
    private final FormalBookingService.AppointmentIds ids;
    private final Clock clock;
    private final CommandRecovery commands;

    VisitTreatmentService(JdbcTransactions tx, StaffAuthorization auth, VisitHealthService.DentistIdentity dentists,
                          FormalBookingService.AppointmentIds ids, EventIdAllocator auditIds, Clock clock) {
        this.tx = tx;
        this.auth = auth;
        this.dentists = dentists;
        this.ids = ids;
        this.clock = clock;
        this.commands = new UcAuditService(tx, auth, this, clock, auditIds).commands();
    }

    List<Consent> read(Context target) {
        return tx.execute(c -> {
            long dentist = requireVisit(c, target);
            var consents = new ArrayList<Consent>();
            try (var sql = c.prepareStatement("""
                    SELECT consent_result_id, proposed_treatment, consent_result
                    FROM adc.treatment_consent_result WHERE visit_id=? ORDER BY recorded_at, consent_result_id
                    """)) {
                sql.setLong(1, target.visit());
                try (var rows = sql.executeQuery()) {
                    while (rows.next()) consents.add(new Consent(rows.getLong(1),rows.getString(2),rows.getString(3),projectScopes==null?List.of():projectScopes.read(c,rows.getLong(1))));
                }
            }
            if (requireVisit(c, target) != dentist) throw new SecurityException("TREATMENT_SCOPE_CHANGED");
            return List.copyOf(consents);
        });
    }

    JsonNode records(Context target){
        if(projectScopes==null)return null;
        return tx.execute(c->{requireVisit(c,target);var result=JSON.createArrayNode();
            try(var p=c.prepareStatement("SELECT r.clinical_record_id,v.record_version_no,v.implementation_result,v.performed_treatment,v.billing_unit,v.actual_quantity,v.performed_project_key FROM adc.clinical_record r JOIN adc.clinical_record_version v USING(clinical_record_id) WHERE r.visit_id=? AND v.is_current ORDER BY r.clinical_record_id")){
                p.setLong(1,target.visit());try(var r=p.executeQuery()){while(r.next())result.addObject().put("clinicalRecordId",r.getString(1)).put("version",r.getInt(2)).put("implementationResult",r.getString(3)).put("project",r.getString(4)).put("unit",r.getString(5)).put("quantity",r.getString(6)).put("projectKey",r.getString(7));}}
            requireVisit(c,target);return result;});
    }

    CommandRecovery.Receipt save(Context target, UUID key, Input input) {
        validate(input);
        if(input.projectKey()!=null){if(projectScopes==null)throw new IllegalArgumentException("PROJECT_KEYS_NOT_ENABLED");var item=projectScopes.catalog.requireKey(input.projectKey());if(input.performed()&&(!item.name().equals(input.project())||!item.unit().equals(input.unit())))throw new IllegalArgumentException("PROJECT_CATALOG_CHANGED");}
        if((input.patientFindings()!=null||input.aftercareInstructions()!=null)&&!patientNotesEnabled)throw new IllegalArgumentException("PATIENT_NOTES_NOT_ENABLED");
        var payload = JSON.createObjectNode().put("patientId", target.patient()).put("visitId", target.visit())
                .put("consentResultId", input.consent()).put("performed", input.performed()).put("project", input.project())
                .put("unit", input.unit()).put("quantity", quantityText(input.quantity()))
                .put("specialRequirements", input.specialRequirements()).put("nonPerformanceReason", input.nonPerformanceReason());
        if(input.patientFindings()!=null||input.aftercareInstructions()!=null)payload.put("patientFindings",input.patientFindings()).put("aftercareInstructions",input.aftercareInstructions());
        if(input.projectKey()!=null)payload.put("projectKey",input.projectKey());
        return commands.execute(new CommandRecovery.Command(OPERATION, UC, key, payload, Set.of(target.clinic())),
                (c, locks) -> append(c, target, input));
    }

    static void validate(Input input) {
        for(String text:new String[]{input.patientFindings(),input.aftercareInstructions()})if(text!=null&&(text.isBlank()||text.codePointCount(0,text.length())>4000))throw new IllegalArgumentException("INVALID_PATIENT_NOTES");
        var quantity = input.quantity();
        if (input.consent() <= 0 || (input.performed()
                ? empty(input.project()) || input.project().codePointCount(0, input.project().length()) > 200
                  || empty(input.unit()) || input.unit().codePointCount(0, input.unit().length()) > 40
                  || quantity == null || quantity.signum() <= 0 || quantity.scale() > 3
                  || quantity.precision() - quantity.scale() > 15
                : empty(input.nonPerformanceReason()) || input.project() != null || input.unit() != null || quantity != null)) {
            throw new Rejection("INVALID_TREATMENT_INPUT");
        }
    }

    private static boolean empty(String value) { return value == null || value.isBlank(); }

    AuditDocuments.Change append(Connection c, Context target, Input input) throws SQLException {
        String actor = auth.require(UC, Set.of(target.clinic())).actor();
        long dentist = requireVisit(c, target);
        if(projectScopes!=null&&input.projectKey()!=null)projectScopes.require(c,input.consent(),target.visit(),input.projectKey(),input.performed(),input.unit());
        else {
            if(projectScopes!=null&&!projectScopes.read(c,input.consent()).isEmpty())throw new Rejection("PROJECT_SELECTION_REQUIRED");
        try (var sql = c.prepareStatement("""
                SELECT proposed_treatment, consent_result FROM adc.treatment_consent_result
                WHERE consent_result_id=? AND visit_id=?
                """)) {
            sql.setLong(1, input.consent());
            sql.setLong(2, target.visit());
            try (var row = sql.executeQuery()) {
                if (!row.next() || (input.performed()
                        && (!"同意".equals(row.getString(2)) || !input.project().equals(row.getString(1))))) {
                    throw new Rejection("TREATMENT_CONSENT_REVIEW_REQUIRED");
                }
            }
        }
        }
        long record = ids.next(c);
        if (record <= 0) throw new SQLException("CLINICAL_RECORD_ID_UNAVAILABLE", "22003");
        try (var sql = c.prepareStatement("INSERT INTO adc.clinical_record (clinical_record_id, visit_id) VALUES (?,?)")) {
            sql.setLong(1, record);
            sql.setLong(2, target.visit());
            requireWritten(sql.executeUpdate());
        }
        var at = StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC);
        String outcome = input.performed() ? "已实施" : "未实施";
        String noteColumns=(patientNotesEnabled?",patient_findings,aftercare_instructions":"")+(projectScopes!=null?",performed_project_key":"");
        String noteValues=(patientNotesEnabled?",?,?":"")+(projectScopes!=null?",?":"");
        try(var sql=c.prepareStatement("INSERT INTO adc.clinical_record_version (clinical_record_id,record_version_no,consent_result_id,responsible_dentist_id,performed_treatment,billing_unit,actual_quantity,implementation_result,special_requirements,non_performance_reason,revision_basis,recorded_by,recorded_at,is_current"+noteColumns+") VALUES (?,1,?,?,?,?,?,?,?,?,NULL,?,?,true"+noteValues+")")) {
            sql.setLong(1, record);
            sql.setLong(2, input.consent());
            sql.setLong(3, dentist);
            sql.setString(4, input.project());
            sql.setString(5, input.unit());
            sql.setBigDecimal(6, input.quantity());
            sql.setString(7, outcome);
            sql.setString(8, input.specialRequirements());
            sql.setString(9, input.nonPerformanceReason());
            sql.setString(10, actor);
            sql.setObject(11, at);
            if(patientNotesEnabled){sql.setString(12,input.patientFindings());sql.setString(13,input.aftercareInstructions());}
            if(projectScopes!=null)sql.setString(patientNotesEnabled?14:12,input.projectKey());
            requireWritten(sql.executeUpdate());
        }
        var reference = JSON.createObjectNode().put("clinicalRecordId", record).put("recordVersionNo", 1)
                .put("visitId", target.visit());
        var evidence = JSON.createObjectNode().put("recordHash", fingerprint(input.consent(), dentist, input.project(),
                input.unit(), input.quantity(), outcome, input.specialRequirements(), input.nonPerformanceReason(), actor, at,input.patientFindings(),input.aftercareInstructions(),input.projectKey()));
        return new AuditDocuments.Change(reference, null, reference, evidence);
    }

    private static void requireWritten(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("TREATMENT_WRITE_UNCONFIRMED", "P0001");
    }

    private long requireVisit(Connection c, Context target) throws SQLException {
        return TreatingDentistAccess.require(c, auth, dentists, UC, target.clinic(), target.patient(), target.visit());
    }

    private static String quantityText(BigDecimal quantity) {
        return quantity == null ? null : quantity.stripTrailingZeros().toPlainString();
    }

    private static String fingerprint(long consent, long dentist, String project, String unit, BigDecimal quantity,
                                      String outcome, String special, String reason, String actor, OffsetDateTime at,String findings,String aftercare,String projectKey) {
        var body=JSON.createArrayNode().add(consent).add(dentist).add(project).add(unit).add(quantityText(quantity)).add(outcome).add(special).add(reason).add(actor).add(at.toInstant().toString());
        if(findings!=null||aftercare!=null)body.add(findings).add(aftercare);
        if(projectKey!=null)body.add(projectKey);
        return AuditDocuments.hash(body);
    }

    @Override
    public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
        if (!UC.equals(command.ucCode()) || !OPERATION.equals(command.operation()) || command.clinics().size() != 1) return false;
        var target = new Context(command.clinics().iterator().next(), command.payload().path("patientId").asLong(),
                command.payload().path("visitId").asLong());
        requireVisit(c, target);
        var result = saved.path("after");
        if (!result.equals(saved.path("businessReference")) || result.path("visitId").asLong() != target.visit()
                || result.path("recordVersionNo").asInt() != 1) return false;
        try (var sql = c.prepareStatement("""
                SELECT v.consent_result_id, v.responsible_dentist_id, v.performed_treatment, v.billing_unit,
                       v.actual_quantity, v.implementation_result, v.special_requirements, v.non_performance_reason,
                       v.recorded_by, v.recorded_at %s %s
                FROM adc.clinical_record_version v JOIN adc.clinical_record r ON r.clinical_record_id=v.clinical_record_id
                WHERE r.visit_id=? AND r.clinical_record_id=? AND v.record_version_no=1
                """.formatted(patientNotesEnabled?",v.patient_findings,v.aftercare_instructions":"",projectScopes!=null?",v.performed_project_key":""))) {
            sql.setLong(1, target.visit());
            sql.setLong(2, result.path("clinicalRecordId").asLong());
            try (var row = sql.executeQuery()) {
                return row.next() && fingerprint(row.getLong(1), row.getLong(2), row.getString(3), row.getString(4),
                        row.getBigDecimal(5), row.getString(6), row.getString(7), row.getString(8), row.getString(9),
                        row.getObject(10, OffsetDateTime.class),patientNotesEnabled?row.getString(11):null,patientNotesEnabled?row.getString(12):null,projectScopes!=null?row.getString(patientNotesEnabled?13:11):null).equals(saved.path("verification").path("recordHash").asText());
            }
        }
    }

    @Override
    public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        return true;
    }
}
