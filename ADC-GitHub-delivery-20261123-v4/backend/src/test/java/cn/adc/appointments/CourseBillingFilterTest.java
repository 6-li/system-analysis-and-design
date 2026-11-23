package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import cn.adc.shared.config.SourceStamp;
import java.sql.*;import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class CourseBillingFilterTest{
 @Test void httpEmptyAllIsDistinctFromMissingDefaultPending()throws Exception{
  var env=new MockEnvironment().withProperty("adc.course.clinic-id","81001").withProperty("adc.course.database","adc_course_validation").withProperty("adc.course.database-port","55432").withProperty("ADC_COURSE_DB_BILLING_PASSWORD","SYNTHETIC test only");
  var controller=new CourseBillingDirectory(env);var source=mock(javax.sql.DataSource.class);var tx=new JdbcTransactions(source);var c=mock(Connection.class);var p=mock(PreparedStatement.class);var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(p);when(p.executeQuery()).thenReturn(r);when(r.next()).thenReturn(false);
  when(source.getConnection()).thenReturn(c);when(c.createStatement()).thenReturn(mock(Statement.class));ReflectionTestUtils.setField(controller,"tx",tx);
  var session=new MockHttpSession();session.setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,new StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC billing","TEST csrf",new SourceStamp("SYNTHETIC","v1","TEST",Instant.now(),true)));
  var mvc=MockMvcBuilders.standaloneSetup(controller).build();
  mvc.perform(get("/api/v1/staff/billing/invoices").param("status","").session(session)).andExpect(status().isOk());verify(p).setString(3,"");
  clearInvocations(p);mvc.perform(get("/api/v1/staff/billing/invoices").session(session)).andExpect(status().isOk());verify(p).setString(3,"PENDING");
  clearInvocations(p);mvc.perform(get("/api/v1/staff/billing/invoices").param("status","已结算").session(session)).andExpect(status().isOk());verify(p).setString(3,"已结算");
  mvc.perform(get("/api/v1/staff/billing/invoices").param("status","UNKNOWN").session(session)).andExpect(status().isBadRequest());
 }
}
