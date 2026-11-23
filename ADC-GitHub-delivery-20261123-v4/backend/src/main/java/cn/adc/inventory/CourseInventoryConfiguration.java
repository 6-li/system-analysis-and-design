package cn.adc.inventory;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.*;
import java.sql.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

@Configuration(proxyBeanMethods=false) @Profile("course-demo")
@ConditionalOnProperty(name="adc.course.inventory-detail-enabled",havingValue="true")
class CourseInventoryConfiguration {
    static final String CONFIRM="SYNTHETIC-INVENTORY-CONF-v1", DELIVERY="SYNTHETIC-INVENTORY-DELIVERY-v1", COST="SYNTHETIC-INVENTORY-COST-v1";
    record Sources(long receipt,long consumption,Instant occurredAt) {
        static Sources load(Path path)throws java.io.IOException{
            if(!path.isAbsolute()||!Files.isRegularFile(path)||Files.size(path)>16384)throw new IllegalArgumentException("EXPLICIT_INVENTORY_SOURCE_REQUIRED");
            JsonNode n=AuditDocuments.JSON.readTree(Files.readString(path));
            if(!n.path("synthetic").asBoolean()||!"SYNTHETIC inventory v1".equals(n.path("version").asText())
                ||n.path("receiptId").asLong()<1000000||n.path("consumptionId").asLong()<1000000)throw new IllegalArgumentException("INVALID_INVENTORY_SOURCE");
            return new Sources(n.path("receiptId").asLong(),n.path("consumptionId").asLong(),Instant.parse(n.path("occurredAt").asText()));
        }
    }
    private final JdbcTransactions tx;
    private final Sources sources;
    private final boolean costV2;
    JdbcTransactions transactions(){return tx;}
    CourseInventoryConfiguration(Environment env)throws java.io.IOException{
        if(!"adc_course_validation".equals(env.getRequiredProperty("adc.course.database"))||env.getRequiredProperty("adc.course.database-port",Integer.class)!=55432||env.getRequiredProperty("adc.course.clinic-id",Long.class)!=81001)throw new IllegalArgumentException("INVENTORY_COURSE_SCOPE_REQUIRED");
        costV2=env.getProperty("adc.course.quarterly-usability-enabled",Boolean.class,false);
        if(costV2){var policyPath=Path.of(env.getRequiredProperty("adc.course.consumption-cost-policy"));if(!policyPath.isAbsolute()||!Files.isRegularFile(policyPath)||Files.size(policyPath)>16384)throw new IllegalArgumentException("COST_POLICY_REQUIRED");var policy=AuditDocuments.JSON.readTree(Files.readString(policyPath));if(!policy.path("synthetic").asBoolean()||!"SYNTHETIC consumption v2".equals(policy.path("version").asText())||!"2.00".equals(policy.path("unitCost").asText())||!"81001".equals(policy.path("clinicId").asText())||!"9000001".equals(policy.path("supplyId").asText()))throw new IllegalArgumentException("COST_POLICY_UNVERIFIED");}
        sources=Sources.load(Path.of(env.getRequiredProperty("adc.course.inventory-source")));
        var ds=new PGSimpleDataSource();ds.setServerNames(new String[]{"127.0.0.1"});ds.setPortNumbers(new int[]{55432});ds.setDatabaseName("adc_course_validation");ds.setUser("adc_course_inventory");ds.setPassword(env.getRequiredProperty("ADC_COURSE_DB_INVENTORY_PASSWORD"));ds.setOptions("-c timezone=UTC");ds.setConnectTimeout(3);ds.setSocketTimeout(10);tx=new JdbcTransactions(ds);
    }
    static StaffBindingAdapter bindings(){
        var stamp=new SourceStamp("SYNTHETIC inventory course roles","inventory-v1","approved course configuration",Instant.now(),true);
        return new StaffBindingAdapter(()->List.of(
            new StaffBindingAdapter.Entry("SYNTHETIC manager",new StaffAuthorization.Grant(stamp.version(),Set.of("CLINIC_MANAGER"),Set.of("UC13","UC16"),Set.of(81001L),false),stamp),
            new StaffBindingAdapter.Entry("SYNTHETIC procurement",new StaffAuthorization.Grant(stamp.version(),Set.of("PROCUREMENT_STAFF"),Set.of("UC14","UC15"),Set.of(81001L),false),stamp)));
    }
    static long next(Connection c,String sequence)throws SQLException{
        if(c.getAutoCommit())throw new SQLException("INVENTORY_ID_REQUIRES_TRANSACTION","25000");
        if(!Set.of("course_uc_audit_id_seq","course_purchase_requirement_id_seq").contains(sequence))throw new IllegalArgumentException("INVALID_INVENTORY_SEQUENCE");
        try(var p=c.prepareStatement("SELECT nextval('adc."+sequence+"'::regclass)");var r=p.executeQuery()){if(r.next()){long id=r.getLong(1);if(id>0&&!r.next())return id;}}
        throw new SQLException("INVENTORY_ID_UNAVAILABLE");
    }
    static long audit(Connection c)throws SQLException{return next(c,"course_uc_audit_id_seq");}
    @Bean StockInquiryController.Access courseStock(){return new StockInquiryController.StaffAccess(tx,bindings(),CourseInventoryConfiguration::audit,Clock.systemUTC());}
    @Bean PurchaseRequirementController.Access coursePurchase(){return new PurchaseRequirementController.StaffAccess(tx,bindings(),c->next(c,"course_purchase_requirement_id_seq"),
        (c,ref)->CONFIRM.equals(ref)?Optional.of(new PurchaseRequirementService.Confirmation(81001,9000001,new BigDecimal("6"),ref,"SYNTHETIC manager","SYNTHETIC course demand approved v1")):Optional.empty(),CourseInventoryConfiguration::audit,Clock.systemUTC());}
    @Bean SupplyReceiptController.Access courseReceipt(){return new SupplyReceiptController.StaffAccess(tx,bindings(),(c,ref)->{
        if(!DELIVERY.equals(ref))return Optional.empty();
        try(var p=c.prepareStatement("SELECT purchase_requirement_id FROM adc.purchase_requirement WHERE clinic_id=81001 AND supply_id=9000001 AND requirement_confirmation::jsonb->>'reference'=? ORDER BY purchase_requirement_id LIMIT 2")){
            p.setString(1,CONFIRM);try(var r=p.executeQuery()){if(!r.next())return Optional.empty();long id=r.getLong(1);if(r.next())return Optional.empty();return Optional.of(new SupplyReceiptService.Delivery(sources.receipt(),81001,id,ref,null));}
        }
    },CourseInventoryConfiguration::audit,Clock.systemUTC());}
    @Bean SupplyConsumptionController.Access courseConsumption(){return new SupplyConsumptionController.StaffAccess(tx,bindings(),(c,ref)->{
        if(costV2&&ref.startsWith("SYNTHETIC-COST-v2-")){
            try(var p=c.prepareStatement("SELECT fact_id,quantity,occurred_at,verified_cost,verification_basis,verified_by FROM adc_course_source.consumption_cost_fact WHERE clinic_id=81001 AND supply_id=9000001 AND source_reference=?")){
                p.setString(1,ref);try(var r=p.executeQuery()){if(!r.next())return Optional.empty();return Optional.of(new SupplyConsumptionService.Evidence(r.getLong(1),new SupplyConsumptionService.Target(81001,9000001),r.getBigDecimal(2),r.getObject(3,OffsetDateTime.class).toInstant(),r.getBigDecimal(4),"SYNTHETIC consumption v2; "+r.getString(5),r.getString(6),ref));}
            }
        }
        return COST.equals(ref)?Optional.of(new SupplyConsumptionService.Evidence(sources.consumption(),new SupplyConsumptionService.Target(81001,9000001),new BigDecimal("2"),sources.occurredAt(),new BigDecimal("4.00"),"SYNTHETIC fixed verified course cost v1","SYNTHETIC manager",ref)):Optional.empty();},CourseInventoryConfiguration::audit,Clock.systemUTC());}
}
