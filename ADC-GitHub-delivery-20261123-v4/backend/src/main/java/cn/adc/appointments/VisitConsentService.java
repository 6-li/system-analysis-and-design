package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore.EventIdAllocator;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

final class VisitConsentService implements CommandRecovery.SuccessVerifier {
    private static final String UC = "UC08";
    private static final String OPERATION = "TREATMENT_CONSENT_RECORD";

    record Context(long clinic, long patient, long visit) {}
    record Input(String party, boolean guardianRequired, String guardianParticipation,
                 String treatment, String outcome, String comments,List<String> projectKeys,String catalogVersion) {
        Input(String party,boolean guardian,String participation,String treatment,String outcome,String comments){this(party,guardian,participation,treatment,outcome,comments,null,null);}
    }
    record Entry(long id, String party, String guardianParticipation, String treatment,
                 String outcome, String comments, String actor, OffsetDateTime recordedAt, long dentist,List<ConsentProjectScopes.Project> projectScopes) {
        Entry(long id,String party,String guardian,String treatment,String outcome,String comments,String actor,OffsetDateTime at,long dentist){this(id,party,guardian,treatment,outcome,comments,actor,at,dentist,List.of());}
    }

    private ConsentProjectScopes projectScopes;
    VisitConsentService enableProjectScopes(ConsentProjectScopes value){projectScopes=value;return this;}
    private final JdbcTransactions transactions;
    private final StaffAuthorization authorization;
    private final VisitHealthService.DentistIdentity dentists;
    private final FormalBookingService.AppointmentIds ids;
    private final Clock clock;
    private final CommandRecovery commands;

    VisitConsentService(JdbcTransactions transactions, StaffAuthorization authorization,
                        VisitHealthService.DentistIdentity dentists, FormalBookingService.AppointmentIds ids,
                        EventIdAllocator auditIds, Clock clock) {
        this.transactions = transactions;
        this.authorization = authorization;
        this.dentists = dentists;
        this.ids = ids;
        this.clock = clock;
        this.commands = new UcAuditService(transactions, authorization, this, clock, auditIds).commands();
    }

