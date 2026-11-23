package cn.adc.quarterly;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import java.sql.*;
import java.util.*;
import static cn.adc.shared.audit.AuditDocuments.JSON;
import static cn.adc.quarterly.QuarterlyCalculationService.money;

/** Frozen records only. Exact full-clinic scope is required before every read. */
final class QuarterlyRecordsService {
    private final JdbcTransactions tx;
    private final StaffAuthorization auth;
    QuarterlyRecordsService(JdbcTransactions tx, StaffAuthorization auth) { this.tx=tx; this.auth=auth; }
    String action(boolean previews, Set<Long> clinics) {
        if (previews) { auth.require("UC17",clinics); return "UC17"; }
        if (auth.evaluate("UC19",clinics).allowed()) return "UC19";
        auth.require("UC20",clinics); return "UC20";
    }
    ObjectNode list(boolean previews,String quarter,Set<Long> clinics,long before,int limit) {
        QuarterlyWindow.of(quarter);
        if(before<=0||limit<1||limit>50)throw new IllegalArgumentException("INVALID_PAGE");
        String action=action(previews,clinics);
        return tx.execute(c->{readOnly(c);var result=list(c,previews,quarter,clinics,before,limit);auth.require(action,clinics);return result;});
    }
    static void readOnly(Connection c)throws SQLException {try(var s=c.createStatement()){s.execute("SET TRANSACTION READ ONLY");}}
    static String scopePredicate(Set<Long> clinics) {
        if(clinics.isEmpty()||clinics.size()>50||clinics.stream().anyMatch(id->id==null||id<=0))throw new IllegalArgumentException("INVALID_SCOPE");
        return "(SELECT count(*) FROM adc.quarterly_statistic_clinic sc WHERE sc.statistic_id=s.statistic_id)=? AND NOT EXISTS (SELECT 1 FROM adc.quarterly_statistic_clinic sc WHERE sc.statistic_id=s.statistic_id AND sc.clinic_id NOT IN ("+String.join(",",Collections.nCopies(clinics.size(),"?"))+"))";
    }
    static ObjectNode list(Connection c,boolean previews,String quarter,Set<Long> clinics,long before,int limit)throws SQLException {
        String columns=previews?"s.statistic_id,s.aggregation_readiness,s.receivable_amount,s.received_amount,s.operating_expense,s.consumption_cost,s.generated_at,s.source_data_version":
            "v.calculation_id,v.calculation_version_no,v.statistic_id,v.report_status,v.net_profit,v.incentive_pool_amount,v.generated_at,EXISTS(SELECT 1 FROM adc.financial_review r WHERE r.calculation_id=v.calculation_id AND r.calculation_version_no=v.calculation_version_no AND r.review_result='通过')";
        String from=previews?"adc.quarterly_operating_statistic s":"adc.quarterly_operating_calculation_version v JOIN adc.quarterly_operating_statistic s ON s.statistic_id=v.statistic_id";
        String id=previews?"s.statistic_id":"v.calculation_id";
        String latest=previews?"":" AND NOT EXISTS (SELECT 1 FROM adc.quarterly_operating_calculation_version newer WHERE newer.calculation_id=v.calculation_id AND newer.calculation_version_no>v.calculation_version_no)";
        String sql="SELECT "+columns+" FROM "+from+" WHERE s.quarter_code=? AND "+id+"<? AND "+scopePredicate(clinics)+latest+" ORDER BY "+id+" DESC LIMIT ?";
        var result=JSON.createObjectNode().put("quarter",quarter);scope(result,clinics);var items=result.putArray("items");
        try(var p=c.prepareStatement(sql)){
            int i=1;p.setString(i++,quarter);p.setLong(i++,before);p.setInt(i++,clinics.size());
            for(long clinic:clinics.stream().sorted().toList())p.setLong(i++,clinic);
            p.setInt(i,limit+1);
            try(var r=p.executeQuery()){
                while(r.next()){
                    if(items.size()==limit){result.put("nextBeforeId",items.get(items.size()-1).path(previews?"statisticId":"calculationId").asText());break;}
                    var item=items.addObject().put("quarter",quarter);scope(item,clinics);
                    if(previews)item.put("statisticId",Long.toString(r.getLong(1))).put("readiness",r.getString(2)).put("receivable",money(r.getBigDecimal(3))).put("received",money(r.getBigDecimal(4))).put("expense",money(r.getBigDecimal(5))).put("consumption",money(r.getBigDecimal(6))).put("generatedAt",r.getString(7)).put("sourceVersion",r.getString(8));
                    else item.put("calculationId",Long.toString(r.getLong(1))).put("version",r.getInt(2)).put("statisticId",Long.toString(r.getLong(3))).put("status",r.getString(4)).put("netProfit",money(r.getBigDecimal(5))).put("incentivePool",money(r.getBigDecimal(6))).put("generatedAt",r.getString(7)).put("reviewed",r.getBoolean(8));
                }
            }
        }
        return result;
    }
    ObjectNode preview(long id,Set<Long> clinics){
        auth.require("UC17",clinics);
        return tx.execute(c->{readOnly(c);QuarterlyCalculationService.requireScope(c,id,clinics);var result=preview(c,id,clinics);auth.require("UC17",clinics);return result;});
    }
    static ObjectNode preview(Connection c,long id,Set<Long> clinics)throws SQLException {
        try(var p=c.prepareStatement("SELECT quarter_code,aggregation_readiness,receivable_amount,received_amount,operating_expense,consumption_cost,due_appointment_count,confirmed_no_show_count,cancelled_appointment_count,no_show_rate,generated_at,source_data_version,accounting_basis,source_manifest_reference FROM adc.quarterly_operating_statistic WHERE statistic_id=?")){
            p.setLong(1,id);try(var r=p.executeQuery()){
                if(!r.next())throw new IllegalArgumentException("RECORD_UNAVAILABLE");
                var result=JSON.createObjectNode().put("statisticId",Long.toString(id)).put("quarter",r.getString(1)).put("readiness",r.getString(2)).put("receivable",money(r.getBigDecimal(3))).put("received",money(r.getBigDecimal(4))).put("expense",money(r.getBigDecimal(5))).put("consumption",money(r.getBigDecimal(6))).put("due",count(r.getString(7))).put("noShow",count(r.getString(8))).put("cancelled",count(r.getString(9))).put("noShowRate",r.getString(10)).put("generatedAt",r.getString(11)).put("sourceVersion",r.getString(12)).put("manifestReference",r.getString(14));
                result.set("basis",AuditDocuments.parse(r.getString(13)));scope(result,clinics);
                result.set("performance",performance(c,"stat",id,null));
                var issues=result.putArray("issues");
                try(var q=c.prepareStatement("SELECT issue_id,clinic_id,original_data,correction_status FROM adc.quarterly_data_issue WHERE statistic_id=? ORDER BY issue_id")){
                    q.setLong(1,id);try(var rows=q.executeQuery()){while(rows.next())issues.addObject().put("issueId",Long.toString(rows.getLong(1))).put("clinicId",rows.getString(2)).put("reason",rows.getString(3)).put("status",rows.getString(4));}
                }
                return result;
            }
        }
    }
    ObjectNode calculation(long id,int version,Set<Long> clinics){
        if(version<1)throw new IllegalArgumentException("INVALID_VERSION");auth.require("UC19",clinics);
        return tx.execute(c->{readOnly(c);var result=calculation(c,id,version,clinics);auth.require("UC19",clinics);return result;});
    }
    static ObjectNode calculation(Connection c,long id,int version,Set<Long> clinics)throws SQLException {
        // Scope belongs to the requested version, not merely the latest version.
        try(var p=c.prepareStatement("SELECT statistic_id,calculation_basis,net_profit,incentive_pool_amount,report_status,pending_verification_notes,generated_at,EXISTS(SELECT 1 FROM adc.financial_review r WHERE r.calculation_id=v.calculation_id AND r.calculation_version_no=v.calculation_version_no AND r.review_result='通过') FROM adc.quarterly_operating_calculation_version v WHERE calculation_id=? AND calculation_version_no=?")){
            p.setLong(1,id);p.setInt(2,version);try(var r=p.executeQuery()){
                if(!r.next())throw new IllegalArgumentException("RECORD_UNAVAILABLE");
                long statistic=r.getLong(1);QuarterlyCalculationService.requireScope(c,statistic,clinics);
                var result=QuarterlyCalculationService.reference(statistic,id,version).put("netProfit",money(r.getBigDecimal(3))).put("incentivePool",money(r.getBigDecimal(4))).put("status",r.getString(5)).put("pendingNotes",r.getString(6)).put("generatedAt",r.getString(7)).put("reviewed",r.getBoolean(8));
                result.set("basis",AuditDocuments.parse(r.getString(2)));scope(result,clinics);result.set("performance",performance(c,"calc",id,version));
                var versions=result.putArray("versions");
                try(var q=c.prepareStatement("SELECT v.calculation_version_no,v.generated_at FROM adc.quarterly_operating_calculation_version v JOIN adc.quarterly_operating_statistic s ON s.statistic_id=v.statistic_id WHERE v.calculation_id=? AND "+scopePredicate(clinics)+" ORDER BY v.calculation_version_no DESC")){
                    int i=1;q.setLong(i++,id);q.setInt(i++,clinics.size());for(long clinic:clinics.stream().sorted().toList())q.setLong(i++,clinic);
                    try(var rows=q.executeQuery()){while(rows.next())versions.addObject().put("version",rows.getInt(1)).put("generatedAt",rows.getString(2));}
                }
                return result;
            }
        }
    }
    static ArrayNode performance(Connection c,String kind,long id,Integer version)throws SQLException {
        if(!Set.of("stat","calc").contains(kind))throw new IllegalArgumentException("INVALID_PERFORMANCE_KIND");
        var result=JSON.createArrayNode();
        String where=kind.equals("stat")?"statistic_id=?":"calculation_id=? AND calculation_version_no=?";
        try(var p=c.prepareStatement("SELECT dentist_id,performance_amount FROM adc.quarterly_dentist_performance_"+kind+" WHERE "+where+" ORDER BY dentist_id")){
            p.setLong(1,id);if(version!=null)p.setInt(2,version);
            try(var r=p.executeQuery()){while(r.next())result.addObject().put("dentistId",Long.toString(r.getLong(1))).put("amount",money(r.getBigDecimal(2)));}
        }return result;
    }
    static Integer count(String value){return value==null?null:Integer.valueOf(value);}
    static void scope(ObjectNode target,Set<Long> clinics){var array=target.putArray("clinics");clinics.stream().sorted().forEach(id->array.add(Long.toString(id)));}
}
