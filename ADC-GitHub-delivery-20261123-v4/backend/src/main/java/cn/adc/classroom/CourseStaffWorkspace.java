package cn.adc.classroom;

import cn.adc.shared.security.StaffSessionIdentityAdapter;
import cn.adc.shared.audit.AuditDocuments;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

@Controller
@Profile("course-demo")
final class CourseStaffWorkspace {
    private record Page(String route, String title) {}

    private static final Page ISSUES = new Page("quarterly-issue", "异常处理");
    private static final Page INVOICE_QUESTION = new Page("invoice-question", "账单费用疑问");
    private static final Map<String, List<Page>> PAGES = Map.of(
            "SYNTHETIC reception", List.of(new Page("appointments", "预约与到诊"), new Page("patients", "患者建档")),
            "SYNTHETIC dentist", List.of(new Page("my-schedule", "我的接诊安排"), new Page("visit-consent", "诊疗同意"), new Page("visit-health", "健康资料"),
                    new Page("visit-treatment", "实际诊疗"), INVOICE_QUESTION),
            "SYNTHETIC billing", List.of(new Page("billing-workbench", "账单与结算"),new Page("treatment-invoice", "生成或查看账单"),
                    new Page("invoice-settlement", "模拟结算"), INVOICE_QUESTION),
            "SYNTHETIC procurement", List.of(new Page("purchase-requirements", "采购需求"),
                    new Page("supply-receipt", "验收入库")),
            "SYNTHETIC manager", List.of(new Page("stock-inquiry", "库存查询"),
                    new Page("supply-consumption", "实际耗用"), ISSUES, new Page("schedule-workspace", "排班草案"),
                    new Page("schedule-resolutions", "排班生效")),
            "SYNTHETIC operations", List.of(new Page("quarterly-preview", "季度统计"), ISSUES,
                    new Page("quarterly-calculation", "季度核算")),
            "SYNTHETIC finance", List.of(new Page("quarterly-financial-review", "财务复核")));

    private final Path assets;
    private final String index;
    private final boolean clinicalBatch;
    private final boolean uiUnification;
    private final boolean quarterlyUsability;
    private final boolean inventoryDetail;
    private final String inventoryGuide;
    private final boolean bookingBatch;
    private final boolean courseChange;
    private final boolean appointmentRead;
    private final boolean interactiveBooking;
    private final boolean appointmentWrite;
    private final boolean patientIntake;
    private final boolean scheduling;
    private final boolean scheduleActivation;
    private final boolean health;
    private final boolean consent;
    private final boolean treatment;
    private final String clinicZone;
    private cn.adc.appointments.MiniRequestInboxController.Access inboxAccess;
    @org.springframework.beans.factory.annotation.Autowired
    CourseStaffWorkspace(Environment environment,org.springframework.beans.factory.ObjectProvider<cn.adc.appointments.MiniRequestInboxController.Access> inbox) throws IOException {
        this(environment);inboxAccess=inbox.getIfAvailable();
    }