    List<Entry> read(Context context) {
        return transactions.execute(c -> {
            long dentist = requireVisit(c, context);
            var entries = new ArrayList<Entry>();
            try (var query = c.prepareStatement("""
                    SELECT consent_result_id, communication_party, guardian_participation, proposed_treatment,
                           consent_result, comments, recorded_by, recorded_at, recording_dentist_id
                    FROM adc.treatment_consent_result WHERE visit_id=?
                    ORDER BY recorded_at, consent_result_id
                    """)) {
                query.setLong(1, context.visit());
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {var e=entry(rows);entries.add(projectScopes==null?e:new Entry(e.id(),e.party(),e.guardianParticipation(),e.treatment(),e.outcome(),e.comments(),e.actor(),e.recordedAt(),e.dentist(),projectScopes.read(c,e.id())));}
                }
            }
            if (requireVisit(c, context) != dentist) throw new SecurityException("CONSENT_SCOPE_CHANGED");
            return List.copyOf(entries);
        });
    }

    CommandRecovery.Receipt save(Context context, UUID key, Input input) {
        validate(input);
        if(input.projectKeys()!=null){if(projectScopes==null)throw new IllegalArgumentException("PROJECT_SCOPES_NOT_ENABLED");projectScopes.selected(input.projectKeys(),input.catalogVersion());}else if(input.catalogVersion()!=null)throw new IllegalArgumentException("PROJECT_SCOPE_REQUIRED");
        var payload = JSON.createObjectNode().put("visitId", context.visit()).put("patientId", context.patient())
                .put("communicationParty", input.party()).put("guardianRequired", input.guardianRequired())
                .put("guardianParticipation", input.guardianParticipation()).put("proposedTreatment", input.treatment())
                .put("consentResult", input.outcome()).put("comments", input.comments());
        if(input.projectKeys()!=null){payload.set("projectKeys",JSON.valueToTree(input.projectKeys().stream().sorted().toList()));payload.put("catalogVersion",input.catalogVersion());}
        return commands.execute(new CommandRecovery.Command(OPERATION, UC, key, payload, Set.of(context.clinic())),
                (c, locks) -> append(c, context, input));
    }

    static void validate(Input input) {
        if (empty(input.party()) || empty(input.treatment()) || empty(input.outcome())
                || !Set.of("同意", "拒绝", "待确认").contains(input.outcome())
                || (input.guardianRequired() && empty(input.guardianParticipation()))
                || (!"同意".equals(input.outcome()) && empty(input.comments()))) {
            throw new IllegalArgumentException("INVALID_CONSENT_INPUT");
        }
    }

    private static boolean empty(String value) { return value == null || value.isBlank(); }

    AuditDocuments.Change append(Connection c, Context context, Input input) throws SQLException {
        String actor = authorization.require(UC, Set.of(context.clinic())).actor();
        long dentist = requireVisit(c, context);
        long id = ids.next(c);
        if (id <= 0) throw new SQLException("CONSENT_ID_UNAVAILABLE", "22003");
        var at = StrictInputPolicy.eventTime(clock).atOffset(ZoneOffset.UTC);
        try (var insert = c.prepareStatement("""
                INSERT INTO adc.treatment_consent_result
                    (consent_result_id, visit_id, communication_party, guardian_participation, proposed_treatment,
                     consent_result, comments, recorded_by, recorded_at, recording_dentist_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setLong(1, id);
            insert.setLong(2, context.visit());
            insert.setString(3, input.party());
            insert.setString(4, input.guardianParticipation());
            insert.setString(5, input.treatment());
            insert.setString(6, input.outcome());
            insert.setString(7, input.comments());
            insert.setString(8, actor);
            insert.setObject(9, at);
            insert.setLong(10, dentist);
            if (insert.executeUpdate() != 1) throw new SQLException("CONSENT_WRITE_UNCONFIRMED", "P0001");
        }
        var reference = JSON.createObjectNode().put("consentResultId", id).put("visitId", context.visit());
        var stored = new Entry(id, input.party(), input.guardianParticipation(), input.treatment(), input.outcome(),
                input.comments(), actor, at, dentist);
        var proof=JSON.createObjectNode().put("entryHash",fingerprint(stored));
        if(input.projectKeys()!=null){var selected=projectScopes.selected(input.projectKeys(),input.catalogVersion());projectScopes.insert(c,id,selected);proof.put("scopeHash",ConsentProjectScopes.hash(selected));}
        return new AuditDocuments.Change(reference,null,reference,proof);
    }

    private long requireVisit(Connection c, Context context) throws SQLException {
        return TreatingDentistAccess.require(c, authorization, dentists, UC, context.clinic(), context.patient(), context.visit());
    }

    private static Entry entry(ResultSet rows) throws SQLException {
        return new Entry(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5),
                rows.getString(6), rows.getString(7), rows.getObject(8, OffsetDateTime.class), rows.getLong(9));
    }

    private static String fingerprint(Entry row) {
        return AuditDocuments.hash(JSON.createArrayNode().add(row.id()).add(row.party()).add(row.guardianParticipation())
                .add(row.treatment()).add(row.outcome()).add(row.comments()).add(row.actor())
                .add(row.recordedAt().toInstant().toString()).add(row.dentist()));
    }

    @Override
    public boolean matchesCommitted(Connection c, CommandRecovery.Command command, JsonNode saved) throws SQLException {
        if (!UC.equals(command.ucCode()) || !OPERATION.equals(command.operation()) || command.clinics().size() != 1) return false;
        var context = new Context(command.clinics().iterator().next(), command.payload().path("patientId").asLong(),
                command.payload().path("visitId").asLong());
        requireVisit(c, context);
        var result = saved.path("after");
        if (!result.equals(saved.path("businessReference")) || result.path("visitId").asLong() != context.visit()) return false;
        try (var query = c.prepareStatement("""
                SELECT consent_result_id, communication_party, guardian_participation, proposed_treatment,
                       consent_result, comments, recorded_by, recorded_at, recording_dentist_id
                FROM adc.treatment_consent_result WHERE consent_result_id=? AND visit_id=?
                """)) {
            query.setLong(1, result.path("consentResultId").asLong());
            query.setLong(2, context.visit());
            try (var row = query.executeQuery()) {
                if(!row.next()||!fingerprint(entry(row)).equals(saved.path("verification").path("entryHash").asText()))return false;
                if(command.payload().has("projectKeys"))return projectScopes!=null&&ConsentProjectScopes.hash(projectScopes.read(c,result.path("consentResultId").asLong())).equals(saved.path("verification").path("scopeHash").asText());
                return true;
            }
        }
    }

    @Override
    public boolean hasContradictorySuccess(Connection c, CommandRecovery.Command command, String reference) {
        // Without a terminal audit there is no trustworthy request-to-row link.
        return true;
    }
}
