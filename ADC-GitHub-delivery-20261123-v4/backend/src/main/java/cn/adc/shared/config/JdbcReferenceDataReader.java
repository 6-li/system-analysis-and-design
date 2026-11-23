package cn.adc.shared.config;
import javax.sql.DataSource;import java.sql.*;import java.util.*;
/** Only SELECT on original24/25; no foundation-data creation or modification API. */
public final class JdbcReferenceDataReader implements ReferenceDataReader {
 private final DataSource source;public JdbcReferenceDataReader(DataSource source){this.source=Objects.requireNonNull(source);}
 public Optional<Clinic> clinic(long id){try(var c=source.getConnection()){c.setReadOnly(true);try(var p=c.prepareStatement("SELECT clinic_id,clinic_name FROM adc.clinic WHERE clinic_id=?")){p.setLong(1,id);try(var r=p.executeQuery()){return r.next()?Optional.of(new Clinic(r.getLong(1),r.getString(2))):Optional.empty();}}}catch(SQLException e){throw new ConfigurationUnavailable();}}
 public Optional<Dentist> dentist(long id){try(var c=source.getConnection()){c.setReadOnly(true);try(var p=c.prepareStatement("SELECT dentist_id,practice_qualification FROM adc.dentist WHERE dentist_id=?")){p.setLong(1,id);try(var r=p.executeQuery()){return r.next()?Optional.of(new Dentist(r.getLong(1),r.getString(2))):Optional.empty();}}}catch(SQLException e){throw new ConfigurationUnavailable();}}
}
