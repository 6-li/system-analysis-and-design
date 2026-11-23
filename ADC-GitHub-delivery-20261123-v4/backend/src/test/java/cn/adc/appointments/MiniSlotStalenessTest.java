package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;import java.sql.*;import java.time.ZoneId;import java.util.Optional;
import cn.adc.shared.db.JdbcTransactions;import cn.adc.shared.audit.AuditDocuments;import cn.adc.shared.audit.CommandRecovery.Rejection;
import static org.mockito.Mockito.*;import static org.junit.jupiter.api.Assertions.*;
class MiniSlotStalenessTest {
 @Test void withdrawnCurrentProjectIsStaleRatherThanMalformedPatientInput()throws Exception {
  var ds=mock(DataSource.class);var c=mock(Connection.class);var p=mock(PreparedStatement.class);var r=mock(ResultSet.class);
  when(ds.getConnection()).thenReturn(c);when(c.createStatement()).thenReturn(mock(Statement.class));when(c.prepareStatement(anyString())).thenReturn(p);when(p.executeQuery()).thenReturn(r);when(r.next()).thenReturn(false);
  var sessions=new MiniRequestSession(code->Optional.empty(),new MiniRequestSession.Sessions(){public String issue(String a){throw new UnsupportedOperationException();}public Optional<String> activeAccount(String t){return Optional.of("SYNTHETIC-account");}});
  var directory=new MiniBookingOptionsController.Directory(){public ZoneId zone(long id){return ZoneId.of("Asia/Shanghai");}public boolean dentist(long c,long d){return true;}};
  var reader=new MiniBookingOptionsController(sessions,new JdbcTransactions(ds),directory);
  var input=AuditDocuments.parse("{\"intendedClinicId\":\"81001\",\"preferred\":{\"date\":\"2026-11-21\"},\"selectedScheme\":{\"durationBasis\":{\"primaryProjectId\":\"85001\"}}}");
  assertEquals("MINI_SELECTED_SLOT_UNAVAILABLE",assertThrows(Rejection.class,()->reader.requireAvailable("Bearer SYNTHETIC-token",input)).getMessage());
 }
}
