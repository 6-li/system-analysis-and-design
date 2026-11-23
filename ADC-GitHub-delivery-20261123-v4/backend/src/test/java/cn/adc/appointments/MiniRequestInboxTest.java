package cn.adc.appointments;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.contains;

class MiniRequestInboxTest {
    private static final UUID REQUEST=UUID.fromString("00000000-0000-4000-8000-000000000101");
    private final DataSource source=mock(DataSource.class);
    private final Connection c=mock(Connection.class);
    private final PreparedStatement query=mock(PreparedStatement.class),audit=mock(PreparedStatement.class);
    private final ResultSet rows=mock(ResultSet.class),events=mock(ResultSet.class);
    private MiniRequestInboxService service(java.util.function.Supplier<Map<String,StaffAuthorization.Grant>> grants)throws Exception{
        when(source.getConnection()).thenReturn(c);when(c.createStatement()).thenReturn(mock(Statement.class));
        when(c.prepareStatement(contains("SELECT r.request_id"))).thenReturn(query);
        when(c.prepareStatement(contains("SELECT uc.evidence"))).thenReturn(audit);
        when(query.executeQuery()).thenReturn(rows);when(audit.executeQuery()).thenReturn(events);
        when(rows.next()).thenReturn(true,false);when(rows.getObject(1,UUID.class)).thenReturn(REQUEST);
        when(rows.getInt(2)).thenReturn(0);when(rows.getObject(3,OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-11-19T01:00:00Z"));
        when(rows.getString(5)).thenReturn("""
          {"type":"NEW","intendedClinicId":"81001","identity":{"applicantInformation":"SYNTHETIC patient","contactInformation":"SYNTHETIC contact","patientIdentification":"SYNTHETIC SECRET ID"},"preferred":{"date":"2026-11-20"}}
          """);
        return new MiniRequestInboxService(new JdbcTransactions(source),new StaffAuthorization(()->Optional.of("SYNTHETIC reception"),grants::get),81001);
    }
    @Test void bookingPreviewUsesTheSelectedVersionAndReturnsOnlyTheDemand()throws Exception{
        var service=service(()->Map.of("SYNTHETIC reception",new StaffAuthorization.Grant("SYNTHETIC",Set.of("BOOKING_RECEPTION"),Set.of("UC02"),Set.of(81001L),false)));
        when(rows.getInt(2)).thenReturn(1);when(rows.getObject(4,Long.class)).thenReturn(71004L);
        var preview=mock(PreparedStatement.class);var sourceRow=mock(ResultSet.class);
        when(c.prepareStatement(contains("SELECT submitted_content::text"))).thenReturn(preview);when(preview.executeQuery()).thenReturn(sourceRow);when(sourceRow.next()).thenReturn(true,false);
        when(sourceRow.getString(1)).thenReturn("{\"type\":\"NEW\",\"preferred\":{\"date\":\"2026-11-21\"},\"treatmentNeed\":\"SYNTHETIC selected treatment\",\"identity\":{\"contactInformation\":\"SYNTHETIC SECRET CONTACT\"}}");
        var result=service.bookingPreview(REQUEST.toString(),"1");assertEquals("2026-11-21",result.path("preferredDate").asText());assertEquals("SYNTHETIC selected treatment",result.path("treatmentNeed").asText());assertFalse(result.toString().contains("SECRET CONTACT"));verify(preview).setLong(3,71004);verify(preview).setString(4,"81001");
    }
    private Map<String,StaffAuthorization.Grant> grant(){return Map.of("SYNTHETIC reception",
      new StaffAuthorization.Grant("SYNTHETIC",Set.of("BOOKING_RECEPTION"),Set.of("UC01"),Set.of(81001L),false));}
    @Test void listsPendingRequestsWithMaskedSummariesAndNoInventedPatientAcceptance()throws Exception{
        var result=service(this::grant).list(null);
        assertEquals(REQUEST.toString(),result.path("items").get(0).path("requestId").asText());
        assertEquals(0,result.path("items").get(0).path("requestVersion").asInt());
        assertEquals("S***",result.path("items").get(0).path("applicantSummary").asText());
        assertFalse(result.toString().contains("SECRET ID"));assertFalse(result.toString().contains("SYNTHETIC contact"));
        verify(query).setString(1,"81001");verify(c).commit();
    }
    @Test void roleWithoutReceptionScopeNeverOpensDatabase()throws Exception{
        var service=service(Map::of);
        assertThrows(SecurityException.class,()->service.list(null));verify(source,never()).getConnection();
    }
    @Test void revocationDuringTheReadRollsBackInsteadOfReturningPatientSummary()throws Exception{
        var active=new java.util.concurrent.atomic.AtomicBoolean(true);
        var service=service(()->active.get()?grant():Map.of());
        when(query.executeQuery()).thenAnswer(call->{active.set(false);return rows;});
        assertThrows(SecurityException.class,()->service.list(null));verify(c).rollback();verify(c,never()).commit();
    }
    @Test void committedAuditAndRealAppointmentRemoveTheRequestWithoutDependingOnCurrentBookingTimes()throws Exception{
        var service=service(this::grant);when(rows.getObject(4,Long.class)).thenReturn(88001L);
        var ref=AuditDocuments.JSON.createObjectNode().put("appointmentId",901);
        var doc=AuditDocuments.JSON.createObjectNode().put("schemaVersion",1).put("eventPhase","BUSINESS_RESULT").put("confirmedCommit","YES")
          .put("operationKind","APPOINTMENT_CREATE").put("canonicalPayloadHash","a".repeat(64));
        var snapshot=doc.putObject("businessResultSnapshot");snapshot.set("businessReference",ref);snapshot.putNull("before");snapshot.set("after",ref);
        snapshot.putObject("verification").put("SYNTHETIC","verified fixture");
        when(events.getString(4)).thenReturn("{\"decision\":\"ALLOW\",\"evaluatedAction\":\"UC02\",\"actualGrantedScope\":[{\"action\":\"UC02\",\"clinicIds\":[81001]}]}");
        when(events.next()).thenReturn(true,false);when(events.getString(1)).thenReturn(doc.toString());when(events.getString(2)).thenReturn(ref.toString());
        var fact=mock(PreparedStatement.class);var exists=mock(ResultSet.class);
        when(c.prepareStatement(contains("SELECT 1 FROM adc.appointment"))).thenReturn(fact);
        when(fact.executeQuery()).thenReturn(exists);when(exists.next()).thenReturn(true);
        assertEquals(0,service.list(null).path("items").size());verify(fact).setString(1,"901");
    }
}

