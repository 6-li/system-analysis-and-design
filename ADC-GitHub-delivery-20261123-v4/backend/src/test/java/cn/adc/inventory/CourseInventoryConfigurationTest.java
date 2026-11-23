package cn.adc.inventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Set;
import cn.adc.shared.security.*;
import static org.junit.jupiter.api.Assertions.*;
class CourseInventoryConfigurationTest {
 @TempDir Path dir;
 @Test void explicitStableSourcesOnly()throws Exception{
  Path file=dir.resolve("sources.json");Files.writeString(file,"{\"synthetic\":true,\"version\":\"SYNTHETIC inventory v1\",\"receiptId\":1000000,\"consumptionId\":1000001,\"occurredAt\":\"2026-11-22T00:00:00Z\"}");
  var a=CourseInventoryConfiguration.Sources.load(file);assertEquals(a,CourseInventoryConfiguration.Sources.load(file));assertEquals(1000000,a.receipt());
  Files.writeString(file,"{\"synthetic\":false}");assertThrows(IllegalArgumentException.class,()->CourseInventoryConfiguration.Sources.load(file));
 }
 @Test void originalRoleAndClinicBoundaries(){
  var b=CourseInventoryConfiguration.bindings();
  var manager=new StaffAuthorization(()->java.util.Optional.of("SYNTHETIC manager"),b::bindings);
  assertDoesNotThrow(()->manager.require("UC13",Set.of(81001L)));
  assertThrows(SecurityException.class,()->manager.require("UC14",Set.of(81001L)));
  assertThrows(SecurityException.class,()->manager.require("UC16",Set.of(81002L)));
 }
}
