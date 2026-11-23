package cn.adc.appointments;
import cn.adc.shared.audit.AuditDocuments;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
/** Explicit course price source. No unknown-name fallback or clinical conclusions. */
final class CourseClinicalCatalog {
    record Item(String key,String name,String unit,BigDecimal price){}
    private final List<Item> items;
    private final String version,source;
    CourseClinicalCatalog(Path path)throws IOException{
        if(!path.isAbsolute()||!Files.isRegularFile(path)||Files.size(path)>65536)throw new IllegalArgumentException("EXPLICIT_COURSE_CATALOG_REQUIRED");
        var body=AuditDocuments.JSON.readTree(Files.readString(path));
        if(!body.path("synthetic").isBoolean()||!body.path("synthetic").booleanValue()||!body.path("items").isArray()||body.path("items").isEmpty()||body.path("items").size()>100)throw new IllegalArgumentException("SYNTHETIC_CATALOG_REQUIRED");
        version=AuditDocuments.text(body,"version");source=AuditDocuments.text(body,"source");
        if(!version.startsWith("SYNTHETIC ")||version.length()>128||source.length()>1000)throw new IllegalArgumentException("CATALOG_PROVENANCE_REQUIRED");
        var values=new ArrayList<Item>();var keys=new HashSet<String>();var ids=new HashSet<String>();
        for(var row:body.path("items")){
            var name=AuditDocuments.text(row,"name");var unit=AuditDocuments.text(row,"unit");
            if(name.length()>200||unit.length()>20||!keys.add(name+"\u0000"+unit))throw new IllegalArgumentException("INVALID_CATALOG_ITEM");
            var price=new BigDecimal(AuditDocuments.text(row,"unitPrice"));
            if(price.signum()<=0||price.scale()>2||price.compareTo(new BigDecimal("1000000"))>0)throw new IllegalArgumentException("INVALID_CATALOG_PRICE");
            String key=row.has("projectKey")?AuditDocuments.text(row,"projectKey"):null;
            if(key!=null&&(!key.matches("[A-Z][A-Z0-9_]{1,79}")||!ids.add(key)))throw new IllegalArgumentException("INVALID_PROJECT_KEY");
            values.add(new Item(key,name,unit,price));
        }
        items=List.copyOf(values);
    }
    Optional<TreatmentInvoiceService.Price> price(String name,String unit){
        return items.stream().filter(x->x.name().equals(name)&&x.unit().equals(unit)).findFirst().map(x->new TreatmentInvoiceService.Price(x.price(),version,source));
    }
    String version(){return version;}
    Item requireKey(String key){return items.stream().filter(x->key!=null&&key.equals(x.key())).findFirst().orElseThrow(()->new IllegalArgumentException("UNKNOWN_PROJECT_KEY"));}
    Optional<TreatmentInvoiceService.Price> priceByKey(String key,String unit){var item=requireKey(key);return item.unit().equals(unit)?Optional.of(new TreatmentInvoiceService.Price(item.price(),version,source)):Optional.empty();}
    JsonNode document(){
        var result=AuditDocuments.JSON.createObjectNode().put("synthetic",true).put("version",version).put("source",source);
        var rows=result.putArray("items");for(var item:items){var row=rows.addObject().put("name",item.name()).put("unit",item.unit()).put("unitPrice",item.price().toPlainString());if(item.key()!=null)row.put("projectKey",item.key());}return result;
    }
}
