package cn.adc;
import cn.adc.shared.db.*;
import cn.adc.shared.audit.*;
import cn.adc.shared.security.StaffAuthorization;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Explicit synthetic fixtures; no clinic/project/default is supplied to production code. */
public final class PgITSupport {
 private PgITSupport(){}
 private static final AtomicLong IDS=new AtomicLong(10000);
 public static long next(){return IDS.incrementAndGet();}
 public static DataSource app(){return new P0DriverDataSource(System.getenv("ADC_P0_TEST_URL"),"p0_probe_app",()->System.getenv("ADC_P0_TEST_PASSWORD"));}
 public static DataSource config(){return configAt(System.getenv("ADC_P0_TEST_URL"),-1);}
 public static DataSource configProxy(){return configAt(System.getenv("ADC_P0_PROXY_URL"),Integer.parseInt(System.getenv("ADC_P0_TEST_URL").split(":")[3].split("/")[0]));}
 public static DataSource configAt(String url,int port){return new P0DriverDataSource(url,"p0_config_app",()->System.getenv("ADC_P0_CONFIG_PASSWORD"),port);}
 public static DataSource fixture(){return fixtureAt(System.getenv("ADC_P0_TEST_URL"));}
 public static DataSource fixtureAt(String url){return new P0DriverDataSource(url,"p0_config_fixture",()->System.getenv("ADC_P0_FIXTURE_PASSWORD"));}
 public static DataSource owner(){return new P0DriverDataSource(System.getenv("ADC_P0_TEST_URL"),"p0_fixture_owner",()->System.getenv("ADC_P0_MIGRATION_PASSWORD"));}
 public static DataSource migration(){return new P0DriverDataSource(System.getenv("ADC_P0_MIGRATION_URL"),"p0_fixture_owner",()->System.getenv("ADC_P0_MIGRATION_PASSWORD"));}
 public static DataSource proxy(){int port=Integer.parseInt(System.getenv("ADC_P0_TEST_URL").split(":")[3].split("/")[0]);return new P0DriverDataSource(System.getenv("ADC_P0_PROXY_URL"),"p0_probe_app",()->System.getenv("ADC_P0_TEST_PASSWORD"),port);}
 public static long count(String sql)throws SQLException {try(var c=app().getConnection();var st=c.createStatement();var r=st.executeQuery(sql)){r.next();return r.getLong(1);}}
 public static void sql(String sql)throws SQLException {try(var c=app().getConnection();var st=c.createStatement()){st.execute(sql);}}
 public static JsonNode row(Connection c,long id)throws SQLException {try(var p=c.prepareStatement("SELECT row_to_json(t)::text FROM adc.project_duration_version t WHERE project_id=? AND duration_version_no=1")){p.setLong(1,id);try(var r=p.executeQuery()){if(!r.next())throw new SQLException("SYNTHETIC_FIXTURE_MISSING");return AuditDocuments.parse(r.getString(1));}}}
 public static long project()throws SQLException {long id=next();try(var c=app().getConnection();var p=c.prepareStatement("INSERT INTO adc.project_duration_version(project_id,duration_version_no,project_name,duration_minutes,publication_status) VALUES(?,1,'SYNTHETIC-before',30,'DRAFT')")){p.setLong(1,id);p.executeUpdate();}return id;}
 public static ObjectNode envelope(JsonNode row){var e=AuditDocuments.JSON.createObjectNode();e.set("targetVersion",row);e.putNull("previousCurrentVersion");return e;}
 public static AuditDocuments.Change edit(Connection c,long id,String name)throws SQLException {var before=row(c,id);try(var p=c.prepareStatement("UPDATE adc.project_duration_version SET project_name=? WHERE project_id=? AND duration_version_no=1")){p.setString(1,name);p.setLong(2,id);p.executeUpdate();}var ref=AuditDocuments.JSON.createObjectNode();ref.put("projectId",Long.toString(id));ref.put("versionNo",1);return new AuditDocuments.Change(ref,envelope(before),envelope(row(c,id)),AuditDocuments.JSON.createObjectNode().put("source","SYNTHETIC_IT_ACTUAL_ROW_QUERY"));}
 public static CommandRecovery.Command command(long id){return new CommandRecovery.Command("DURATION_DRAFT_EDIT",null,UUID.randomUUID(),AuditDocuments.JSON.createObjectNode().put("projectId",id).put("name","SYNTHETIC-after"),Set.of());}
 public static StaffAuthorization authorization(AtomicReference<Map<String,StaffAuthorization.Grant>> grants){return new StaffAuthorization(()->Optional.of("synthetic-hq"),grants::get);}
 public static AtomicReference<Map<String,StaffAuthorization.Grant>> grants(){return new AtomicReference<>(Map.of("synthetic-hq",new StaffAuthorization.Grant("SYNTHETIC-POLICY-v1",Set.of("HQ"),Set.of("DURATION_EDIT","DURATION_PUBLISH"),Set.of(),true)));}
 public static CommandRecovery.SuccessVerifier actualVerifier(){return new CommandRecovery.SuccessVerifier(){
  public boolean matchesCommitted(Connection c,CommandRecovery.Command cmd,JsonNode saved)throws SQLException {var target=saved.path("after").path("targetVersion");if(target.isMissingNode())return false;JsonNode actual=row(c,target.get("project_id").longValue());if(actual.equals(target))return true;
   // A later independently committed edit may legitimately supersede the saved draft content.
   try(var p=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM adc.extension_operation_audit_event WHERE outcome='SUCCEEDED' AND evidence->'before'->'targetVersion'=?::jsonb)")){p.setString(1,target.toString());try(var r=p.executeQuery()){r.next();return r.getBoolean(1);}}
  }
  public boolean hasContradictorySuccess(Connection c,CommandRecovery.Command cmd,String ref)throws SQLException {try(var p=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM adc.project_duration_version WHERE publication_evidence->>'requestReference'=?)")){p.setString(1,ref);try(var r=p.executeQuery()){r.next();return r.getBoolean(1);}}}
 };}
 public static CommandRecovery hq(DataSource data,StaffAuthorization auth){return new ExtensionAuditService(new JdbcTransactions(data),auth,actualVerifier(),Clock.systemUTC()).commands();}
}
