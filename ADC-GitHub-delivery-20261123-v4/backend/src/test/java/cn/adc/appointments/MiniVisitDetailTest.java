package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.OffsetDateTime;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class MiniVisitDetailTest {
 @Test void unrelatedVisitDenied()throws Exception{
  var c=mock(Connection.class);var p=mock(PreparedStatement.class);var r=mock(ResultSet.class);
  when(c.prepareStatement(anyString())).thenReturn(p);when(p.executeQuery()).thenReturn(r);when(r.next()).thenReturn(false);
  assertThrows(SecurityException.class,()->MiniVisitDetail.read(c,71,99));verify(p).setLong(1,99);verify(p).setLong(2,71);
 }
 @Test void missingInvoiceIsExplicitWithoutInternalFields()throws Exception{
  var c=mock(Connection.class);var p=mock(PreparedStatement.class);var r=mock(ResultSet.class);
  var projects=mock(PreparedStatement.class);var empty=mock(ResultSet.class);var bill=mock(PreparedStatement.class);
  when(c.prepareStatement(anyString())).thenReturn(p,projects,bill);when(p.executeQuery()).thenReturn(r);when(r.next()).thenReturn(true,false);
  when(r.getString(1)).thenReturn("81001");when(r.getString(2)).thenReturn("SYNTHETIC clinic");when(r.getString(3)).thenReturn("79001");when(r.getObject(4,OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-11-22T09:00:00+08:00"));
  when(projects.executeQuery()).thenReturn(empty);when(bill.executeQuery()).thenReturn(empty);when(empty.next()).thenReturn(false);
  var out=MiniVisitDetail.read(c,71,99);assertTrue(out.path("invoice").isNull());assertTrue(out.path("projects").isEmpty());assertEquals("79001",out.path("dentistId").asText());assertFalse(out.has("registeredBy"));
  verify(p,never()).executeUpdate();verify(projects,never()).executeUpdate();verify(bill,never()).executeUpdate();
 }
}