    CourseStaffWorkspace(Environment environment) throws IOException {
        interactiveBooking=environment.getProperty("adc.course.booking-interaction-enabled",Boolean.class,false);
        quarterlyUsability=environment.getProperty("adc.course.quarterly-usability-enabled",Boolean.class,false);
        uiUnification=environment.getProperty("adc.course.ui-unification-enabled",Boolean.class,false);
        clinicalBatch=environment.getProperty("adc.course.clinical-batch-enabled",Boolean.class,false);
        inventoryDetail=environment.getProperty("adc.course.inventory-detail-enabled",Boolean.class,false);
        inventoryGuide=inventoryDetail?java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(environment.getRequiredProperty("adc.course.inventory-source")))):"";
        bookingBatch = environment.getProperty("adc.course.booking-batch-enabled",Boolean.class,false);
        courseChange = environment.getProperty("adc.course.uc03-enabled", Boolean.class, false);
        appointmentRead = environment.getProperty("adc.course.appointment-read-enabled", Boolean.class, false);
        appointmentWrite = environment.getProperty("adc.course.appointment-write-validation", Boolean.class, false);
        patientIntake = environment.getProperty("adc.course.patient-intake-validation", Boolean.class, false)
                && environment.getProperty("adc.uc01.enabled", Boolean.class, false);
        scheduling = environment.getProperty("adc.course.scheduling-enabled", Boolean.class, false);
        scheduleActivation=environment.getProperty("adc.uc22.resolution-enabled",Boolean.class,false);
        health = environment.getProperty("adc.uc07.enabled", Boolean.class, false);
        consent = environment.getProperty("adc.uc08.enabled", Boolean.class, false);
        treatment = environment.getProperty("adc.uc09.enabled", Boolean.class, false);
        String zone = environment.getProperty("adc.course.clinic-zone", "");
        clinicZone = java.time.ZoneId.getAvailableZoneIds().contains(zone) ? zone : "";
        Path bundle = Path.of(environment.getRequiredProperty("adc.course.staff-bundle")).toRealPath();
        assets = bundle.resolve("assets").toRealPath();
        if (!assets.startsWith(bundle) || !Files.isDirectory(assets)) {
            throw new IOException("Course Vue assets must be inside the bundle");
        }
        Path shell = bundle.resolve("index.html").toRealPath();
        if (!shell.startsWith(bundle)) throw new IOException("Course Vue index must be inside the bundle");
        index = Files.readString(shell, UTF_8);
        if (!index.contains("<body>")) throw new IOException("Course Vue index has no body");
    }

    @GetMapping("/staff/{page}")
    ResponseEntity<String> workspace(@PathVariable String page, HttpServletRequest request) {
        var session = request.getSession(false);
        var authenticated = new StaffSessionIdentityAdapter(() -> {
            var value = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
            return value instanceof StaffSessionIdentityAdapter.VerifiedSession verified ? verified : null;
        }).current();
        if (authenticated.isEmpty()) {
            return ResponseEntity.status(303).location(URI.create("/")).cacheControl(CacheControl.noStore()).build();
        }
        List<Page> allowed = PAGES.getOrDefault(authenticated.get(), List.of()).stream()
                .filter(entry->uiUnification||!"billing-workbench".equals(entry.route()))
                .filter(entry->scheduleActivation||!"schedule-resolutions".equals(entry.route())).toList();
        if (allowed.stream().noneMatch(entry -> entry.route().equals(page))) {
            return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build();
        }
        var navigation = new StringBuilder("<nav aria-label='岗位工作台'>");
        for (Page entry : allowed) {
            navigation.append("<a href='/staff/").append(entry.route()).append("'>")
                    .append(entry.title()).append("</a> · ");
        }
        navigation.append("<a href='/'>切换岗位</a></nav>");

        String meta = "<meta name='adc-staff-role' content='" + authenticated.get().substring("SYNTHETIC ".length()) + "'>";
        if (appointmentRead && "appointments".equals(page)) {
            var context = AuditDocuments.JSON.createObjectNode().put("canRead", true);
            var actions = context.putArray("actions");
            if (appointmentWrite) {
                var verified = (StaffSessionIdentityAdapter.VerifiedSession)
                        session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                actions.add("create");
                if(courseChange)actions.add("change");
                if(bookingBatch)actions.add("cancel");
                if(clinicalBatch){actions.add("arrival");actions.add("noShow");}
                context.put("csrfToken", verified.csrfToken());
                context.put("requestActorScope", AuditDocuments.hash(
                        AuditDocuments.JSON.getNodeFactory().textNode(verified.subject())).substring(0, 32));
            }
            if(request.getParameter("sourceRequest")!=null){
                if(inboxAccess==null)return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();
                try{cn.adc.appointments.MiniRequestInboxService.bindSelection(context,inboxAccess.open(request).selection(request.getParameter("sourceRequest"),request.getParameter("sourceVersion")));}
                catch(SecurityException denied){return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build();}
                catch(IllegalArgumentException unavailable){return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).build();}
                catch(cn.adc.shared.db.JdbcTransactions.Failure unavailable){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
            }
            meta += "<meta name='adc-appointments-context' content='" + context + "'>";
            if(context.has("verifiedBookingSource") || bookingBatch&&context.has("verifiedChangeSource")){
                try{var preview=inboxAccess.open(request).processingPreview(request.getParameter("sourceRequest"),request.getParameter("sourceVersion"));meta+="<meta name='adc-booking-source-preview' content='"+java.util.Base64.getEncoder().encodeToString(preview.toString().getBytes(UTF_8))+"'>";}
                catch(SecurityException denied){return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build();}
                catch(IllegalArgumentException stale){return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).build();}
                catch(cn.adc.shared.db.JdbcTransactions.Failure unavailable){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
            }
        } else if ((patientIntake && "patients".equals(page))
                || (health && "visit-health".equals(page))
                || (consent && "visit-consent".equals(page))
                || (treatment && "visit-treatment".equals(page))
                || (scheduling && List.of("schedule-workspace", "schedule-resolutions").contains(page))
                || (quarterlyUsability && List.of("quarterly-preview","quarterly-issue","quarterly-calculation","quarterly-financial-review").contains(page))
                || (inventoryDetail && List.of("stock-inquiry","purchase-requirements","supply-receipt","supply-consumption").contains(page))
                || (clinicalBatch && List.of("treatment-invoice","invoice-question","invoice-settlement","billing-workbench").contains(page))) {
            var verified = (StaffSessionIdentityAdapter.VerifiedSession)
                    session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
            if (verified.csrfToken() != null && verified.csrfToken().matches("[a-f0-9-]{36}")) {
                String uc = "patients".equals(page) ? "01" : "visit-health".equals(page) ? "07"
                        : "visit-consent".equals(page) ? "08"
                        : "visit-treatment".equals(page) ? "09"
                        : "quarterly-preview".equals(page)?"17": "quarterly-issue".equals(page)?"18": "quarterly-calculation".equals(page)?"19": "quarterly-financial-review".equals(page)?"20"
                        : "stock-inquiry".equals(page)?"13": "purchase-requirements".equals(page)?"14": "supply-receipt".equals(page)?"15": "supply-consumption".equals(page)?"16"
                        : "billing-workbench".equals(page)?"10": "treatment-invoice".equals(page)?"10": "invoice-question".equals(page)?"11": "invoice-settlement".equals(page)?"12"
                        : "schedule-workspace".equals(page) ? "21" : "22";
                meta += "<meta name='adc-uc" + uc + "-csrf' content='" + verified.csrfToken() + "'>";
            }
        }
        if(uiUnification)meta+="<meta name='adc-ui-unification' content='true'>";
        if(quarterlyUsability)meta+="<meta name='adc-quarterly-usability' content='true'>";
        if(inventoryDetail && List.of("stock-inquiry","purchase-requirements","supply-receipt","supply-consumption").contains(page)) meta+="<meta name='adc-inventory-guide' content='"+inventoryGuide+"'>";
        if(uiUnification&&"billing-workbench".equals(page)){var verified=(StaffSessionIdentityAdapter.VerifiedSession)session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);meta+="<meta name='adc-uc12-csrf' content='"+verified.csrfToken()+"'>";}
        if(clinicalBatch){meta+="<meta name='adc-clinical-catalog-enabled' content='true'>";if("invoice-question".equals(page))meta+="<meta name='adc-invoice-clinical' content='"+("SYNTHETIC dentist".equals(authenticated.get())?"true":"false")+"'>";}
        if (bookingBatch) meta += "<meta name='adc-booking-batch-enabled' content='true'>";
        if (interactiveBooking) meta += "<meta name='adc-booking-interaction-enabled' content='true'>";
        if (!clinicZone.isEmpty()) meta += "<meta name='adc-clinic-zone' content='" + clinicZone + "'>";
        if(inboxAccess!=null && List.of("patients","appointments").contains(page))meta+="<meta name='adc-mini-inbox-enabled' content='true'>";
        if(inboxAccess!=null && "patients".equals(page) && request.getParameter("sourceRequest")!=null){
            try{
                var preview=inboxAccess.open(request).verificationPreview(request.getParameter("sourceRequest"),request.getParameter("sourceVersion"));
                meta+="<meta name='adc-uc01-source-preview' content='"+java.util.Base64.getEncoder().encodeToString(preview.toString().getBytes(UTF_8))+"'>";
            }catch(SecurityException denied){return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build();}
            catch(IllegalArgumentException stale){return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).build();}
            catch(cn.adc.shared.db.JdbcTransactions.Failure unavailable){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType(MediaType.TEXT_HTML, UTF_8))
                .body(index.replace("<body>", "<body>" + navigation).replace("</head>", meta + "</head>"));
    }

    @GetMapping("/assets/{file}")
    ResponseEntity<FileSystemResource> asset(@PathVariable String file) throws IOException {
        if (!file.matches("[A-Za-z0-9_-]+\\.(js|css)")) return ResponseEntity.notFound().build();
        Path candidate = assets.resolve(file);
        if (!Files.isRegularFile(candidate)) return ResponseEntity.notFound().build();
        Path resolved = candidate.toRealPath();
        if (!resolved.startsWith(assets)) return ResponseEntity.notFound().build();
        var media = file.endsWith(".css") ? "text/css" : "text/javascript";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(media)).body(new FileSystemResource(resolved));
    }
}

