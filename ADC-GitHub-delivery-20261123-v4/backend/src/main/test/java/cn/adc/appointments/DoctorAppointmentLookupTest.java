package cn.adc.appointments;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import cn.adc.shared.config.SourceStamp;
import java.time.*;
import java.sql.*;
import java.lang.reflect.Proxy;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
class DoctorAppointmentLookupTest {
 private MockHttpServletRequest request(String role){var r=new MockHttpServletRequest();if(role!=null)r.getSession().setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,new StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC "+role,"TEST csrf",new SourceStamp("SYNTHETIC","v1","TEST",Instant.now(),true)));return r;}
 @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
 private static final class Fixture{
  String sql="";final Map<Integer,Long> parameters=new HashMap<>();int queries,connections,commits,rollbacks,cursor;boolean found,fail;Runnable afterRead=()->{};final CourseVisitDirectory controller;
  Fixture(boolean found){this.found=found;
   ResultSet rows=proxy(ResultSet.class,(o,m,a)->switch(m.getName()){
    case "next"->++cursor==1&&this.found;
    case "getString"->{int i=(Integer)a[0];if(i==5)afterRead.run();yield switch(i){case 1->"1000002";case 2->"100000";case 3->"100003";default->"TEST 项目";};}
    case "getObject"->OffsetDateTime.parse("2026-11-22T22:45:35+08:00");default->null;});
   PreparedStatement query=proxy(PreparedStatement.class,(o,m,a)->{switch(m.getName()){case "setLong":parameters.put((Integer)a[0],(Long)a[1]);return null;case "executeQuery":queries++;if(fail)throw new SQLException("TEST failure","08006");return rows;default:return null;}});
   Statement settings=proxy(Statement.class,(o,m,a)->m.getName().equals("execute")?true:null);
   Connection connection=proxy(Connection.class,(o,m,a)->{switch(m.getName()){case "createStatement":return settings;case "prepareStatement":sql=(String)a[0];return query;case "commit":commits++;return null;case "rollback":rollbacks++;return null;default:return null;}});
   DataSource ds=proxy(DataSource.class,(o,m,a)->{if(m.getName().equals("getConnection")){connections++;return connection;}return null;});
   controller=new CourseVisitDirectory(new JdbcTransactions(ds),new JdbcTransactions(ds),81001,79001);
  }
 }
 @Test void ownAppointmentUsesScopedReadAndPreservesBothIds(){
  var f=new Fixture(true);var response=f.controller.ownAppointment(request("dentist"),"100003");assertEquals(200,response.getStatusCode().value());var body=response.getBody();assertEquals("100003",body.path("appointmentId").asText());assertEquals("1000002",body.path("visitId").asText());assertEquals("100000",body.path("patientId").asText());assertEquals(Map.of(1,81001L,2,79001L,3,100003L),f.parameters);assertTrue(f.sql.startsWith("SELECT "));assertTrue(f.sql.contains("v.actual_dentist_id=?"));assertTrue(f.sql.contains("a.appointment_status='已到诊'"));assertTrue(f.sql.contains("v.appointment_id=?"));assertEquals(1,f.commits);
 }
 @Test void missingOrOutOfScopeRowsAreIndistinguishable(){var f=new Fixture(false);var response=f.controller.ownAppointment(request("dentist"),"100003");assertEquals(404,response.getStatusCode().value());assertEquals("VISIT_NOT_FOUND",response.getBody().path("code").asText());assertFalse(response.getBody().has("patientId"));}
 @Test void invalidIdentityAndIdDoNotQueryDatabase(){var f=new Fixture(true);assertThrows(SecurityException.class,()->f.controller.ownAppointment(request(null),"100003"));for(var role:new String[]{"reception","billing","manager"})assertThrows(SecurityException.class,()->f.controller.ownAppointment(request(role),"100003"));for(var id:new String[]{"0","01","-1","1 OR 1=1","9223372036854775808"})assertThrows(IllegalArgumentException.class,()->f.controller.ownAppointment(request("dentist"),id));assertEquals(0,f.connections);}
 @Test void sessionIsRecheckedAfterRead(){var f=new Fixture(true);var req=request("dentist");f.afterRead=()->req.getSession().invalidate();assertThrows(SecurityException.class,()->f.controller.ownAppointment(req,"100003"));assertEquals(1,f.rollbacks);assertEquals(0,f.commits);}
 @Test void jdbcFailureDoesNotLeakRows(){var f=new Fixture(true);f.fail=true;assertThrows(JdbcTransactions.Failure.class,()->f.controller.ownAppointment(request("dentist"),"100003"));assertEquals(1,f.rollbacks);}
 @Test void httpContractPreservesAuthenticationAndNoStore()throws Exception{
  var f=new Fixture(true);var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(f.controller).build();var get=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/staff/my-visits/by-appointment/100003");
  mvc.perform(get).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/staff/my-visits/by-appointment/100003").session((org.springframework.mock.web.MockHttpSession)request("billing").getSession())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/staff/my-visits/by-appointment/01").session((org.springframework.mock.web.MockHttpSession)request("dentist").getSession())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/staff/my-visits/by-appointment/100003").session((org.springframework.mock.web.MockHttpSession)request("dentist").getSession())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.visitId").value("1000002"));
 }
}
