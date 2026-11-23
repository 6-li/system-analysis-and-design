package cn.adc.appointments;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import tools.jackson.databind.JsonNode;
@RestController @Profile("course-demo")
@ConditionalOnProperty(name="adc.course.clinical-batch-enabled",havingValue="true")
final class CourseClinicalCatalogController {
    private final CourseClinicalCatalog catalog;
    private CourseProjectCatalog projects;
    CourseClinicalCatalogController(CourseClinicalCatalog catalog){this.catalog=catalog;}
    @org.springframework.beans.factory.annotation.Autowired
    CourseClinicalCatalogController(CourseClinicalCatalog catalog,org.springframework.beans.factory.ObjectProvider<CourseProjectCatalog> projects){this.catalog=catalog;this.projects=projects.getIfAvailable();}
    @GetMapping("/api/v1/staff/course-treatment-catalog")
    ResponseEntity<JsonNode> read(HttpServletRequest request){
        var actor=CourseAppointmentRead.identity(request).current();
        if(actor.isEmpty()||!java.util.Set.of("SYNTHETIC dentist","SYNTHETIC billing").contains(actor.get()))return ResponseEntity.status(actor.isEmpty()?401:403).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(projects==null?catalog.document():projects.catalog.document());
    }
}
