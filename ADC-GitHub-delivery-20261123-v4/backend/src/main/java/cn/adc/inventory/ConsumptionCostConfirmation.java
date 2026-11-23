package cn.adc.inventory;

import cn.adc.shared.audit.*;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.*;
import cn.adc.shared.time.StrictInputPolicy;
import java.sql.*;
import java.time.*;
import java.math.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static cn.adc.shared.audit.AuditDocuments.JSON;

/** Explicit manager-confirmed course cost fact; confirmation does not consume stock. */
final class ConsumptionCostConfirmation implements CommandRecovery.SuccessVerifier {
 static final String OP="CONSUMPTION_COST_CONFIRM";
 record Input(long clinic,long supply,BigDecimal quantity,Instant occurredAt,String basis){}
 private final StaffAuthorization auth;
 private final Clock clock;
 private final CommandRecovery commands;
 ConsumptionCostConfirmation(JdbcTransactions tx,StaffAuthorization auth,Clock clock){this.auth=auth;this.clock=clock;commands=new UcAuditService(tx,auth,this,clock,CourseInventoryConfiguration::audit).commands();}
 static BigDecimal cost(Input i,Clock clock){
  if(i.clinic()!=81001||i.supply()!=9000001||i.quantity()==null||i.quantity().signum()<=0||i.quantity().scale()>3||i.quantity().precision()-i.quantity().scale()>15||i.occurredAt()==null||i.occurredAt().isAfter(clock.instant())||i.occurredAt().getNano()%1000!=0||i.basis()==null||i.basis().isBlank()||i.basis().length()>4000)throw new IllegalArgumentException("INVALID_COST_CONFIRMATION");
  var value=i.quantity().multiply(new BigDecimal("2.00")).setScale(2,RoundingMode.HALF_UP);
  if(value.precision()>18)throw new IllegalArgumentException("COST_AMOUNT_OUT_OF_RANGE");return value;
 }
 CommandRecovery.Receipt confirm(Input input,UUID key){
  var total=cost(input,clock);
  var payload=JSON.createObjectNode().put("clinicId",input.clinic()).put("supplyId",input.supply()).put("quantity",input.quantity().stripTrailingZeros().toPlainString()).put("occurredAt",input.occurredAt().toString()).put("basis",input.basis()).put("policy","SYNTHETIC consumption v2").put("unitCost","2.00");
  return commands.execute(new CommandRecovery.Command(OP,"UC16",key,payload,Set.of(input.clinic())),(c,locks)->{
   String actor=auth.require("UC16",Set.of(input.clinic())).actor();
   long id;
   try(var p=c.prepareStatement("SELECT nextval('adc.course_supply_consumption_id_seq')");var r=p.executeQuery()){if(!r.next())throw new SQLException("COST_ID_UNAVAILABLE");id=r.getLong(1);}
   String ref="SYNTHETIC-COST-v2-"+id;var at=StrictInputPolicy.eventTime(clock);
   try(var p=c.prepareStatement("INSERT INTO adc_course_source.consumption_cost_fact(fact_id,clinic_id,supply_id,quantity,occurred_at,unit_cost,verified_cost,verification_basis,verified_by,verified_at,request_key,source_reference) VALUES(?,?,?,?,?,2.00,?,?,?,?,?,?)")){
    p.setLong(1,id);p.setLong(2,input.clinic());p.setLong(3,input.supply());p.setBigDecimal(4,input.quantity());p.setObject(5,input.occurredAt().atOffset(ZoneOffset.UTC));p.setBigDecimal(6,total);p.setString(7,input.basis());p.setString(8,actor);p.setObject(9,at.atOffset(ZoneOffset.UTC));p.setObject(10,key);p.setString(11,ref);if(p.executeUpdate()!=1)throw new SQLException("COST_FACT_UNCONFIRMED");
   }
   var result=publicFact(id,input.clinic(),input.supply(),input.quantity(),input.occurredAt(),total,ref);
   var proof=JSON.createObjectNode().put("factHash",AuditDocuments.hash(result)).put("policy","SYNTHETIC consumption v2");
   return new AuditDocuments.Change(JSON.createObjectNode().put("costFactId",Long.toString(id)),null,result,proof);
  });
 }
 static tools.jackson.databind.node.ObjectNode publicFact(long id,long clinic,long supply,BigDecimal quantity,Instant at,BigDecimal cost,String ref){
  return JSON.createObjectNode().put("costFactId",Long.toString(id)).put("clinicId",Long.toString(clinic)).put("supplyId",Long.toString(supply)).put("quantity",quantity.stripTrailingZeros().toPlainString()).put("occurredAt",at.toString()).put("unitCost","2.00").put("cost",cost.toPlainString()).put("sourceReference",ref).put("synthetic",true);
 }
 @Override public boolean matchesCommitted(Connection c,CommandRecovery.Command command,JsonNode saved)throws SQLException{
  if(!OP.equals(command.operation())||!"UC16".equals(command.ucCode())||!command.clinics().equals(Set.of(81001L)))return false;
  String actor=auth.require("UC16",command.clinics()).actor();
  try(var p=c.prepareStatement("SELECT fact_id,clinic_id,supply_id,quantity,occurred_at,verified_cost,source_reference,verified_by,verification_basis FROM adc_course_source.consumption_cost_fact WHERE clinic_id=81001 AND request_key=?")){
   p.setObject(1,command.clientKey());try(var r=p.executeQuery()){
    if(!r.next()||!actor.equals(r.getString(8))||!command.payload().path("basis").asText().equals(r.getString(9)))return false;
    var fact=publicFact(r.getLong(1),r.getLong(2),r.getLong(3),r.getBigDecimal(4),r.getObject(5,OffsetDateTime.class).toInstant(),r.getBigDecimal(6),r.getString(7));
    return fact.equals(saved.path("after"))&&AuditDocuments.hash(fact).equals(saved.path("verification").path("factHash").asText());
   }
  }
 }
 @Override public boolean hasContradictorySuccess(Connection c,CommandRecovery.Command command,String reference){return true;}
}
