package cn.adc.appointments;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.config.*;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.duration.DurationPolicyService.Basis;
import org.junit.jupiter.api.Test;
import java.sql.*;import javax.sql.DataSource;import java.time.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class BookingProposalServiceTest {
 final Clock clock=Clock.fixed(Instant.parse("2026-11-20T01:00:00Z"),ZoneOffset.UTC);
 final SourceStamp stamp=new SourceStamp("SYNTHETIC source","1","SYNTHETIC verifier",clock.instant(),true);
 @Test void arbitraryDateUsesCurrentDurationAndClinicZoneWithoutFixedAcceptanceString()throws Exception{
  var scheme=propose("2026-12-04T10:10:00",81001,true,false);
  assertEquals(Instant.parse("2026-12-04T02:10:00Z"),scheme.startsAt());assertEquals(Instant.parse("2026-12-04T02:37:00Z"),scheme.endsAt());
  assertEquals(new Basis(85001,9,27),scheme.duration());
 }
 @Test void previewRejectsWrongClinicMissingCurrentAndCrossClinicDentistOccupancy()throws Exception{
  assertThrows(RuntimeException.class,()->propose("2026-12-04T10:10:00",81002,true,false));
  assertThrows(RuntimeException.class,()->propose("2026-12-04T10:10:00",81001,false,false));
  assertThrows(RuntimeException.class,()->propose("2026-12-04T10:10:00",81001,true,true));
 }
 @Test void confirmationIsExactRevocableAndScopedToTheStaffSession()throws Exception{
  var source=new SyntheticBookingConfirmations(clock);var scheme=propose("2026-12-04T10:10:00",81001,true,false);
  var ref=source.record("SYNTHETIC reception","SYNTHETIC applicant",scheme,stamp,true,null);
  assertTrue(source.forStaff("SYNTHETIC other").find(null,ref).isEmpty());
  var verifier=new BookingAcceptanceVerifier(source.forStaff("SYNTHETIC reception"),new BookingAcceptanceVerifier.ApplicantAuthority(){
   public boolean isPatient(Connection c,String s,long patient){return patient==71006;}
   public boolean mayAcceptBooking(Connection c,String s,InitialBookingReview.AcceptedScheme plan,String grant){return false;}
  },clock);
  assertTrue(verifier.matches(null,ref,scheme));
  var changed=new InitialBookingReview.AcceptedScheme(scheme.patientId(),scheme.schedule(),scheme.clinicId(),scheme.dentistId(),scheme.startsAt(),scheme.endsAt(),scheme.treatmentRequest(),new Basis(85001,10,27));
  assertFalse(verifier.matches(null,ref,changed));source.revoke("SYNTHETIC reception",ref);assertFalse(verifier.matches(null,ref,scheme));
 }
 @Test void absentExplicitConfirmationOrUnverifiedProxyNeverBecomesAcceptance()throws Exception{
  var source=new SyntheticBookingConfirmations(clock);var scheme=propose("2026-12-04T10:10:00",81001,true,false);
  assertThrows(SecurityException.class,()->source.record("SYNTHETIC reception","SYNTHETIC applicant",scheme,stamp,false,null));
  var ref=source.record("SYNTHETIC reception","SYNTHETIC proxy",scheme,stamp,true,"SYNTHETIC unverified grant");
  var verifier=new BookingAcceptanceVerifier(source.forStaff("SYNTHETIC reception"),new BookingAcceptanceVerifier.ApplicantAuthority(){public boolean isPatient(Connection c,String s,long p){return true;}public boolean mayAcceptBooking(Connection c,String s,InitialBookingReview.AcceptedScheme plan,String grant){return false;}},clock);
  assertFalse(verifier.matches(null,ref,scheme));
 }
 private InitialBookingReview.AcceptedScheme propose(String start,long scheduleClinic,boolean current,boolean occupied)throws Exception{
  var c=mock(Connection.class);var ds=mock(DataSource.class);when(ds.getConnection()).thenReturn(c);when(c.createStatement()).thenReturn(mock(Statement.class));
  when(c.prepareStatement(anyString())).thenAnswer(call->{String sql=call.getArgument(0);var p=mock(PreparedStatement.class);var r=mock(ResultSet.class);when(p.executeQuery()).thenReturn(r);
   if(sql.contains("FROM adc.patient"))when(r.next()).thenReturn(true,false);
   else if(sql.contains("project_duration_version")){when(r.next()).thenReturn(current,false);when(r.getString(1)).thenReturn("{\"project_id\":85001,\"duration_version_no\":9,\"duration_minutes\":27,\"publication_status\":\"CURRENT\",\"project_name\":\"SYNTHETIC project\"}");}
   else if(sql.contains("FROM adc.reception_schedule_version")){when(r.next()).thenReturn(true,false);when(r.getLong(1)).thenReturn(scheduleClinic);when(r.getLong(2)).thenReturn(79001L);when(r.getObject(3,OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-12-04T01:00:00Z"));when(r.getObject(4,OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-12-04T05:00:00Z"));when(r.getString(5)).thenReturn("EFFECTIVE");}
   else if(sql.contains("FROM adc.appointment a")){when(r.next()).thenReturn(occupied,false);when(r.getString(3)).thenReturn("待到诊");when(r.getObject(5,OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-12-04T03:00:00Z"));}
   else throw new AssertionError(sql);return p;});
  var auth=new StaffAuthorization(()->Optional.of("SYNTHETIC reception"),()->Map.of("SYNTHETIC reception",new StaffAuthorization.Grant("1",Set.of("BOOKING_RECEPTION"),Set.of("UC02"),Set.of(81001L),false)));
  var service=new BookingProposalService(new JdbcTransactions(ds),auth,id->new ClinicDirectoryProvider.Clinic(id,"SYNTHETIC clinic",ZoneId.of("Asia/Shanghai"),stamp),(dentist,clinic)->new DentistEligibilityProvider.Eligibility(dentist,clinic,"SYNTHETIC qualification",stamp),(connection,row)->false,clock);
  var result=service.propose(81001,71006,new Version(87001,2),85001,start,null,"SYNTHETIC treatment");
  verify(c,never()).prepareStatement(startsWith("INSERT"));return result;
 }
}
