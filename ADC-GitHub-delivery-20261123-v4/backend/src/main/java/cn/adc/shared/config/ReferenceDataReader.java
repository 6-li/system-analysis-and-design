package cn.adc.shared.config;
import java.util.Optional;
public interface ReferenceDataReader {
 record Clinic(long id,String name){} record Dentist(long id,String qualification){}
 Optional<Clinic> clinic(long id);Optional<Dentist> dentist(long id);
}
