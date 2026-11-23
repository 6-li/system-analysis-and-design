package cn.adc.shared.security;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
/** UT-SCH-003/025/026: explicit role/action/scope and revocation. */
class StaffAuthorizationTest {
 @Test void denyUntrustedIdentityAndMissingBinding() {
  var a=new StaffAuthorization(()->Optional.empty(),()->Map.of());
  assertThrows(SecurityException.class,()->a.require("UC21",Set.of(1L)));
 }
 @Test void headquartersEditAndPublishAreIndependent() {
  var grants=new AtomicReference<>(Map.of("hq",new StaffAuthorization.Grant("v1",Set.of("HQ"),Set.of("DURATION_EDIT"),Set.of(),true)));
  var a=new StaffAuthorization(()->Optional.of("hq"),grants::get);
  assertEquals("hq",a.require("DURATION_EDIT",Set.of()).actor());
  assertThrows(SecurityException.class,()->a.require("DURATION_PUBLISH",Set.of()));
  grants.set(Map.of());assertThrows(SecurityException.class,()->a.require("DURATION_EDIT",Set.of()));
 }
 @Test void clinicAndRoleDoNotConferOtherReceptionDuties() {
  var a=new StaffAuthorization(()->Optional.of("r"),()->Map.of("r",new StaffAuthorization.Grant("v1",Set.of("BOOKING_RECEPTION"),Set.of("UC02","UC03"),Set.of(1L),false)));
  assertEquals(Set.of(1L),a.require("UC03",Set.of(1L)).clinicIds());
  assertThrows(SecurityException.class,()->a.require("UC03",Set.of(1L,2L)));
  assertThrows(SecurityException.class,()->a.require("UC05",Set.of(1L)));
 }
 @Test void configuredActionCannotBypassOriginalRole() {
  var a=new StaffAuthorization(()->Optional.of("m"),()->Map.of("m",new StaffAuthorization.Grant("v",Set.of("CLINIC_MANAGER"),Set.of("UC03","UC21"),Set.of(1L),false)));
  assertThrows(SecurityException.class,()->a.require("UC03",Set.of(1L)));
  assertDoesNotThrow(()->a.require("UC21",Set.of(1L)));
 }
}
