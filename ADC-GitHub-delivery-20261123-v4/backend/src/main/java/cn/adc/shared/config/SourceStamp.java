package cn.adc.shared.config;
import java.time.Instant;
/** Server-injected provenance, not a client assertion or a new persisted field. */
public record SourceStamp(String reference,String version,String verifiedBy,Instant verifiedAt,boolean usable){
 public void requireUsable(){if(!usable||reference==null||reference.isBlank()||version==null||version.isBlank()||verifiedBy==null||verifiedBy.isBlank()||verifiedAt==null)throw new ConfigurationUnavailable();}
}
