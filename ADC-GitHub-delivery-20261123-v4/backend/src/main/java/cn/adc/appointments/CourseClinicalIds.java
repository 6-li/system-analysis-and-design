package cn.adc.appointments;
import java.sql.*;
/** Sequence bindings confined to the explicitly installed course batch. */
final class CourseClinicalIds {
    static long visit(Connection c)throws SQLException{return next(c,"course_visit_id_seq");}
    static long health(Connection c)throws SQLException{return next(c,"course_health_change_id_seq");}
    static long consent(Connection c)throws SQLException{return next(c,"course_consent_id_seq");}
    static long record(Connection c)throws SQLException{return next(c,"course_clinical_record_id_seq");}
    static long invoice(Connection c)throws SQLException{return next(c,"course_invoice_id_seq");}
    static long item(Connection c)throws SQLException{return next(c,"course_invoice_item_id_seq");}
    static long payment(Connection c)throws SQLException{return next(c,"course_payment_id_seq");}
    private static long next(Connection c,String sequence)throws SQLException{
        if(c.getAutoCommit())throw new SQLException("COURSE_ID_REQUIRES_TRANSACTION","25000");
        try(var p=c.prepareStatement("SELECT nextval('adc."+sequence+"'::regclass)");var r=p.executeQuery()){
            if(r.next()){long id=r.getLong(1);if(id>0&&!r.next())return id;}
        }
        throw new SQLException("COURSE_ID_UNAVAILABLE","22003");
    }
}
