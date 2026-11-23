package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CourseClinicalCatalogTest {
    @TempDir Path dir;
    private CourseClinicalCatalog catalog(String body)throws Exception{Path path=dir.resolve(UUID.randomUUID()+".json");Files.writeString(path,body);return new CourseClinicalCatalog(path);}
    private String document(String items){return "{\"synthetic\":true,\"version\":\"SYNTHETIC test v1\",\"source\":\"TEST course source\",\"items\":"+items+"}";}
    @Test void explicitKnownPriceAndNoUnknownFallback()throws Exception{
        var value=catalog(document("[{\"name\":\"TEST check\",\"unit\":\"次\",\"unitPrice\":\"30.00\"}]"));
        assertTrue(value.price("TEST check","次").isPresent());assertTrue(value.price("other","次").isEmpty());assertTrue(value.price("TEST check","颗").isEmpty());assertTrue(value.document().path("synthetic").asBoolean());
    }
    @Test void rejectsUnprovenAndAmbiguousPriceSources(){
        assertThrows(IllegalArgumentException.class,()->catalog(document("[]")));
        String item="{\"name\":\"TEST check\",\"unit\":\"次\",\"unitPrice\":\"30.00\"}";
        assertThrows(IllegalArgumentException.class,()->catalog(document("["+item+","+item+"]")));
        assertThrows(IllegalArgumentException.class,()->catalog(document("["+item+"]").replace("\"synthetic\":true","\"synthetic\":false")));
    }
    @Test void rejectsNegativeOrUnroundedPrecision(){
        for(String price:List.of("-1.00","0.00","1.001"))assertThrows(IllegalArgumentException.class,()->catalog(document("[{\"name\":\"TEST\",\"unit\":\"次\",\"unitPrice\":\""+price+"\"}]")));
    }
    @Test void clinicalAndBillingResponsibilitiesStaySeparate(){
        var bindings=CourseClinicalBillingConfiguration.billingBindings();
        var dentist=new cn.adc.shared.security.StaffAuthorization(()->Optional.of("SYNTHETIC dentist"),bindings::bindings);
        var billing=new cn.adc.shared.security.StaffAuthorization(()->Optional.of("SYNTHETIC billing"),bindings::bindings);
        assertDoesNotThrow(()->dentist.require("UC11_CLINICAL",Set.of(81001L)));
        assertThrows(SecurityException.class,()->billing.require("UC11_CLINICAL",Set.of(81001L)));
        assertThrows(SecurityException.class,()->dentist.require("UC12",Set.of(81001L)));
        assertThrows(SecurityException.class,()->billing.require("UC10",Set.of(81002L)));
    }
}
