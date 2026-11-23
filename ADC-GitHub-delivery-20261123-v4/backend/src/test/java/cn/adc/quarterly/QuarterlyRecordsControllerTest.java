package cn.adc.quarterly;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Set;
import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class QuarterlyRecordsControllerTest {
 @Test void fourReadRoutesPreserveScopeVersionAndDisableCaching()throws Exception{
  var c=mock(java.sql.Connection.class);var ds=mock(javax.sql.DataSource.class);when(ds.getConnection()).thenReturn(c);when(c.createStatement()).thenReturn(mock(java.sql.Statement.class));
  when(c.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call->{
   String sql=call.getArgument(0);var stmt=mock(java.sql.PreparedStatement.class);var rows=mock(java.sql.ResultSet.class);when(stmt.executeQuery()).thenReturn(rows);
   if(sql.startsWith("SELECT clinic_id FROM")){when(rows.next()).thenReturn(true,true,false);when(rows.getLong(1)).thenReturn(81001L,81002L);}
   else if(sql.startsWith("SELECT quarter_code,aggregation_readiness,receivable_amount")){when(rows.next()).thenReturn(true,false);when(rows.getString(1)).thenReturn("2026-Q4");when(rows.getString(13)).thenReturn("{}");}
   else if(sql.startsWith("SELECT statistic_id,calculation_basis")){when(rows.next()).thenReturn(true,false);when(rows.getLong(1)).thenReturn(42L);when(rows.getString(2)).thenReturn("{}");}
   else when(rows.next()).thenReturn(false);
   return stmt;
  });
  var auth=QuarterlyRecordsServiceTest.auth("TEST operations","HQ_OPERATIONS",Set.of("UC17","UC19"),Set.of(81001L,81002L));
  var service=new QuarterlyRecordsService(new cn.adc.shared.db.JdbcTransactions(ds),auth);
  var mvc=MockMvcBuilders.standaloneSetup(new QuarterlyRecordsController((req,csrf)->{if(!csrf.equals("TEST"))throw new SecurityException("CSRF_INVALID");return service;})).build();
  for(String route:new String[]{"previews?quarter=2026-Q4","calculations?quarter=2026-Q4","previews/42?unused=1","calculations/77?version=1"})mvc.perform(get("/api/v1/staff/quarterly/records/"+route+"&clinics=81001,81002").header("X-CSRF-Token","TEST")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
  verify(ds,times(4)).getConnection();verify(c,times(4)).commit();
 }
 @Test void unauthorizedHistoryReadDoesNotInvokeService()throws Exception{
  var mvc=MockMvcBuilders.standaloneSetup(new QuarterlyRecordsController((req,csrf)->{throw new SecurityException("AUTHENTICATION_REQUIRED");})).build();
  mvc.perform(get("/api/v1/staff/quarterly/records/previews?quarter=2026-Q4&clinics=81001").header("X-CSRF-Token","TEST")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
 }
}
