package cn.adc.shared.resource;
import cn.adc.PgITSupport;
import cn.adc.shared.db.JdbcTransactions;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="ADC_P0_IT_ENABLED",matches="true")
class ResourceLockIT {
 @Test void catalogPublicationExclusiveWaitsForSharedReader()throws Exception {
  try(var a=PgITSupport.app().getConnection();var b=PgITSupport.app().getConnection();var o=PgITSupport.app().getConnection();var pool=Executors.newVirtualThreadPerTaskExecutor()){
   a.setAutoCommit(false);b.setAutoCommit(false);new ResourceLockCoordinator().scope(a).catalog(false);int pid;try(var s=b.createStatement();var r=s.executeQuery("SELECT pg_backend_pid()")){r.next();pid=r.getInt(1);}var future=pool.submit(()->{new ResourceLockCoordinator().scope(b).catalog(true);return true;});blockUntil(o,pid);a.commit();assertTrue(future.get(5,TimeUnit.SECONDS));b.rollback();
  }
 }
 @Test void forUpdateNeedsColumnPrivilegeAndFailsClosedWithoutIt()throws Exception {
  try(var owner=PgITSupport.owner().getConnection();var s=owner.createStatement()){
   s.execute("REVOKE UPDATE(dentist_id) ON adc.dentist FROM p0_probe_app");try{var e=assertThrows(JdbcTransactions.Failure.class,()->new JdbcTransactions(PgITSupport.app()).execute(c->{new ResourceLockCoordinator().scope(c).dentists(List.of(1L));return null;}));assertEquals("42501",e.sqlState());assertFalse(e.unknown());}finally{s.execute("GRANT UPDATE(dentist_id) ON adc.dentist TO p0_probe_app");}
  }
 }
 private void blockUntil(Connection observer,int pid)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<end){try(var p=observer.prepareStatement("SELECT cardinality(pg_blocking_pids(?))>0")){p.setInt(1,pid);try(var r=p.executeQuery()){r.next();if(r.getBoolean(1))return;}}Thread.sleep(10);}fail("No actual blocking state observed");}
 @Test void crossClinicDentistLockWaitsAndNewStatementReadsCommittedChange()throws Exception {
  try(var a=PgITSupport.app().getConnection();var b=PgITSupport.app().getConnection();var observer=PgITSupport.app().getConnection();var executor=Executors.newVirtualThreadPerTaskExecutor()){
   a.setAutoCommit(false);b.setAutoCommit(false);var ca=new ResourceLockCoordinator().scope(a);ca.command("synthetic-a");ca.schedules(List.of(9000L));ca.dentists(List.of(1L));
   int pid;try(var s=b.createStatement();var r=s.executeQuery("SELECT pg_backend_pid()")){r.next();pid=r.getInt(1);}
   var future=executor.submit(()->{var cb=new ResourceLockCoordinator().scope(b);cb.command("synthetic-b");cb.schedules(List.of(9001L));cb.dentists(List.of(1L));try(var s=b.createStatement();var r=s.executeQuery("SELECT conflict_check_result FROM adc.reception_schedule_version WHERE schedule_id=9000 AND schedule_version_no=1")){r.next();return r.getString(1);}});
   blockUntil(observer,pid);
   try(var st=a.createStatement()){st.execute("UPDATE adc.reception_schedule_version SET conflict_check_result='SYNTHETIC-after-A' WHERE schedule_id=9000 AND schedule_version_no=1");}
   a.commit();assertEquals("SYNTHETIC-after-A",future.get(5,TimeUnit.SECONDS));b.rollback();
  }
 }
 @Test void missingResourceRollsBackInsteadOfLockingPhantomId(){var tx=new JdbcTransactions(PgITSupport.app());var failure=assertThrows(JdbcTransactions.Failure.class,()->tx.execute(c->{new ResourceLockCoordinator().scope(c).dentists(List.of(-999L));return null;}));assertEquals("P0002",failure.sqlState());assertFalse(failure.unknown());}
 @Test void realLockTimeoutIsKnownRollbackNotCommitUnknown()throws Exception {
  try(var a=PgITSupport.app().getConnection()){a.setAutoCommit(false);new ResourceLockCoordinator().scope(a).dentists(List.of(1L));var tx=new JdbcTransactions(PgITSupport.app());var e=assertThrows(JdbcTransactions.Failure.class,()->tx.execute(c->{try(var st=c.createStatement()){st.execute("SET LOCAL lock_timeout='80ms'");}new ResourceLockCoordinator().scope(c).dentists(List.of(1L));return null;}));assertEquals("55P03",e.sqlState());assertFalse(e.unknown());a.rollback();}
 }
 @Test void implicitForeignKeyWaitAndDeadlockUseActualJdbcConnections()throws Exception {
  try(var a=PgITSupport.app().getConnection();var b=PgITSupport.owner().getConnection();var observer=PgITSupport.app().getConnection();var executor=Executors.newVirtualThreadPerTaskExecutor()){
   a.setAutoCommit(false);b.setAutoCommit(false);try(var s=a.createStatement()){s.execute("SELECT dentist_id FROM adc.dentist WHERE dentist_id=1 FOR UPDATE");}try(var s=b.createStatement()){s.execute("SELECT clinic_id FROM adc.clinic WHERE clinic_id=1 FOR UPDATE");}
   int pid;try(var s=b.createStatement();var r=s.executeQuery("SELECT pg_backend_pid()")){r.next();pid=r.getInt(1);}
   var waiting=executor.submit(()->{try(var s=b.createStatement()){s.execute("SELECT dentist_id FROM adc.dentist WHERE dentist_id=1 FOR UPDATE");b.rollback();return "NONE";}catch(SQLException e){b.rollback();return e.getSQLState();}});blockUntil(observer,pid);
   String stateA="NONE";try(var s=a.createStatement()){s.execute("INSERT INTO adc.reception_schedule_version(schedule_id,schedule_version_no,clinic_id,dentist_id,starts_at,ends_at,activation_status,qualification_check_result,conflict_check_result) VALUES(999001,1,1,1,'2026-11-06T00:00:00Z','2026-11-06T01:00:00Z','DRAFT','synthetic','synthetic')");}catch(SQLException e){stateA=e.getSQLState();}finally{a.rollback();}
   String stateB=waiting.get(5,TimeUnit.SECONDS);assertTrue(stateA.equals("40P01")||stateB.equals("40P01"));assertEquals(0,PgITSupport.count("SELECT count(*) FROM adc.reception_schedule_version WHERE schedule_id=999001"));
  }
 }
}
