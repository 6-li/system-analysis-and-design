package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ExistingPatientLinkTest {
    private record Fixture(Connection c,PreparedStatement source,ResultSet sourceRows,PreparedStatement candidate,ResultSet candidateRows){}
    private Fixture fixture()throws Exception{
        var c=mock(Connection.class);var source=mock(PreparedStatement.class);var sr=mock(ResultSet.class);
        var candidate=mock(PreparedStatement.class);var cr=mock(ResultSet.class);
        when(c.prepareStatement(contains("FROM adc_mini.preliminary_request"))).thenReturn(source);
        when(c.prepareStatement(startsWith("SELECT patient_id,patient_name"))).thenReturn(candidate);
        when(source.executeQuery()).thenReturn(sr);when(sr.next()).thenReturn(true);when(sr.getString(1)).thenReturn(PatientAccounts.principal(UUID.randomUUID()));when(sr.getString(2)).thenReturn("li");
        when(candidate.executeQuery()).thenReturn(cr);when(cr.next()).thenReturn(true,false);when(cr.getLong(1)).thenReturn(71004L);when(cr.getString(2)).thenReturn("TEST-patient");when(cr.getString(3)).thenReturn("TEST-contact");
        return new Fixture(c,source,sr,candidate,cr);
    }
    @Test void previewUsesOriginalIdentityAndMasksContactWithoutWriting()throws Exception{
        var f=fixture();var result=new ExistingPatientLink().lookup(f.c,UUID.randomUUID(),0,81001);
        assertEquals("71004",result.path("patientId").asText());assertEquals("T***",result.path("contactHint").asText());
        verify(f.candidate).setString(1,"li");verify(f.source,never()).executeUpdate();verify(f.candidate,never()).executeUpdate();
    }
    @Test void staleAndAmbiguousSourceAreDeniedWithoutBinding()throws Exception{
        var f=fixture();assertThrows(IllegalArgumentException.class,()->new ExistingPatientLink().lookup(f.c,UUID.randomUUID(),1,81001));
        verify(f.candidate,never()).executeQuery();
        when(f.candidateRows.next()).thenReturn(true,true);
        assertThrows(IllegalArgumentException.class,()->new ExistingPatientLink().lookup(f.c,UUID.randomUUID(),0,81001));
    }
    @Test void differentPatientOrMissingVerificationCannotLink()throws Exception{
        var f=fixture();var service=new ExistingPatientLink();
        assertThrows(SecurityException.class,()->service.link(f.c,UUID.randomUUID(),0,81001,71005,"SYNTHETIC reception","TEST-verified"));
        assertThrows(SecurityException.class,()->service.link(f.c,UUID.randomUUID(),0,81001,71004,"SYNTHETIC dentist","TEST-verified"));
        assertThrows(SecurityException.class,()->service.link(f.c,UUID.randomUUID(),0,81001,71004,"SYNTHETIC reception"," "));
        verify(f.c,never()).prepareStatement(startsWith("INSERT"));
    }
    @Test void failedAssociationRollsBackBindingInsert()throws Exception{
        var f=fixture();var source=mock(javax.sql.DataSource.class);when(source.getConnection()).thenReturn(f.c);
        when(f.c.createStatement()).thenReturn(mock(Statement.class));
        var insert=mock(PreparedStatement.class);when(insert.executeUpdate()).thenReturn(1);
        when(f.c.prepareStatement(startsWith("INSERT INTO adc_mini.patient_account_binding"))).thenReturn(insert);
        var update=mock(PreparedStatement.class);when(update.executeUpdate()).thenThrow(new SQLException("TEST failure","40001"));
        when(f.c.prepareStatement(startsWith("UPDATE adc_mini.preliminary_request"))).thenReturn(update);
        var tx=new cn.adc.shared.db.JdbcTransactions(source);
        assertThrows(cn.adc.shared.db.JdbcTransactions.Failure.class,()->tx.execute(c->new ExistingPatientLink().link(c,UUID.randomUUID(),0,81001,71004,"SYNTHETIC reception","TEST-verified")));
        verify(insert).executeUpdate();verify(f.c).rollback();verify(f.c,never()).commit();
    }
}
