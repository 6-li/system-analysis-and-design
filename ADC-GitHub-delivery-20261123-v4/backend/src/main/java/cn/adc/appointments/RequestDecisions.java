package cn.adc.appointments;
import java.sql.*;
import java.util.UUID;
import cn.adc.shared.audit.CommandRecovery.Rejection;
/** Independent rejection fact. Existing successful UC audit remains the authority for approval. */
final class RequestDecisions {
    static boolean ready(Connection c)throws SQLException{
        try(var q=c.prepareStatement("SELECT to_regclass('adc_mini.request_decision') IS NOT NULL");var r=q.executeQuery()){return r.next()&&r.getBoolean(1);}
    }
    static String reason(Connection c,UUID request)throws SQLException{
        if(!ready(c))return null; // compatibility for pre-batch isolated fixtures only; batch startup requires table.
        try(var q=c.prepareStatement("SELECT reason FROM adc_mini.request_decision WHERE request_id=? AND decision_status='REJECTED'")){
            q.setObject(1,request);try(var r=q.executeQuery()){return r.next()?r.getString(1):null;}
        }
    }
    static void requireOpen(Connection c,UUID request)throws SQLException{
        if(reason(c,request)!=null)throw new Rejection("REQUEST_ALREADY_REJECTED");
    }
}
