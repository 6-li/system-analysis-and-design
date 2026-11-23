package cn.adc.shared.config;
import java.time.ZoneId;
public interface ClinicDirectoryProvider {record Clinic(long id,String name,ZoneId zone,SourceStamp provenance){} Clinic find(long id);}
