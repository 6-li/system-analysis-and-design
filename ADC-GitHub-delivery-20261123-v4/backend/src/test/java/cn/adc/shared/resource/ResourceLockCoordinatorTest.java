package cn.adc.shared.resource;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ResourceLockCoordinatorTest {
 @Test void scheduleHashKeysAreDeduplicatedAndActuallySignedSorted(){var keys=ResourceLockCoordinator.scheduleKeys(List.of(1L,2L,1L,Long.MIN_VALUE));assertEquals(3,keys.size());assertEquals(keys.stream().sorted().toList(),keys);assertEquals(keys,ResourceLockCoordinator.scheduleKeys(List.of(Long.MIN_VALUE,2L,1L)));}
 @Test void emptyScopeCannotAcquireReverseStagesOrUpgrade(){Connection c=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->m.getName().equals("getAutoCommit")?false:null);var s=new ResourceLockCoordinator().scope(c);assertDoesNotThrow(()->s.schedules(List.of()));assertDoesNotThrow(()->s.dentists(List.of()));assertThrows(IllegalStateException.class,()->s.schedules(List.of()));assertThrows(IllegalStateException.class,()->s.catalog(false));}
 @Test void autoCommitCannotPretendToHoldTransactionLocks(){Connection c=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->m.getName().equals("getAutoCommit")?true:null);assertThrows(IllegalArgumentException.class,()->new ResourceLockCoordinator().scope(c));}
 @Test void firstDraftUsesOnlyCommandAndDentistAndCannotAddVersionLock()throws Exception {
  var statements=new ArrayList<String>();
  var scope=new ResourceLockCoordinator().scope(recordingConnection(statements));
  scope.command("synthetic-new");
  scope.uc21NewDraft(7);
  assertThrows(IllegalStateException.class,()->scope.schedules(List.of(8L)));
  assertThrows(IllegalStateException.class,()->scope.versions(List.of(new ResourceLockCoordinator.Version(8,1))));
  assertEquals(List.of("SELECT pg_advisory_xact_lock(41001,?)","SELECT adc_uc21_lock.lock_dentist(?)"),statements);
 }
 @Test void existingDraftLocksScheduleBothDentistsAndExactVersion()throws Exception {
  var statements=new ArrayList<String>();
  var scope=new ResourceLockCoordinator().scope(recordingConnection(statements));
  scope.command("synthetic-revision");
  scope.uc21ExistingDraft(List.of(9L,2L,9L),new ResourceLockCoordinator.Version(8,3));
  assertEquals(List.of("SELECT pg_advisory_xact_lock(41001,?)","SELECT pg_advisory_xact_lock(?)",
   "SELECT adc_uc21_lock.lock_dentist(?)","SELECT adc_uc21_lock.lock_dentist(?)",
   "SELECT adc_uc21_lock.lock_version(?,?)"),statements);
 }
 @Test void uc21CannotLockWithoutCommand() {
  var statements=new ArrayList<String>();
  var scope=new ResourceLockCoordinator().scope(recordingConnection(statements));
  assertThrows(IllegalStateException.class,()->scope.uc21NewDraft(7));
  assertTrue(statements.isEmpty());
 }
 private Connection recordingConnection(List<String> statements){
  return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
   case "getAutoCommit" -> false;
   case "prepareStatement" -> {
    statements.add((String)a[0]);
    yield Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(q,n,v)->n.getReturnType()==boolean.class?false:null);
   }
   default -> null;
  });
 }
}
