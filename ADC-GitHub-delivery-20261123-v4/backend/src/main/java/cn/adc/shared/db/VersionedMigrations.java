package cn.adc.shared.db;
import tools.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Fixed approved SQL set, one explicit transaction; no new migration history table. */
public final class VersionedMigrations {
 public record Migration(String version,String file,String sha256,String sql){}
 private final List<Migration> migrations;
 private final int expectedTables;
 public VersionedMigrations(){
  this("manifest.json",12);
 }
 /** Explicit course installation; never selected automatically during application startup. */
 public static VersionedMigrations course(){return new VersionedMigrations("course-manifest.json",33);}
 private VersionedMigrations(String manifestFile,int tableCount){
  expectedTables=tableCount;
  try(var input=getClass().getResourceAsStream("/db/p0/"+manifestFile)){
   var manifest=new ObjectMapper().readTree(Objects.requireNonNull(input));var list=new ArrayList<Migration>();int next=1;
   for(var item:manifest.get("migrations")){
    String file=item.get("file").asText(),version=item.get("version").asText();if(!file.matches("V[0-9]{3}__[a-z0-9_]+\\.sql")||!version.equals("V"+String.format("%03d",next++)))throw new IllegalArgumentException("INVALID_MIGRATION_ORDER");
    byte[] bytes;try(var stream=getClass().getResourceAsStream("/db/p0/"+file)){bytes=Objects.requireNonNull(stream).readAllBytes();}
    String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));if(!digest.equals(item.get("sha256").asText()))throw new IllegalArgumentException("MIGRATION_CHECKSUM_MISMATCH");
    String sql=new String(bytes,StandardCharsets.UTF_8);if(java.util.regex.Pattern.compile("\\b(DROP|TRUNCATE|ALTER\\s+DATABASE|ALTER\\s+SYSTEM)\\b",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql.replaceAll("(?m)--.*$","")).find())throw new IllegalArgumentException("MIGRATION_OUTSIDE_SCOPE");
    list.add(new Migration(version,file,digest,sql));
   }migrations=List.copyOf(list);
  }catch(Exception e){throw new IllegalStateException("APPROVED_MIGRATION_SET_UNAVAILABLE",e);}
 }
 public List<Migration> migrations(){return migrations;}
 public void applyEmpty(Connection c)throws SQLException {
  if(c.getAutoCommit())throw new SQLException("MIGRATION_REQUIRES_EXPLICIT_TRANSACTION","25000");
  try(var st=c.createStatement();var r=st.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='adc' AND table_type='BASE TABLE'")){r.next();if(r.getInt(1)!=0)throw new SQLException("NONEMPTY_SCHEMA_STOP_NO_REPLAY","P0001");}
  for(var m:migrations)try(var st=c.createStatement()){st.execute(m.sql());while(st.getMoreResults()||st.getUpdateCount()!=-1){} }
  try(var st=c.createStatement();var r=st.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='adc' AND table_type='BASE TABLE'")){r.next();if(r.getInt(1)!=expectedTables)throw new SQLException("APPROVED_TABLE_SET_MISMATCH","P0001");}
 }
}
