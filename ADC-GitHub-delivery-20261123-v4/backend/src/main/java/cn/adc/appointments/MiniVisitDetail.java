package cn.adc.appointments;
import java.sql.*;
import cn.adc.shared.audit.AuditDocuments;
import tools.jackson.databind.node.ObjectNode;

/** Patient-safe projection; never includes internal notes, staff actors or payment references. */
final class MiniVisitDetail {
 static ObjectNode read(Connection c,long patient,long visit)throws SQLException{return read(c,patient,visit,false);}
 static ObjectNode read(Connection c,long patient,long visit,boolean notes)throws SQLException{
  var out=AuditDocuments.JSON.createObjectNode();
  try(var p=c.prepareStatement("""
    SELECT v.clinic_id,cl.clinic_name,v.actual_dentist_id,v.registered_at
    FROM adc.visit v JOIN adc.appointment a ON a.appointment_id=v.appointment_id
    JOIN adc.clinic cl ON cl.clinic_id=v.clinic_id
    WHERE v.visit_id=? AND a.patient_id=? AND a.appointment_status='已到诊'
    """)){
   p.setLong(1,visit);p.setLong(2,patient);try(var r=p.executeQuery()){
    if(!r.next())throw new SecurityException("MINI_VISIT_HISTORY_ACCESS_DENIED");
    out.put("visitId",Long.toString(visit)).put("clinicId",r.getString(1)).put("clinicName",r.getString(2)).put("dentistId",r.getString(3)).put("registeredAt",r.getObject(4,java.time.OffsetDateTime.class).toInstant().toString());
   }
  }
  var projects=out.putArray("projects");var clinicalNotes=out.putArray("clinicalNotes");
  try(var p=c.prepareStatement("""
    SELECT rv.performed_treatment,rv.billing_unit,rv.actual_quantity %s,rv.implementation_result
    FROM adc.clinical_record cr JOIN adc.clinical_record_version rv USING(clinical_record_id)
    WHERE cr.visit_id=? AND rv.is_current ORDER BY cr.clinical_record_id
    """.formatted(notes?",rv.patient_findings,rv.aftercare_instructions":",NULL::text,NULL::text"))){
   p.setLong(1,visit);try(var r=p.executeQuery()){while(r.next()){
    if("已实施".equals(r.getString(6)))projects.addObject().put("item",r.getString(1)).put("unit",r.getString(2)).put("quantity",r.getBigDecimal(3).stripTrailingZeros().toPlainString()).put("patientFindings",r.getString(4)).put("aftercareInstructions",r.getString(5));
    if(r.getString(4)!=null||r.getString(5)!=null)clinicalNotes.addObject().put("patientFindings",r.getString(4)).put("aftercareInstructions",r.getString(5));
   }}
  }

  long invoice=0;int version=0;ObjectNode bill=null;
  try(var p=c.prepareStatement("""
    SELECT i.invoice_id,i.invoice_status,v.invoice_version_no,v.total_amount
    FROM adc.treatment_invoice i JOIN adc.treatment_invoice_version v USING(invoice_id)
    WHERE i.visit_id=? AND v.is_current
    """)){
   p.setLong(1,visit);try(var r=p.executeQuery()){if(r.next()){
    invoice=r.getLong(1);version=r.getInt(3);bill=out.putObject("invoice").put("invoiceId",Long.toString(invoice)).put("status",r.getString(2)).put("version",version).put("total",r.getBigDecimal(4).toPlainString());
    if(r.next())throw new SQLException("INVOICE_CURRENT_VERSION_AMBIGUOUS","40001");
   }}
  }
  if(bill==null){out.putNull("invoice");return out;}
  var lines=bill.putArray("lines");
  try(var p=c.prepareStatement("SELECT billed_item,billing_unit,billed_quantity,unit_price,line_amount FROM adc.treatment_invoice_item WHERE invoice_id=? AND invoice_version_no=? ORDER BY invoice_item_id")){
   p.setLong(1,invoice);p.setInt(2,version);try(var r=p.executeQuery()){while(r.next())lines.addObject().put("item",r.getString(1)).put("unit",r.getString(2)).put("quantity",r.getBigDecimal(3).stripTrailingZeros().toPlainString()).put("unitPrice",r.getBigDecimal(4).toPlainString()).put("amount",r.getBigDecimal(5).toPlainString());}
  }
  var payments=bill.putArray("payments");
  try(var p=c.prepareStatement("SELECT payment_method,requested_amount,received_amount,local_settlement_status,received_at FROM adc.payment_transaction WHERE invoice_id=? AND invoice_version_no=? ORDER BY payment_id")){
   p.setLong(1,invoice);p.setInt(2,version);try(var r=p.executeQuery()){while(r.next()){
    var pay=payments.addObject().put("method",r.getString(1)).put("requestedAmount",r.getBigDecimal(2).toPlainString()).put("status",r.getString(4));
    if(r.getBigDecimal(3)==null)pay.putNull("receivedAmount");else pay.put("receivedAmount",r.getBigDecimal(3).toPlainString());
    var at=r.getObject(5,java.time.OffsetDateTime.class);if(at==null)pay.putNull("receivedAt");else pay.put("receivedAt",at.toInstant().toString());
    pay.put("synthetic",r.getString(1)!=null&&r.getString(1).contains("模拟"));
   }}
  }
  try(var p=c.prepareStatement("SELECT 1 FROM adc.treatment_invoice_version WHERE invoice_id=? AND invoice_version_no=? AND is_current")){
   p.setLong(1,invoice);p.setInt(2,version);try(var r=p.executeQuery()){if(!r.next())throw new SQLException("INVOICE_CHANGED_RETRY_READ","40001");}
  }
  return out;
 }
}
