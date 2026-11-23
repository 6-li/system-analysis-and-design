package cn.adc;
import cn.adc.shared.db.*;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="ADC_P0_IT_ENABLED",matches="true")
class P0SchemaContractIT {
 @Test void rootLockHasOnlyRequiredColumnPrivilege()throws Exception {try(var c=PgITSupport.app().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT has_column_privilege(current_user,'adc.appointment_resolution','resolution_id','UPDATE') AND NOT has_column_privilege(current_user,'adc.appointment_resolution','resolution_result','UPDATE')")){r.next();assertTrue(r.getBoolean(1));}}
 @Test void versionedMigrationsRollbackAtomicallyThenApplyWithoutNewHistoryTable() {
  var migrations=new VersionedMigrations();assertEquals(4,migrations.migrations().size());var tx=new JdbcTransactions(PgITSupport.migration());
  assertThrows(JdbcTransactions.Failure.class,()->tx.execute(c->{migrations.applyEmpty(c);throw new SQLException("synthetic post-DDL failure","23514");}));
  long before=tx.execute(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='adc' AND table_type='BASE TABLE'")){r.next();return r.getLong(1);}});assertEquals(0,before);
  tx.execute(c->{migrations.applyEmpty(c);return null;});
  var duplicate=assertThrows(JdbcTransactions.Failure.class,()->tx.execute(c->{migrations.applyEmpty(c);return null;}));assertFalse(duplicate.unknown());assertEquals("P0001",duplicate.sqlState());
 }
 @Test void applicationCannotCreateOrRewriteHistoryButCanLockDentist()throws Exception {
  try(var c=PgITSupport.app().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT NOT has_schema_privilege(current_user,'adc','CREATE') AND NOT has_table_privilege(current_user,'adc.extension_operation_audit_event','UPDATE') AND NOT has_table_privilege(current_user,'adc.operation_audit_event','DELETE') AND NOT has_table_privilege(current_user,'adc.appointment_resolution','UPDATE') AND has_column_privilege(current_user,'adc.dentist','dentist_id','UPDATE')")){assertTrue(r.next());assertTrue(r.getBoolean(1));}
 }
 @Test void approvedTableAndColumnCounts()throws Exception {assertEquals(12,PgITSupport.count("SELECT count(*) FROM information_schema.tables WHERE table_schema='adc' AND table_type='BASE TABLE'"));assertEquals(8,PgITSupport.count("SELECT count(*) FROM information_schema.columns WHERE table_schema='adc' AND table_name='project_duration_version'"));assertEquals(11,PgITSupport.count("SELECT count(*) FROM information_schema.columns WHERE table_schema='adc' AND table_name='extension_operation_audit_event'"));}
}
