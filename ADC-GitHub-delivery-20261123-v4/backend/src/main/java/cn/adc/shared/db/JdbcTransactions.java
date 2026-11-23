package cn.adc.shared.db;
import javax.sql.DataSource;
import java.sql.*;
import java.util.Objects;
import java.util.logging.Logger;

/** Explicit sequential physical transactions; no blind retries or nested REQUIRES_NEW. */
public final class JdbcTransactions {
 @FunctionalInterface public interface Work<T>{T run(Connection c)throws Exception;}
 public static final class Failure extends RuntimeException {
  private final boolean unknown;private final String state;private final boolean commitAttempted;
  public Failure(boolean unknown,String state){this(unknown,state,false);}
  private Failure(boolean unknown,String state,boolean commitAttempted){super(unknown?"TRANSACTION_RESULT_UNKNOWN":"TRANSACTION_NOT_COMMITTED");this.unknown=unknown;this.state=state;this.commitAttempted=commitAttempted;}
  public boolean unknown(){return unknown;}public String sqlState(){return state;}
  public boolean commitAttempted(){return commitAttempted;}
 }
 private static final Logger LOG=Logger.getLogger(JdbcTransactions.class.getName());
 private final DataSource source;private final ThreadLocal<Boolean> active=ThreadLocal.withInitial(()->false);
 public JdbcTransactions(DataSource source){this.source=Objects.requireNonNull(source);}
 public boolean active(){return active.get();}
 public <T>T execute(Work<T> work){
  if(active.get())throw new IllegalStateException("NESTED_PHYSICAL_TRANSACTION_FORBIDDEN");
  Connection c;
  try{c=source.getConnection();}catch(SQLException e){throw failure(false,e);}
  try{
   c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);active.set(true);
   final T result;
   try {
    try(var st=c.createStatement()){st.execute("SET LOCAL lock_timeout='2s';SET LOCAL statement_timeout='6s'");}
    result=work.run(c);
   }catch(Exception e){
    String state=e instanceof SQLException s?s.getSQLState():"APPLICATION";
    boolean unknown=!rollback(c)||(state!=null&&state.startsWith("08"));
    if(e instanceof SecurityException s&&!unknown)throw s;if(e instanceof IllegalArgumentException a&&!unknown)throw a;
    if(e instanceof Failure f)throw new Failure(unknown||f.unknown(),f.sqlState());
    throw new Failure(unknown,state==null?"UNKNOWN":state);
   }
   try{c.commit();}catch(SQLException e){
    // The driver did not acknowledge COMMIT. Cleanup cannot prove it never committed.
    rollback(c);
    throw new Failure(true,e.getSQLState()==null?"UNKNOWN":e.getSQLState(),true);
   }
   return result;
  }catch(SQLException e){throw failure(false,e);}
  finally{
   active.remove();
   try{c.close();}catch(SQLException ignored){LOG.warning("TRANSACTION_CONNECTION_CLOSE_FAILED");}
  }
 }
 private static boolean rollback(Connection c){try{c.rollback();return true;}catch(SQLException e){return false;}}
 private static Failure failure(boolean unknown,SQLException e){
  return new Failure(unknown,e.getSQLState()==null?"UNKNOWN":e.getSQLState());
 }
}
