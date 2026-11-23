package cn.adc.shared.db;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JdbcTransactionsTest {
 static class Fake {
  int commits,rollbacks;boolean commitUnknown,rollbackFails,closeFails;SQLException commitFailure;
  Connection connection(){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
   case "createStatement" -> Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(x,n,v)->n.getReturnType()==boolean.class?false:null);
   case "commit" -> {commits++;if(commitFailure!=null)throw commitFailure;if(commitUnknown)throw new SQLException("synthetic lost commit reply","08006");yield null;}
   case "rollback" -> {rollbacks++;if(rollbackFails)throw new SQLException("synthetic unavailable","08006");yield null;}
   case "getAutoCommit" -> false;
   case "close" -> {if(closeFails)throw new SQLException("synthetic close failed","08006");yield null;}
   default -> null;
  });}
  DataSource source(){return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(p,m,a)->m.getName().equals("getConnection")?connection():null);}
 }
 @Test void successCommitsExactlyOnceNoRetry(){var f=new Fake();var tx=new JdbcTransactions(f.source());assertEquals(Integer.valueOf(42),tx.execute(c->42));assertEquals(1,f.commits);assertEquals(0,f.rollbacks);assertFalse(tx.active());}
 @Test void statementFailureConfirmedRollback(){var f=new Fake();var tx=new JdbcTransactions(f.source());var e=assertThrows(JdbcTransactions.Failure.class,()->tx.execute(c->{throw new SQLException("synthetic","23514");}));assertFalse(e.unknown());assertEquals("23514",e.sqlState());assertEquals(1,f.rollbacks);assertEquals(0,f.commits);}
 @Test void unknownCommitNeverAutomaticallyReplays(){var f=new Fake();f.commitUnknown=true;var n=new AtomicInteger();var tx=new JdbcTransactions(f.source());var e=assertThrows(JdbcTransactions.Failure.class,()->tx.execute(c->n.incrementAndGet()));assertTrue(e.unknown());assertEquals(1,n.get());assertEquals(1,f.commits);assertFalse(tx.active());}
 @Test void rollbackFailureRemainsUnknown(){var f=new Fake();f.rollbackFails=true;var tx=new JdbcTransactions(f.source());assertTrue(assertThrows(JdbcTransactions.Failure.class,()->tx.execute(c->{throw new SQLException("synthetic","23505");})).unknown());}
 @Test void connectionAcquisitionFailureDidNotAttemptCommit() {
  var source=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},
          (p,m,a)->{throw new SQLException("unavailable","08001");});
  var e=assertThrows(JdbcTransactions.Failure.class,()->new JdbcTransactions(source).execute(c->fail("work ran")));
  assertFalse(e.unknown());
  assertFalse(e.commitAttempted());
 }
 @Test void nestedIndependentTransactionIsRejected(){var f=new Fake();var tx=new JdbcTransactions(f.source());assertThrows(JdbcTransactions.Failure.class,()->tx.execute(c->tx.execute(inner->1)));assertEquals(0,f.commits);assertEquals(1,f.rollbacks);}
 @Test void commitExceptionWithoutReliableOutcomeCannotBecomeConfirmedRollback() {
  for (String state : new String[]{null, "HYT00"}) {
   var f=new Fake();f.commitFailure=new SQLException("synthetic ambiguous commit",state);
   var tx=new JdbcTransactions(f.source());
   var e=assertThrows(JdbcTransactions.Failure.class,()->tx.execute(c->1));
   assertTrue(e.unknown());
   assertTrue(e.commitAttempted());
   assertEquals(1,f.commits);
   assertFalse(tx.active());
  }
 }
 @Test void closeFailureAfterAcknowledgedCommitPreservesResult() {
  var f=new Fake();f.closeFails=true;
  var tx=new JdbcTransactions(f.source());
  assertEquals(Integer.valueOf(1),tx.execute(c->1));
  assertEquals(1,f.commits);
  assertFalse(tx.active());
 }
}
