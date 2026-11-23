package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import java.sql.*;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Explicit reception verification; preserves patient rows and never fabricates a create audit. */
final class ExistingPatientLink {
    private record Source(UUID account,String identity,Long patient,int version) {}
    JsonNode lookup(Connection c,UUID request,int version,long clinic)throws SQLException {
        var source=source(c,request,clinic,false);
        if(source.version()!=version||source.patient()!=null)throw new IllegalArgumentException("EXISTING_SOURCE_STALE");
        return candidate(c,source.identity());
    }
    JsonNode link(Connection c,UUID request,int version,long clinic,long patient,String actor,String basis)throws SQLException {
        if(!"SYNTHETIC reception".equals(actor)||version<0||version==Integer.MAX_VALUE||basis==null||basis.isBlank()||basis.length()>2000)
            throw new SecurityException("EXPLICIT_PATIENT_VERIFICATION_REQUIRED");
        var source=source(c,request,clinic,true);
        if(source.patient()!=null) {
            if(source.patient()==patient&&source.version()==version+1&&binding(c,source.account(),patient,request))
                return receipt(request,patient,source.version());
            throw new IllegalArgumentException("EXISTING_SOURCE_STALE");
        }
        if(source.version()!=version)throw new IllegalArgumentException("EXISTING_SOURCE_STALE");
        var candidate=candidate(c,source.identity());
        if(!Long.toString(patient).equals(candidate.path("patientId").asText()))throw new SecurityException("EXISTING_PATIENT_MISMATCH");
        try(var q=c.prepareStatement("""
            INSERT INTO adc_mini.patient_account_binding(account_id,patient_id,verified_staff,verification_basis,verified_request_id)
            SELECT account_id,?,?,?,? FROM adc_mini.patient_account WHERE account_id=? AND NOT disabled
            ON CONFLICT(account_id) DO NOTHING
            """)) {
            q.setLong(1,patient);q.setString(2,actor);q.setString(3,basis);q.setObject(4,request);q.setObject(5,source.account());
            if(q.executeUpdate()!=1&&!binding(c,source.account(),patient,request))throw new SecurityException("ACCOUNT_BINDING_CONFLICT");
        }
        try(var q=c.prepareStatement("""
            UPDATE adc_mini.preliminary_request SET associated_patient_id=?,request_version=?
            WHERE request_id=? AND request_version=? AND associated_patient_id IS NULL
            """)) {
            q.setLong(1,patient);q.setInt(2,version+1);q.setObject(3,request);q.setInt(4,version);
            if(q.executeUpdate()!=1)throw new IllegalArgumentException("EXISTING_SOURCE_STALE");
        }
        return receipt(request,patient,version+1);
    }
    private Source source(Connection c,UUID request,long clinic,boolean lock)throws SQLException {
        try(var q=c.prepareStatement("""
            SELECT r.applicant_account,r.submitted_content#>>'{identity,patientIdentification}',r.associated_patient_id,r.request_version
            FROM adc_mini.preliminary_request r JOIN adc_mini.patient_account a
              ON r.applicant_account=('ACCOUNT:' || a.account_id::text)
            WHERE r.request_id=? AND r.submitted_content->>'type'='NEW'
              AND r.submitted_content->>'intendedClinicId'=? AND NOT a.disabled
              AND EXISTS(SELECT 1 FROM adc_mini.operation_audit_event e WHERE e.request_id=r.request_id AND e.operation='op008')
            """+(lock?" FOR UPDATE OF r":""))) {
            q.setObject(1,request);q.setString(2,Long.toString(clinic));
            try(var r=q.executeQuery()) {
                if(!r.next())throw new SecurityException("EXISTING_SOURCE_UNVERIFIED");
                  RequestDecisions.requireOpen(c,request);
                var account=JdbcPatientAccountScope.accountId(r.getString(1));
                if(account==null)throw new SecurityException("EXISTING_SOURCE_UNVERIFIED");
                return new Source(account,r.getString(2),r.getObject(3,Long.class),r.getInt(4));
            }
        }
    }
    private JsonNode candidate(Connection c,String identity)throws SQLException {
        if(identity==null||identity.isBlank())throw new IllegalArgumentException("EXISTING_PATIENT_NOT_FOUND");
        try(var q=c.prepareStatement("SELECT patient_id,patient_name,contact_information FROM adc.patient WHERE identity_information=? ORDER BY patient_id LIMIT 2")) {
            q.setString(1,identity);
            try(var r=q.executeQuery()) {
                if(!r.next())throw new IllegalArgumentException("EXISTING_PATIENT_NOT_FOUND");
                var result=AuditDocuments.JSON.createObjectNode().put("patientId",Long.toString(r.getLong(1)))
                    .put("patientName",r.getString(2)).put("contactHint",mask(r.getString(3)));
                if(r.next())throw new IllegalArgumentException("EXISTING_PATIENT_AMBIGUOUS");
                return result;
            }
        }
    }
    private boolean binding(Connection c,UUID account,long patient,UUID request)throws SQLException {
        try(var q=c.prepareStatement("SELECT 1 FROM adc_mini.patient_account_binding WHERE account_id=? AND patient_id=? AND verified_request_id=?")) {
            q.setObject(1,account);q.setLong(2,patient);q.setObject(3,request);
            try(var r=q.executeQuery()){return r.next();}
        }
    }
    private static String mask(String text){return text==null||text.isBlank()?"未提供":text.substring(0,text.offsetByCodePoints(0,1))+"***";}
    private static JsonNode receipt(UUID request,long patient,int version){return AuditDocuments.JSON.createObjectNode()
        .put("bound",true).put("reusedExistingPatient",true).put("requestId",request.toString()).put("patientId",Long.toString(patient)).put("requestVersion",version);}
}
