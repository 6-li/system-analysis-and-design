package cn.adc.shared.db;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Only an explicit isolated target, credentials supplied separately, no pgpass/default target. */
public final class P0DriverDataSource implements DataSource {
 private final String url,user,database;private final int port;private final Supplier<String> password;
 public P0DriverDataSource(String url,String user,Supplier<String> password){this(url,user,password,-1);}
 /** Explicit test proxy endpoint still verifies the actual pinned server port on every connection. */
 public P0DriverDataSource(String url,String user,Supplier<String> password,int expectedServerPort){
  var m=Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([0-9]{1,5})/(adc_p0_batch1_[a-z0-9_]+)").matcher(Objects.requireNonNull(url));
  if(!m.matches())throw new IllegalArgumentException("EXPLICIT_ISOLATED_TARGET_REQUIRED");port=Integer.parseInt(m.group(1));if(port==5432||port<1024||port>65535)throw new IllegalArgumentException("ISOLATED_PORT_REQUIRED");
  if(user==null||!Set.of("p0_probe_app","p0_fixture_owner","p0_config_app","p0_config_fixture").contains(user))throw new IllegalArgumentException("LEAST_PRIVILEGE_ACCOUNT_REQUIRED");
  this.url=url;this.user=user;this.database=m.group(2);this.password=Objects.requireNonNull(password);if(expectedServerPort!=-1){if(expectedServerPort==5432||expectedServerPort<1024||expectedServerPort>65535)throw new IllegalArgumentException("ISOLATED_SERVER_PORT_REQUIRED");serverPort=expectedServerPort;}else serverPort=port;
 }
 private final int serverPort;
 @Override public Connection getConnection() throws SQLException {
  String secret=password.get();if(secret==null||secret.isBlank())throw new SQLException("P0_CREDENTIAL_UNAVAILABLE","28000");
  var props=new Properties();props.setProperty("user",user);props.setProperty("password",secret);props.setProperty("connectTimeout","3");props.setProperty("socketTimeout","8");props.setProperty("sslmode","disable");props.setProperty("ApplicationName","adc-p0-batch1");props.setProperty("options","-c timezone=UTC");
  Connection c=DriverManager.getConnection(url,props);
  try(var st=c.createStatement();var r=st.executeQuery("SELECT current_database(),current_user,inet_server_port(),current_setting('server_version_num')")){
   if(!r.next()||!database.equals(r.getString(1))||!user.equals(r.getString(2))||serverPort!=r.getInt(3)||!"180006".equals(r.getString(4)))throw new SQLException("P0_TARGET_MISMATCH","08001");
  }catch(SQLException e){c.close();throw e;}return c;
 }
 @Override public Connection getConnection(String u,String p)throws SQLException{throw new SQLFeatureNotSupportedException("CREDENTIAL_OVERRIDE_DISABLED");}
 @Override public PrintWriter getLogWriter(){return null;}@Override public void setLogWriter(PrintWriter p)throws SQLException{throw new SQLFeatureNotSupportedException("CREDENTIAL_LOGGING_DISABLED");}
 @Override public void setLoginTimeout(int n){}@Override public int getLoginTimeout(){return 3;}@Override public Logger getParentLogger(){return Logger.getLogger("cn.adc.shared.db");}
 @Override public <T>T unwrap(Class<T> type)throws SQLException{if(type.isInstance(this))return type.cast(this);throw new SQLException("NOT_A_WRAPPER");}@Override public boolean isWrapperFor(Class<?> type){return type.isInstance(this);}
}
