package cn.adc.shared.resource;
import java.sql.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Shared seven-UC lock primitives. No business operation is implemented here. */
public final class ResourceLockCoordinator {
 private final boolean courseUc22Narrow;
 public ResourceLockCoordinator(){this(false);}
 public ResourceLockCoordinator(boolean courseUc22Narrow){this.courseUc22Narrow=courseUc22Narrow;}
 public record Version(long scheduleId,int versionNo) implements Comparable<Version>{public int compareTo(Version o){int n=Long.compare(scheduleId,o.scheduleId);return n==0?Integer.compare(versionNo,o.versionNo):n;}}
 public static long key(String value){try{return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).getLong();}catch(Exception e){throw new IllegalStateException("HASH_UNAVAILABLE");}}
 public static List<Long> scheduleKeys(Collection<Long> ids){return ids.stream().map(i->key("schedule:"+i)).distinct().sorted().toList();}
 public Scope scope(Connection c){try{if(c.getAutoCommit())throw new IllegalArgumentException("TRANSACTION_REQUIRED");return new Scope(c,courseUc22Narrow);}catch(SQLException e){throw new IllegalStateException("TRANSACTION_REQUIRED");}}
 public static final class Scope {
  private final Connection connection;private int stage;private String commandReference;private boolean initialUc21Draft;
  private final boolean courseUc22Narrow;
  private Scope(Connection c,boolean narrow){connection=c;courseUc22Narrow=narrow;}
  private void enter(int next){
   if(next<=stage||(initialUc21Draft&&(next==3||next==5)))throw new IllegalStateException("LOCK_ORDER_OR_UPGRADE_VIOLATION");
   stage=next;
  }
  public void command(String ref)throws SQLException {enter(1);try(var p=connection.prepareStatement("SELECT pg_advisory_xact_lock(41001,?)")){p.setInt(1,(int)key(ref));p.execute();commandReference=ref;}}
  public void requireCommand(Connection c,String ref){if(connection!=c||!Objects.equals(commandReference,ref))throw new IllegalStateException("COMMAND_LOCK_REQUIRED_BEFORE_RESOURCES");}
  public void catalog(boolean exclusive)throws SQLException {enter(2);try(var p=connection.prepareStatement(exclusive?"SELECT pg_advisory_xact_lock(41002,1)":"SELECT pg_advisory_xact_lock_shared(41002,1)")){p.execute();}}
  public void schedules(Collection<Long> ids)throws SQLException {enter(3);for(long key:scheduleKeys(ids))try(var p=connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")){p.setLong(1,key);p.execute();}}
  public void dentists(Collection<Long> ids)throws SQLException {enter(4);if(courseUc22Narrow)narrowRows("lock_dentist",ids);else rows("dentist","dentist_id",ids);}
  /** Called only by the dedicated UC21 role with the narrow functions installed. */
  public void uc21NewDraft(long dentistId)throws SQLException {
   requireUc21CommandStage();initialUc21Draft=true;enter(4);uc21Dentist(dentistId);
  }
  /** Existing stable arrangement and exact basis are locked before the caller rereads them. */
  public void uc21ExistingDraft(Collection<Long> dentists,Version basis)throws SQLException {
   requireUc21CommandStage();
   if(dentists.isEmpty())throw new IllegalArgumentException("DENTIST_LOCK_REQUIRED");
   schedules(List.of(basis.scheduleId()));
   enter(4);
   for(long id:dentists.stream().distinct().sorted().toList())uc21Dentist(id);
   enter(5);
   try(var p=connection.prepareStatement("SELECT adc_uc21_lock.lock_version(?,?)")){
    p.setLong(1,basis.scheduleId());p.setInt(2,basis.versionNo());p.execute();
   }
  }
  private void requireUc21CommandStage(){
   if(stage!=1||commandReference==null)throw new IllegalStateException("COMMAND_LOCK_REQUIRED_BEFORE_RESOURCES");
  }
  private void uc21Dentist(long id)throws SQLException {
   try(var p=connection.prepareStatement("SELECT adc_uc21_lock.lock_dentist(?)")){p.setLong(1,id);p.execute();}
  }
  public void versions(Collection<Version> versions)throws SQLException {enter(5);for(var v:versions.stream().distinct().sorted().toList())try(var p=connection.prepareStatement("SELECT schedule_id FROM adc.reception_schedule_version WHERE schedule_id=? AND schedule_version_no=? FOR UPDATE")){p.setLong(1,v.scheduleId());p.setInt(2,v.versionNo());try(var r=p.executeQuery()){if(!r.next())throw new SQLException("VERSION_NOT_FOUND","P0002");}}}
  public void appointments(Collection<Long> ids)throws SQLException {enter(6);if(courseUc22Narrow)narrowRows("lock_appointment",ids);else rows("appointment","appointment_id",ids);}
  public void roots(Collection<Long> ids)throws SQLException {enter(7);if(courseUc22Narrow)narrowRows("lock_resolution",ids);else rows("appointment_resolution","resolution_id",ids);}
  private void narrowRows(String function,Collection<Long> ids)throws SQLException {
   if(!Set.of("lock_dentist","lock_appointment","lock_resolution").contains(function))throw new IllegalArgumentException("INVALID_NARROW_LOCK");
   for(long id:ids.stream().distinct().sorted().toList())try(var p=connection.prepareStatement("SELECT adc_uc21_lock."+function+"(?)")){p.setLong(1,id);p.execute();}
  }
  private void rows(String table,String column,Collection<Long> ids)throws SQLException {for(long id:ids.stream().distinct().sorted().toList())try(var p=connection.prepareStatement("SELECT "+column+" FROM adc."+table+" WHERE "+column+"=? FOR UPDATE")){p.setLong(1,id);try(var r=p.executeQuery()){if(!r.next())throw new SQLException("LOCK_RESOURCE_NOT_FOUND","P0002");}}}
 }
}
