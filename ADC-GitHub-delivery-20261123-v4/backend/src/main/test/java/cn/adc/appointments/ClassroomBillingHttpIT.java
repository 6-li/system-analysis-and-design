package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.inventory.ClassroomInventoryConfiguration;
import cn.adc.quarterly.ClassroomQuarterlyConfiguration;
import cn.adc.scheduling.ClassroomScheduleConfiguration;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ClassroomBillingHttpIT {
    private static final String DENTIST = "SYNTHETIC dentist";
    private static final String MINI_REQUEST = "00000000-0000-4000-8000-000000000101";
    private static final String MINI_ACCOUNT = "SYNTHETIC classroom mini account";
    private static final String MINI_IDENTITY = "SYNTHETIC classroom new patient";
    private static final String RECEPTION = "SYNTHETIC reception";
    private static final String BILLING = "SYNTHETIC billing";
    private static final String PROCUREMENT = "SYNTHETIC procurement";
    private static final String MANAGER = "SYNTHETIC manager";
    private static final String OPERATIONS = "SYNTHETIC operations";
    private static final String FINANCE = "SYNTHETIC finance";
    private static final java.util.Map<String, String> DEMO_PASSWORDS = demoPasswords();

    private static java.util.Map<String, String> demoPasswords() {
        if (!"true".equals(System.getenv("ADC_CLASSROOM_DEMO"))) return java.util.Map.of();
        boolean manual = "true".equals(System.getenv("ADC_CLASSROOM_HOLD"));
        var passwords = new java.util.HashMap<String, String>();
        for (var account : List.of("reception", "dentist", "billing", "procurement", "manager", "operations", "finance")) {
            var password = manual ? System.getenv("ADC_DEMO_" + account.toUpperCase(java.util.Locale.ROOT))
                    : UUID.randomUUID().toString();
            if (password == null || password.isBlank() || passwords.containsValue(password))
                throw new IllegalStateException("Set a distinct local demo password for each ADC_DEMO account");
            passwords.put(account, password);
        }
        return java.util.Map.copyOf(passwords);
    }

    private static String demoLoginForm(String account) {
        return "account=" + account + "&password="
                + java.net.URLEncoder.encode(DEMO_PASSWORDS.get(account), java.nio.charset.StandardCharsets.UTF_8);
    }
    private static final AtomicLong IDS = new AtomicLong(71000);
    private static final String VISIT = "/api/v1/staff/visits/42";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({MiniBookingOptionsController.class, MiniPatientProfileController.class, BookingInteractionController.class, MiniIntakeController.class, MiniAppointmentReadController.class, PatientMaintenanceController.class, VisitHealthController.class, VisitConsentController.class, VisitTreatmentController.class,
            TreatmentInvoiceController.class, InvoiceSettlementController.class, InvoiceQuestionController.class,
            ClassroomInventoryConfiguration.class,
            ClassroomQuarterlyConfiguration.class, ClassroomScheduleConfiguration.class})
    static class Runtime {
        private final Clock clock = Clock.systemUTC();
        private final String classroomZone="true".equals(System.getenv("ADC_CLASSROOM_HOLD"))?"Asia/Shanghai":"Etc/UTC";
        private final JdbcTransactions tx = new JdbcTransactions(new T028IsolatedPgIT.IsolatedData("runtime", 0));
        private final SourceStamp stamp = new SourceStamp("SYNTHETIC", "classroom-billing", "isolated", clock.instant(), true);

        @Bean StaffBindingAdapter bindings() {
            return new StaffBindingAdapter(() -> List.of(
                    entry(DENTIST, "DENTIST", Set.of("UC07", "UC08", "UC09", "UC11_CLINICAL")),
                    entry(BILLING, "BILLING_STAFF", Set.of("UC10", "UC11", "UC12")),
                    entry(PROCUREMENT, "PROCUREMENT_STAFF", Set.of("UC14", "UC15")),
                    entry(MANAGER, "CLINIC_MANAGER", Set.of("UC13", "UC16", "UC18_CORRECTION", "UC21", "UC22")),
                    new StaffBindingAdapter.Entry(OPERATIONS, new StaffAuthorization.Grant(stamp.version(),
                            Set.of("HQ_OPERATIONS"), Set.of("UC17", "UC18", "UC19"), Set.of(81001L, 81002L), false), stamp),
                    entry(FINANCE, "HQ_FINANCE", Set.of("UC20")),
                    new StaffBindingAdapter.Entry(RECEPTION, new StaffAuthorization.Grant(stamp.version(),
                            Set.of("BOOKING_RECEPTION", "CLINIC_RECEPTION"), Set.of("UC01", "UC02", "UC03", "UC04", "UC05", "UC06"),
                            Set.of(81001L), false), stamp)));
        }

        private StaffBindingAdapter.Entry entry(String actor, String role, Set<String> actions) {
            return new StaffBindingAdapter.Entry(actor,
                    new StaffAuthorization.Grant(stamp.version(), Set.of(role), actions, Set.of(81001L), false), stamp);
        }

        // This filter is compiled only in the integration test and is bound to its loopback server.
        @Bean Filter syntheticSession() {
            return (request, response, chain) -> {
                var http = (HttpServletRequest) request;
                String role = http.getHeader("X-Test-Classroom-Role");
                if (!"true".equals(System.getenv("ADC_CLASSROOM_DEMO"))
                        && ("dentist".equals(role) || "billing".equals(role))) {
                    http.getSession(true).setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                            new StaffSessionIdentityAdapter.VerifiedSession(
                                    "dentist".equals(role) ? DENTIST : BILLING, "SYNTHETIC-CSRF", stamp));
                }
                chain.doFilter(request, response);
            };
        }

        @Bean Filter demoPages() {
            return (request, response, chain) -> {
                var http = (HttpServletRequest) request;
                var out = (jakarta.servlet.http.HttpServletResponse) response;
                if (!"true".equals(System.getenv("ADC_CLASSROOM_DEMO"))) {
                    chain.doFilter(request, response);
                    return;
                }
                String path = http.getRequestURI();
                var bundle = java.nio.file.Path.of(System.getenv("ADC_T028_EVIDENCE"), "staff-bundle");
                var pages = new java.util.HashMap<>(java.util.Map.of("/staff/visit-health", "07", "/staff/visit-consent", "08", "/staff/visit-treatment", "09",
                        "/staff/treatment-invoice", "10", "/staff/invoice-settlement", "12", "/staff/appointments", "05", "/staff/patients", "01"));
                boolean inventory = "true".equals(System.getenv("ADC_CLASSROOM_STOCK_WEB"));
                if (inventory) pages.putAll(java.util.Map.of("/staff/stock-inquiry", "13", "/staff/purchase-requirements", "14",
                        "/staff/supply-receipt", "15", "/staff/supply-consumption", "16"));
                if (inventory) pages.putAll(java.util.Map.of("/staff/quarterly-preview", "17", "/staff/quarterly-issue", "18",
                        "/staff/quarterly-calculation", "19", "/staff/quarterly-financial-review", "20"));
                if (inventory) pages.putAll(java.util.Map.of("/staff/schedule-workspace", "21", "/staff/schedule-resolutions", "22"));
                pages.put("/staff/my-schedule", "DS");
                boolean invoiceQuestion = "true".equals(System.getenv("ADC_CLASSROOM_INVOICE_QUESTION_WEB"));
                if (invoiceQuestion) pages.put("/staff/invoice-question", "11");
                if ("GET".equals(http.getMethod()) && "/".equals(path)) {
                    out.setContentType("text/html;charset=UTF-8");
                    out.getWriter().write("""
                            <!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>ADC 课堂工作台</title>
                            <main><h1>ADC 课堂工作台</h1><p>合成患者编号 88001，待到诊预约 904。已有就诊 42 可用于直接演示诊疗。</p>
                            <form method="post" action="/demo/login">
                              <label>员工账号 <input name="account" autocomplete="username" required></label>
                              <label>密码 <input name="password" type="password" autocomplete="current-password" required></label>
                              <button type="submit">登录</button>
                            </form>
                            </main></html>
                            """);
                } else if ("POST".equals(http.getMethod()) && "/demo/login".equals(path)) {
                    if (!"same-origin".equals(http.getHeader("Sec-Fetch-Site"))) {
                        out.sendError(403);
                        return;
                    }
                    String role = http.getParameter("account");
                    String submitted = http.getParameter("password");
                    String expected = role == null ? null : DEMO_PASSWORDS.get(role);
                    if (expected == null || submitted == null
                            || !java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    submitted.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            || !inventory && Set.of("procurement", "manager").contains(role)) {
                        out.sendError(401);
                        return;
                    }
                    var old = http.getSession(false);
                    if (old != null) old.invalidate();
                    http.getSession(true).setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                            new StaffSessionIdentityAdapter.VerifiedSession(
                                    switch (role) { case "dentist" -> DENTIST; case "reception" -> RECEPTION;
                                        case "procurement" -> PROCUREMENT; case "manager" -> MANAGER;
                                        case "operations" -> OPERATIONS; case "finance" -> FINANCE; default -> BILLING; },
                                    "SYNTHETIC-CSRF", stamp));
                    out.sendRedirect(switch (role) {
                        case "dentist" -> "/staff/visit-consent";
                        case "reception" -> "/staff/appointments";
                        case "procurement" -> "/staff/purchase-requirements";
                        case "manager" -> "/staff/stock-inquiry";
                        case "operations" -> "/staff/quarterly-preview";
                        case "finance" -> "/staff/quarterly-financial-review";
                        default -> "/staff/treatment-invoice";
                    });
                } else if ("GET".equals(http.getMethod()) && pages.containsKey(path)) {
                    String uc = pages.get(path);
                    var session = http.getSession(false);
                    var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                    boolean dentistry = Set.of("07", "08", "09", "DS").contains(uc)
                            || "11".equals(uc) && verified instanceof StaffSessionIdentityAdapter.VerifiedSession sessionIdentity
                            && DENTIST.equals(sessionIdentity.subject());
                    String inventoryActor = switch (uc) {
                        case "13", "16", "21", "22" -> MANAGER;
                        case "14", "15" -> PROCUREMENT;
                        case "17", "19" -> OPERATIONS;
                        case "20" -> FINANCE;
                        case "18" -> verified instanceof StaffSessionIdentityAdapter.VerifiedSession current
                                && MANAGER.equals(current.subject()) ? MANAGER : OPERATIONS;
                        default -> null;
                    };
                    String actor = inventoryActor != null ? inventoryActor
                            : dentistry ? DENTIST : Set.of("01", "05").contains(uc) ? RECEPTION : BILLING;
                    if (!(verified instanceof StaffSessionIdentityAdapter.VerifiedSession value)
                            || !actor.equals(value.subject())) {
                        out.sendRedirect("/");
                        return;
                    }
                    out.setContentType("text/html;charset=UTF-8");
                    out.setHeader("Cache-Control", "no-store");
                    String links = Set.of("01", "05").contains(uc) ? "<a href='/staff/patients'>患者建档</a> · <a href='/staff/appointments'>预约与到诊</a>" : dentistry
                            ? "<a href='/staff/my-schedule'>我的接诊安排</a> · <a href='/staff/visit-health'>健康资料</a> · <a href='/staff/visit-consent'>诊疗同意</a> · <a href='/staff/visit-treatment'>实际诊疗</a>"
                            : "<a href='/staff/treatment-invoice'>生成或查看账单</a> · <a href='/staff/invoice-settlement'>模拟结算</a>";
                    if (invoiceQuestion && (DENTIST.equals(actor) || BILLING.equals(actor))) {
                        links += " · <a href='/staff/invoice-question'>账单费用疑问</a>";
                    }
                    if (PROCUREMENT.equals(inventoryActor)) {
                        links = "<a href='/staff/purchase-requirements'>采购需求</a> · <a href='/staff/supply-receipt'>验收入库</a>";
                    } else if (MANAGER.equals(inventoryActor)) {
                        links = "<a href='/staff/stock-inquiry'>库存查询</a> · <a href='/staff/supply-consumption'>实际耗用</a> · <a href='/staff/quarterly-issue'>异常补正</a>"
                                + " · <a href='/staff/schedule-workspace'>排班草案</a> · <a href='/staff/schedule-resolutions'>排班生效</a>";
                    } else if (OPERATIONS.equals(inventoryActor)) {
                        links = "<a href='/staff/quarterly-preview'>季度统计</a> · <a href='/staff/quarterly-issue'>异常复核</a> · <a href='/staff/quarterly-calculation'>季度核算</a>";
                    } else if (FINANCE.equals(inventoryActor)) {
                        links = "<a href='/staff/quarterly-financial-review'>财务复核</a>";
                    }
                    String meta = "<meta name='adc-uc" + uc + "-csrf' content='SYNTHETIC-CSRF'>";
                    if ("05".equals(uc)) {
                        String scope = AuditDocuments.requestReference(RECEPTION, "UC", "ARRIVAL_VERIFY", UUID.randomUUID()).split("/")[2];
                        var context = AuditDocuments.JSON.createObjectNode().put("canRead", true)
                                .put("csrfToken", "SYNTHETIC-CSRF").put("requestActorScope", scope);
                        context.putArray("actions").add("create").add("change").add("arrival").add("noShow").add("cancel");
                        meta = "<meta name='adc-appointments-context' content='" + context + "'>";
                        if(http.getParameter("sourceRequest")!=null){
                            try{
                                var identity=CourseAppointmentRead.identity(http);
                                var inbox=new MiniRequestInboxService(tx,new StaffAuthorization(identity,bindings()::bindings),81001);
                                MiniRequestInboxService.bindSelection(context,inbox.selection(http.getParameter("sourceRequest"),http.getParameter("sourceVersion")));
                                meta="<meta name='adc-appointments-context' content='"+context+"'>";
                                if(context.has("verifiedBookingSource")){
                                    var preview=inbox.bookingPreview(http.getParameter("sourceRequest"),http.getParameter("sourceVersion"));
                                    meta+="<meta name='adc-booking-source-preview' content='"+java.util.Base64.getEncoder().encodeToString(preview.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))+"'>";
                                }
                            }catch(SecurityException denied){out.sendError(403);return;}
                            catch(IllegalArgumentException unavailable){out.sendError(409);return;}
                        }
                    }
                    meta += "<meta name='adc-booking-interaction-enabled' content='true'>";
                    meta += "<meta name='adc-clinic-zone' content='" + clinic(81001).zone().getId() + "'>";
                    if(Set.of("01","05").contains(uc))meta+="<meta name='adc-mini-inbox-enabled' content='true'>";
                    if("01".equals(uc)&&http.getParameter("sourceRequest")!=null){
                        try{
                            var identity=CourseAppointmentRead.identity(http);
                            var inbox=new MiniRequestInboxService(tx,new StaffAuthorization(identity,bindings()::bindings),81001);
                            var preview=inbox.verificationPreview(http.getParameter("sourceRequest"),http.getParameter("sourceVersion"));
                            meta+="<meta name='adc-uc01-source-preview' content='"+java.util.Base64.getEncoder().encodeToString(preview.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))+"'>";
                        }catch(SecurityException denied){out.sendError(403);return;}
                        catch(IllegalArgumentException stale){out.sendError(409);return;}
                    }
                    out.getWriter().write(java.nio.file.Files.readString(bundle.resolve("index.html"))
                            .replace("</head>", meta + "</head>")
                            .replace("<body>", "<body><nav>" + links + " · <a href='/'>切换岗位</a></nav>"));
                } else if ("GET".equals(http.getMethod()) && "/api/v1/staff/mini-requests".equals(path)) {
                    var controller=new MiniRequestInboxController(req->{
                        var identity=CourseAppointmentRead.identity(req);
                        if(!identity.current().filter(RECEPTION::equals).isPresent())throw new SecurityException("INBOX_RECEPTION_REQUIRED");
                        return new MiniRequestInboxService(tx,new StaffAuthorization(identity,bindings()::bindings),81001);
                    });
                    var result=controller.list(http,http.getParameter("cursor"));
                    out.setStatus(result.getStatusCode().value());out.setHeader("Cache-Control","no-store");
                    out.setContentType("application/json;charset=UTF-8");out.getWriter().write(result.getBody().toString());
                } else if ("GET".equals(http.getMethod()) && "/api/v1/staff/my-schedule".equals(path)) {
                    Long cursor;
                    try { cursor = http.getParameter("cursor") == null ? null : Long.valueOf(http.getParameter("cursor")); }
                    catch (NumberFormatException invalid) { out.sendError(400); return; }
                    var result = new CourseDentistSchedule(tx, 81001, 79001).read(http, cursor,"true".equals(http.getParameter("includeAppointments")));
                    out.setStatus(result.getStatusCode().value());
                    out.setHeader("Cache-Control", "no-store");
                    out.setContentType("application/json;charset=UTF-8");
                    out.getWriter().write(result.getBody().toString());
                } else if (path.matches("/api/v1/staff/appointments(?:/[1-9][0-9]{0,17}(?:/schedule-support|/arrival-verifications|/no-show-verifications|/cancellations|/changes)?)?")) {
                    var identity = new StaffSessionIdentityAdapter(() -> {
                        var session = http.getSession(false);
                        var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                        return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
                    });
                    if (!identity.current().filter(RECEPTION::equals).isPresent()) {
                        out.sendError(403);
                        return;
                    }
                    var auth = new StaffAuthorization(identity, bindings()::bindings);
                    var reads = new AppointmentReadService(tx, auth, clock);
                    var arrival = new ArrivalNoShowService(tx, auth, new AppointmentRepository(),
                            (c, row, finding) -> finding.action() == ArrivalNoShowService.Action.NO_SHOW
                                    ? ("SYNTHETIC no-show verified".equals(finding.basis()) && finding.clinic() == 81001L
                                            ? Optional.of(new ArrivalNoShowService.Verified("SYNTHETIC no-show confirmed", null))
                                            : Optional.empty())
                                    : "SYNTHETIC human evidence".equals(finding.basis())
                                    && "SYNTHETIC patient verified".equals(finding.identityReference())
                                    && finding.clinic() == 81001L && Long.valueOf(79001L).equals(finding.actualDentist())
                                    ? Optional.of(new ArrivalNoShowService.Verified("SYNTHETIC verified", null))
                                    : Optional.empty(),
                            c -> IDS.incrementAndGet(), c -> IDS.incrementAndGet(), clock);
                    var cancellation = new AppointmentCancellationService(tx, auth,
                            (c, row, submitted) -> "SYNTHETIC cancellation verified".equals(submitted)
                                    ? Optional.of("SYNTHETIC patient cancellation") : Optional.empty(),
                            c -> IDS.incrementAndGet(), clock, new AppointmentRepository(),
                            new JdbcMiniCancellationSource(new CourseMiniExistingRequestContexts(MINI_ACCOUNT),clock));
                    var controller = new AppointmentController(booking(auth, http), reads, change(auth), cancellation, arrival);
                    AppointmentController.Response result;
                    var parts = path.split("/");
                    String appointment = parts.length > 5 ? parts[5] : null;
                    boolean noShowRequest = path.endsWith("/no-show-verifications");
                    boolean arrivalRequest = noShowRequest || path.endsWith("/arrival-verifications");
                    boolean cancelRequest = path.endsWith("/cancellations");
                    boolean changeRequest = path.endsWith("/changes");
                    if ("GET".equals(http.getMethod()) && appointment != null && !arrivalRequest && !cancelRequest && !changeRequest) {
                        result = path.endsWith("/schedule-support") ? controller.support(81001L, appointment)
                                : controller.detail(81001L, appointment);
                    } else if ("POST".equals(http.getMethod()) && (appointment == null || arrivalRequest || cancelRequest || changeRequest)) {
                        try { identity.requireCsrf(http.getHeader("X-CSRF-Token")); }
                        catch (SecurityException invalid) { out.sendError(403); return; }
                        var input = AuditDocuments.JSON.readTree(http.getInputStream());
                        var key = http.getHeader("Idempotency-Key");
                        result = appointment == null ? BookingInteractionController.serialized(http, () -> controller.create(81001L, key, input))
                                : changeRequest ? controller.change(Long.parseLong(appointment), 81001L, 81001L, key, input)
                                : cancelRequest ? controller.cancel(Long.parseLong(appointment), 81001L, key, input)
                                : controller.attendance(noShowRequest ? ArrivalNoShowService.Action.NO_SHOW : ArrivalNoShowService.Action.ARRIVAL, Long.parseLong(appointment),
                                        81001L, key, input);
                    } else { out.sendError(405); return; }
                    out.setStatus(result.status());
                    out.setContentType("application/json;charset=UTF-8");
                    out.setHeader("Cache-Control", "no-store");
                    out.getWriter().write(result.body().toString());
                } else if ("GET".equals(http.getMethod()) && path.matches("/assets/[A-Za-z0-9_-]+[.](js|css)")) {
                    var asset = bundle.resolve(path.substring(1));
                    if (!java.nio.file.Files.isRegularFile(asset)) { out.sendError(404); return; }
                    out.setContentType(path.endsWith(".js") ? "text/javascript" : "text/css");
                    java.nio.file.Files.copy(asset, out.getOutputStream());
                } else {
                    chain.doFilter(request, response);
                }
            };
        }

        @Bean BookingInteractionController.Access interactiveBookingAccess(){
            return new BookingInteractionController.Access(){
                public BookingInteractionController.Context open(HttpServletRequest request){
                    var identity=CourseAppointmentRead.identity(request);var auth=new StaffAuthorization(identity,bindings()::bindings);auth.require("UC02",Set.of(81001L));
                    return new BookingInteractionController.Context(new BookingProposalService(tx,auth,Runtime.this::clinic,Runtime.this::eligibility,CommittedBookingRelease::confirms,clock),auth,81001,classroomZone,stamp);
                }
                public JsonNode selected(HttpServletRequest request,String reference){var ref=CourseMiniBookingContext.parse(reference);var context=open(request);return new MiniRequestInboxService(tx,context.auth(),81001).bookingPreview(ref.requestId().toString(),Integer.toString(ref.requestVersion())).get("selectedScheme");}
                public void source(HttpServletRequest request,String reference,long patient){
                    var ref=CourseMiniBookingContext.parse(reference);var context=open(request);var selected=new MiniRequestInboxService(tx,context.auth(),81001).selection(ref.requestId().toString(),Integer.toString(ref.requestVersion()));
                    if(!"NEW".equals(selected.path("type").asText())||!selected.has("associatedPatientId")||(patient!=0&&!Long.toString(patient).equals(selected.path("associatedPatientId").asText())))throw new SecurityException("BOOKING_SOURCE_DENIED");
                }
            };
        }
        private FormalBookingService booking(StaffAuthorization auth) { return booking(auth,null); }
        private FormalBookingService booking(StaffAuthorization auth, HttpServletRequest request) {
            var scheme = classroomScheme("10:30:00", "10:57:00");
            var legacy = acceptance("SYNTHETIC-acceptance", scheme, true);
            var dynamic=request==null?null:BookingInteractionController.verifier(request);
            var verifier=dynamic==null?legacy:new BookingAcceptanceVerifier((c,ref)->ref.startsWith("SYNTHETIC-confirmation:")?BookingInteractionController.confirmations(request).findFor(CourseAppointmentRead.identity(request).current().orElseThrow(),ref):legacyConfirmation(c,ref,scheme), new BookingAcceptanceVerifier.ApplicantAuthority(){public boolean isPatient(java.sql.Connection c,String subject,long patient)throws java.sql.SQLException{return subject.equals("SYNTHETIC patient:"+patient)||((patient==88001&&subject.equals("SYNTHETIC-patient"))||(subject.equals("SYNTHETIC-classroom-new")&&associatedPatient(c)==patient));}public boolean mayAcceptBooking(java.sql.Connection c,String subject,InitialBookingReview.AcceptedScheme selected,String grant){return false;}},clock);
            var review = new InitialBookingReview(tx, auth, this::clinic, verifier);
            return new FormalBookingService(tx, auth, review, this::eligibility,
                    c -> IDS.incrementAndGet(), c -> IDS.incrementAndGet(), (c, plan, coordination, ref) -> "SYNTHETIC reminder",
                    CommittedBookingRelease::confirms, clock,new JdbcMiniBookingSource(new CourseMiniBookingContext(MINI_ACCOUNT),clock));
        }

        private Optional<BookingAcceptanceVerifier.Confirmation> legacyConfirmation(java.sql.Connection c,String reference,InitialBookingReview.AcceptedScheme scheme)throws java.sql.SQLException{
            if("SYNTHETIC-acceptance".equals(reference))return Optional.of(confirmation(reference,"SYNTHETIC-patient",scheme));
            if("SYNTHETIC-new-patient-acceptance".equals(reference)){long patient=associatedPatient(c);if(patient>0)return Optional.of(confirmation(reference,"SYNTHETIC-classroom-new",new InitialBookingReview.AcceptedScheme(patient,scheme.schedule(),scheme.clinicId(),scheme.dentistId(),scheme.startsAt(),scheme.endsAt(),scheme.treatmentRequest(),scheme.duration())));}
            return Optional.empty();
        }
        private AppointmentChangeService change(StaffAuthorization auth) {
            var scheme = classroomScheme("11:00:00", "11:27:00");
            return new AppointmentChangeService(tx, auth, this::clinic, this::eligibility,
                    acceptance("SYNTHETIC-change-acceptance", scheme, false), c -> IDS.incrementAndGet(),
                    c -> IDS.incrementAndGet(), (c, original, target) -> "SYNTHETIC changed reminder",
                    CommittedBookingRelease::confirms, clock,new AppointmentRepository(),new JdbcMiniChangeSource(new CourseMiniExistingRequestContexts(MINI_ACCOUNT),clock));
        }

        private cn.adc.shared.config.ClinicDirectoryProvider.Clinic clinic(long clinic) {
            return new cn.adc.shared.config.ClinicDirectoryProvider.Clinic(clinic, "SYNTHETIC", java.time.ZoneId.of(classroomZone), stamp);
        }

        private cn.adc.shared.config.DentistEligibilityProvider.Eligibility eligibility(long dentist, long clinic) {
            return new cn.adc.shared.config.DentistEligibilityProvider.Eligibility(dentist, clinic, "SYNTHETIC qualification", stamp);
        }

        private InitialBookingReview.AcceptedScheme classroomScheme(String start, String end) {
            return new InitialBookingReview.AcceptedScheme(88001L,
                    new cn.adc.shared.resource.ResourceLockCoordinator.Version(87001L, 2), 81001L, 79001L,
                    java.time.Instant.parse("2026-11-08T" + start + "Z"), java.time.Instant.parse("2026-11-08T" + end + "Z"),
                    "SYNTHETIC treatment", new cn.adc.duration.DurationPolicyService.Basis(85001L, 1, 27));
        }

        private BookingAcceptanceVerifier acceptance(String reference, InitialBookingReview.AcceptedScheme scheme,
                                                     boolean allowNewPatient) {
            return new BookingAcceptanceVerifier((c, ref) -> {
                if (reference.equals(ref)) return Optional.of(confirmation(ref, "SYNTHETIC-patient", scheme));
                if (allowNewPatient && "SYNTHETIC-new-patient-acceptance".equals(ref)) {
                    var patient = associatedPatient(c);
                    if (patient > 0) {
                        var accepted = new InitialBookingReview.AcceptedScheme(patient, scheme.schedule(), scheme.clinicId(),
                                scheme.dentistId(), scheme.startsAt(), scheme.endsAt(), scheme.treatmentRequest(), scheme.duration());
                        return Optional.of(confirmation(ref, "SYNTHETIC-classroom-new", accepted));
                    }
                }
                return Optional.empty();
            }, new BookingAcceptanceVerifier.ApplicantAuthority() {
                public boolean isPatient(java.sql.Connection c, String actor, long patient) throws java.sql.SQLException {
                    return (patient == 88001L && "SYNTHETIC-patient".equals(actor))
                            || (allowNewPatient && patient > 0 && "SYNTHETIC-classroom-new".equals(actor)
                                    && associatedPatient(c) == patient);
                }
                public boolean mayAcceptBooking(java.sql.Connection c, String actor,
                        InitialBookingReview.AcceptedScheme plan, String grant) { return false; }
            }, clock);
        }

        private BookingAcceptanceVerifier.Confirmation confirmation(String ref, String actor, InitialBookingReview.AcceptedScheme scheme) {
            return new BookingAcceptanceVerifier.Confirmation(ref, stamp, actor, null, stamp.verifiedAt(), null, scheme);
        }

        private static long associatedPatient(java.sql.Connection c) throws java.sql.SQLException {
            try (var statement = c.prepareStatement("""
                    SELECT associated_patient_id FROM adc_mini.preliminary_request
                    WHERE associated_patient_id IS NOT NULL AND
                      (request_id=? OR (applicant_account=?
                        AND submitted_content#>>'{identity,patientIdentification}'=?
                        AND submitted_content->>'intendedClinicId'='81001'))
                    """)) {
                statement.setObject(1, UUID.fromString(MINI_REQUEST));
                statement.setString(2, MINI_ACCOUNT);
                statement.setString(3, MINI_IDENTITY);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) return 0L;
                    long patient = rows.getLong(1);
                    return rows.next() ? 0L : patient;
                }
            }
        }

        @Bean MiniRequestSession miniSession() {
            var token = UUID.randomUUID().toString();
            return new MiniRequestSession(code -> "SYNTHETIC-code".equals(code) ? Optional.of(MINI_ACCOUNT) : Optional.empty(),
                    new MiniRequestSession.Sessions() {
                        public String issue(String account) { return token; }
                        public Optional<String> activeAccount(String submitted) {
                            return token.equals(submitted) ? Optional.of(MINI_ACCOUNT) : Optional.empty();
                        }
                    });
        }

        @Bean JdbcTransactions miniReadTransactions() { return tx; }
        @Bean MiniBookingOptionsController.Directory miniBookingDirectory(){return new MiniBookingOptionsController.Directory(){
            public ZoneId zone(long clinic){if(clinic!=81001&&clinic!=81002)throw new IllegalStateException("SYNTHETIC_CLINIC_REQUIRED");return ZoneId.of(classroomZone);}
            public boolean dentist(long clinic,long dentist){return (clinic==81001||clinic==81002)&&Set.of(79001L,79002L,79003L).contains(dentist);}
        };}

        @Bean MiniAppointmentReadController.PatientScope classroomMiniPatientScope() {
            // The classroom SELF grant is configured independently of the submitted request.
            return new MiniAppointmentReadController.PatientScope() {
                @Override public boolean mayRead(java.sql.Connection c, String account, long patient) throws java.sql.SQLException {
                    return patient > 0 && verifiedPatient(c, account) == patient;
                }

                @Override public List<MiniAppointmentReadController.Authorization> authorizations(
                        java.sql.Connection c, String account) throws java.sql.SQLException {
                    long patient = verifiedPatient(c, account);
                    return patient == 0L ? List.of() : List.of(new MiniAppointmentReadController.Authorization(
                            "SYNTHETIC-classroom-self", patient, MiniAppointmentReadController.Authorization.Kind.SELF,
                            List.of("APPOINTMENT_READ", MiniAppointmentReadController.VISIT_HISTORY_READ,
                                    MiniPatientProfileController.PERMISSION), 1));
                }

                private long verifiedPatient(java.sql.Connection c, String account) throws java.sql.SQLException {
                    if (!MINI_ACCOUNT.equals(account)) return 0L;
                    try (var lookup = c.prepareStatement("SELECT patient_id FROM adc.patient WHERE identity_information=?")) {
                        lookup.setString(1, MINI_IDENTITY);
                        try (var rows = lookup.executeQuery()) {
                            if (!rows.next()) return 0L;
                            long patient = rows.getLong(1);
                            return rows.next() ? 0L : patient;
                        }
                    }
                }
            };
        }

        @Bean MiniNewRequestIntake miniIntake(MiniRequestSession session,MiniBookingOptionsController options) {
            return new MiniNewRequestIntake(session, new JdbcMiniRequestIntakeStore(tx, clock),options::requireAvailable);
        }

        private static boolean verifiedMiniSource(java.sql.Connection c, JsonNode source,String identity) throws java.sql.SQLException {
            var request = UUID.fromString(source.path("requestId").asText());
            if (MINI_REQUEST.equals(request.toString())) return MINI_IDENTITY.equals(identity);
            try (var p = c.prepareStatement("""
                    SELECT 1 FROM adc_mini.preliminary_request
                    WHERE request_id=? AND applicant_account=?
                      AND submitted_content#>>'{identity,patientIdentification}'=?
                      AND submitted_content->>'intendedClinicId'='81001'
                    """)) {
                p.setObject(1, request);
                p.setString(2, MINI_ACCOUNT);
                p.setString(3, identity);
                try (var rows = p.executeQuery()) { return rows.next(); }
            }
        }

        @Bean PatientMaintenanceController.Access patientAccess() {
            return new PatientMaintenanceController.Access() {
                public JsonNode query(HttpServletRequest request, String csrf, JsonNode input) {
                    throw new SecurityException("CLASSROOM_PATIENT_CREATE_ONLY");
                }

                public cn.adc.shared.audit.CommandRecovery.Receipt save(HttpServletRequest request, String csrf,
                                                                       UUID key, Long patient, JsonNode input) {
                    if (!"true".equals(System.getenv("ADC_CLASSROOM_DEMO")) || patient != null) {
                        throw new SecurityException("CLASSROOM_PATIENT_CREATE_ONLY");
                    }
                    var identity = new StaffSessionIdentityAdapter(() -> {
                        var session = request.getSession(false);
                        var verified = session == null ? null : session.getAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE);
                        return verified instanceof StaffSessionIdentityAdapter.VerifiedSession value ? value : null;
                    });
                    identity.requireCsrf(csrf);
                    var auth = new StaffAuthorization(identity, bindings()::bindings);
                    var source = new JdbcMiniPatientSource((c, actor, clinic, id, document) -> {
                        if (!RECEPTION.equals(actor) || clinic != 81001
                                || !verifiedMiniSource(c, document.path("source"),AuditDocuments.text(document,"identityInformation"))
                                || document.path("source").path("identityMatchBasis").asText().isBlank()) {
                            throw new SecurityException("CLASSROOM_PATIENT_SOURCE_DENIED");
                        }
                    }, clock);
                    return new PatientMaintenanceService(tx, auth,
                            (c, actor, clinic, id, document) -> { throw new SecurityException("CLASSROOM_MINI_SOURCE_REQUIRED"); },
                            c -> IDS.incrementAndGet(), c -> IDS.incrementAndGet(), clock, source)
                            .save(81001L, null, key, input);
                }
            };
        }

        private long dentist(String actor, long clinic) {
            return DENTIST.equals(actor) && clinic == 81001 ? 79001 : 0;
        }

        @Bean VisitHealthController.Access healthAccess() {
            return new VisitHealthAccess(tx, bindings(), req -> 81001L, this::dentist,
                    c -> IDS.incrementAndGet(), c -> IDS.incrementAndGet(), clock);
        }

        @Bean VisitConsentController.Access consentAccess() {
            return new VisitConsentAccess(tx, bindings(), req -> 81001L, this::dentist,
                    c -> IDS.incrementAndGet(), c -> IDS.incrementAndGet(), clock);
        }

        @Bean VisitTreatmentController.Access treatmentAccess() {
            return new VisitTreatmentAccess(tx, bindings(), req -> 81001L, this::dentist,
                    c -> IDS.incrementAndGet(), c -> IDS.incrementAndGet(), clock);
        }

        @Bean TreatmentInvoiceController.Access invoiceAccess() {
            return new TreatmentInvoiceController.StaffAccess(tx, bindings(), req -> 81001L,
                    (c, target, fact) -> "SYNTHETIC treatment".equals(fact.item()) && "次".equals(fact.unit())
                            ? Optional.of(new TreatmentInvoiceService.Price(new BigDecimal("26.66"), "SYNTHETIC price v1", "classroom"))
                            : Optional.empty(),
                    c -> IDS.incrementAndGet(), c -> IDS.incrementAndGet(), c -> IDS.incrementAndGet(), clock);
        }

        @Bean InvoiceSettlementController.Access settlementAccess() {
            return new InvoiceSettlementController.StaffAccess(tx, bindings(), req -> 81001L,
                    c -> IDS.incrementAndGet(), c -> IDS.incrementAndGet(), clock);
        }

        @Bean InvoiceQuestionController.Access invoiceQuestionAccess() {
            return new InvoiceQuestionController.StaffAccess(tx, bindings(), req -> 81001L, this::dentist,
                    (c, target, fact) -> "SYNTHETIC treatment".equals(fact.item()) && "次".equals(fact.unit())
                            ? Optional.of(new TreatmentInvoiceService.Price(new BigDecimal("24.00"), "SYNTHETIC verified price v2", "classroom"))
                            : Optional.empty(), c -> IDS.incrementAndGet(), c -> IDS.incrementAndGet(), clock);
        }
    }

    @Test void realHttpPersistsClinicalAndBillingChainAcrossApplicationRestart() throws Exception {
        var invoiceKey = UUID.randomUUID();
        var verifyKey = UUID.randomUUID();
        String invoiceId, paymentId, clinicalId, verification;
        int version;
        try (var server = start()) {
            var consent = post(server, "dentist", "/consent", UUID.randomUUID(), """
                    {"patientId":"88001","communicationParty":"SYNTHETIC patient","guardianRequired":false,
                     "guardianParticipation":null,"proposedTreatment":"SYNTHETIC treatment","consentResult":"同意","comments":null}
                    """);
            String consentId = consent.path("consentResultId").asText();
            assertFalse(consentId.isBlank());
            var treatment = post(server, "dentist", "/treatment", UUID.randomUUID(), """
                    {"patientId":"88001","consentResultId":"%s","performed":true,"project":"SYNTHETIC treatment",
                     "unit":"次","quantity":"1.250","specialRequirements":null,"nonPerformanceReason":null}
                    """.formatted(consentId));
            clinicalId = treatment.path("clinicalRecordId").asText();
            var invoice = post(server, "billing", "/invoice", invoiceKey, "{}").path("invoice");
            invoiceId = invoice.path("invoiceId").asText();
            version = invoice.path("invoiceVersionNo").asInt();
            assertEquals(clinicalId, invoice.path("lines").get(0).path("clinicalRecordId").asText());
            assertEquals("33.33", invoice.path("total").asText());
            var payment = post(server, "billing", "/demo-settlement", UUID.randomUUID(), """
                    {"invoiceId":"%s","invoiceVersionNo":%d,"operation":"DEMO_SETTLEMENT_START","paymentId":null,
                     "confirmation":{"applicant":"SYNTHETIC patient","amount":"33.33","basis":"SYNTHETIC confirmed"}}
                    """.formatted(invoiceId, version)).path("payment");
            assertEquals("待核验", payment.path("status").asText());
            paymentId = payment.path("paymentId").asText();
            verification = """
                    {"invoiceId":"%s","invoiceVersionNo":%d,"operation":"DEMO_SETTLEMENT_VERIFY","paymentId":"%s","confirmation":null}
                    """.formatted(invoiceId, version, paymentId);
            assertEquals("已结算", post(server, "billing", "/demo-settlement", verifyKey, verification)
                    .path("payment").path("status").asText());
        }
        try (var restarted = start()) {
            var invoice = post(restarted, "billing", "/invoice", UUID.randomUUID(), "{}").path("invoice");
            assertEquals(invoiceId, invoice.path("invoiceId").asText());
            assertEquals(version, invoice.path("invoiceVersionNo").asInt());
            assertEquals("已结算", invoice.path("status").asText());
            assertEquals(clinicalId, invoice.path("lines").get(0).path("clinicalRecordId").asText());
            var recovered = post(restarted, "billing", "/demo-settlement", verifyKey, verification).path("payment");
            assertEquals(paymentId, recovered.path("paymentId").asText());
            assertEquals("33.33", recovered.path("amount").asText());
            assertEquals("SYNTHETIC-UC12-" + paymentId, recovered.path("receipt").asText());
        }
        try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
             var st = c.prepareStatement("""
                     SELECT count(*), sum(received_amount) FROM adc.payment_transaction WHERE invoice_id=? AND invoice_version_no=?
                     """)) {
            st.setLong(1, Long.parseLong(invoiceId));
            st.setInt(2, version);
            try (var rows = st.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals(new BigDecimal("33.33"), rows.getBigDecimal(2));
            }
            try (var audit = c.prepareStatement("""
                    SELECT uc_code, count(*) FROM adc.operation_audit_event
                    WHERE outcome='SUCCESS' AND uc_code IN ('UC08','UC09','UC10','UC12')
                    GROUP BY uc_code ORDER BY uc_code
                    """); var rows = audit.executeQuery()) {
                for (var count : List.of(1, 1, 2, 2)) {
                    assertTrue(rows.next());
                    assertEquals(count, rows.getInt(2), rows.getString(1));
                }
                assertFalse(rows.next());
            }
        }
    }

    @Test void staffBrowserWritesThroughTheRunningBackend() throws Exception {
        var evidence = java.nio.file.Path.of(System.getenv("ADC_T028_EVIDENCE"));
        var staff = java.nio.file.Path.of("../apps/staff-web").toRealPath();
        boolean cancellationScenario = "true".equals(System.getenv("ADC_CLASSROOM_CANCEL"));
        boolean patientScenario = "true".equals(System.getenv("ADC_CLASSROOM_PATIENT"));
        boolean changeScenario = "true".equals(System.getenv("ADC_CLASSROOM_CHANGE"));
        boolean stockScenario = "true".equals(System.getenv("ADC_CLASSROOM_STOCK_BROWSER"));
        boolean quarterScenario = "true".equals(System.getenv("ADC_CLASSROOM_QUARTER_BROWSER"));
        boolean scheduleScenario = "true".equals(System.getenv("ADC_CLASSROOM_SCHEDULE_BROWSER"));
        boolean noShowScenario = "true".equals(System.getenv("ADC_CLASSROOM_NO_SHOW_BROWSER"));
        boolean invoiceQuestionScenario = "true".equals(System.getenv("ADC_CLASSROOM_INVOICE_QUESTION_BROWSER"));
        try (var server = start()) {
            if (quarterScenario) createQuarterBillingFacts(server);
            String questionedInvoice = null;
            if (invoiceQuestionScenario) {
                var base = URI.create("http://127.0.0.1:" + server.getWebServer().getPort());
                try (var client = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build()) {
                    questionedInvoice = createUnsettledInvoice(client, base).path("invoiceId").asText();
                }
            }
            var miniRequest = "true".equals(System.getenv("ADC_CLASSROOM_MINI"))
                    ? submitMiniRequest(server) : MINI_REQUEST;
            var browser = new ProcessBuilder(System.getenv("ADC_CLASSROOM_NODE"),
                    "node_modules/@playwright/test/cli.js", "test", "--config=playwright.appointments.config.js",
                    "--grep", invoiceQuestionScenario ? "classroom live INVOICE-QUESTION" : noShowScenario ? "classroom live NO-SHOW"
                            : scheduleScenario ? "classroom live SCHEDULE" : quarterScenario ? "classroom live QUARTER" : stockScenario ? "classroom live STOCK"
                            : cancellationScenario ? "classroom live CANCEL" : "classroom live VISIT", "--reporter=list,junit",
                    "--output=" + evidence.resolve("browser-results"))
                    .directory(staff.toFile()).redirectErrorStream(true)
                    .redirectOutput(evidence.resolve("browser.log").toFile());
            browser.environment().remove("ADC_T028_RUNTIME");
            browser.environment().remove("ADC_T028_PUBLISHER");
            browser.environment().put("ADC_APPOINTMENT_TEST_BUNDLE", evidence.resolve("staff-bundle").toString());
            browser.environment().put("ADC_CLASSROOM_REQUEST_ID", miniRequest);
            browser.environment().put("ADC_CLASSROOM_MINI_READ", System.getenv("ADC_CLASSROOM_MINI"));
            browser.environment().put("ADC_CLASSROOM_PATIENT", Boolean.toString(patientScenario));
            browser.environment().put("ADC_CLASSROOM_CHANGE", Boolean.toString(changeScenario));
            browser.environment().put("ADC_CLASSROOM_FULL_CHAIN", System.getenv("ADC_CLASSROOM_DEMO"));
            browser.environment().put("ADC_CLASSROOM_HTTP", "http://127.0.0.1:" + server.getWebServer().getPort());
            if (questionedInvoice != null) browser.environment().put("ADC_CLASSROOM_QUESTION_INVOICE_ID", questionedInvoice);
            DEMO_PASSWORDS.forEach((account, password) -> browser.environment().put(
                    "ADC_DEMO_" + account.toUpperCase(java.util.Locale.ROOT), password));
            browser.environment().put("PLAYWRIGHT_JUNIT_OUTPUT_FILE", evidence.resolve("browser.xml").toString());
            var process = browser.start();
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                fail("Edge timed out. See browser.log");
            }
            assertEquals(0, process.exitValue(), "See browser.log");
            if (invoiceQuestionScenario) {
                try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
                     var sql = c.prepareStatement("""
                             SELECT v.invoice_version_no, v.total_amount, v.is_current, v.confirmed_at, i.invoice_status
                             FROM adc.treatment_invoice_version v JOIN adc.treatment_invoice i USING(invoice_id)
                             WHERE v.invoice_id=? ORDER BY v.invoice_version_no
                             """)) {
                    sql.setLong(1, Long.parseLong(questionedInvoice));
                    try (var row = sql.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals(1, row.getInt(1));
                        assertEquals(new BigDecimal("33.33"), row.getBigDecimal(2));
                        assertFalse(row.getBoolean(3));
                        assertTrue(row.next());
                        assertEquals(2, row.getInt(1));
                        assertEquals(new BigDecimal("30.00"), row.getBigDecimal(2));
                        assertTrue(row.getBoolean(3));
                        assertNotNull(row.getObject(4));
                        assertEquals("待结算", row.getString(5));
                        assertFalse(row.next());
                    }
                }
                return;
            }
            if (noShowScenario) {
                try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
                     var sql = c.createStatement();
                     var row = sql.executeQuery("""
                             SELECT appointment_status, no_show_verification,
                                    (SELECT count(*) FROM adc.visit WHERE appointment_id=904),
                                    (SELECT count(*) FROM adc.operation_audit_event
                                     WHERE uc_code='UC06' AND outcome='SUCCESS')
                             FROM adc.appointment WHERE appointment_id=904
                             """)) {
                    assertTrue(row.next());
                    assertEquals("已确认未到诊", row.getString(1));
                    assertTrue(row.getString(2).contains("SYNTHETIC no-show confirmed"));
                    assertEquals(0, row.getInt(3));
                    assertEquals(1, row.getInt(4));
                }
                return;
            }
            if (scheduleScenario) {
                try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
                     var sql = c.createStatement();
                     var row = sql.executeQuery("""
                             SELECT (SELECT count(*) FROM adc.reception_schedule_version WHERE schedule_id=88000
                                       AND schedule_version_no=1 AND activation_status='EFFECTIVE'),
                                    (SELECT count(*) FROM adc.reception_schedule_version WHERE schedule_id=87001
                                       AND schedule_version_no=2 AND activation_status='EFFECTIVE'),
                                    (SELECT count(*) FROM adc.operation_audit_event WHERE outcome='SUCCESS'
                                       AND uc_code IN ('UC21','UC22'))
                             """)) {
                    assertTrue(row.next());
                    assertEquals(1, row.getInt(1));
                    assertEquals(1, row.getInt(2));
                    assertEquals(2, row.getInt(3));
                }
                return;
            }
            if (quarterScenario) {
                try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
                     var sql = c.createStatement();
                     var row = sql.executeQuery("""
                             SELECT old.operating_expense, fresh.operating_expense, i.original_data,
                                    h.corrected_value, h.correction_status,
                                    fresh.source_basis::jsonb -> 'appliedCorrections' @>
                                      jsonb_build_array(jsonb_build_object('historyId',h.correction_history_id,'factId','expense-1')),
                                    (SELECT (fact->>'sourceEvidence')::jsonb FROM
                                      jsonb_array_elements(fresh.source_basis::jsonb -> 'facts') fact
                                      WHERE fact->>'metric'='EXPENSE' AND fact->>'id'='expense-1')
                             FROM adc.quarterly_issue_correction_history h
                             JOIN adc.quarterly_data_issue i ON i.issue_id=h.issue_id
                             JOIN adc.quarterly_operating_statistic old ON old.statistic_id=i.statistic_id
                             JOIN adc.quarterly_operating_statistic fresh ON fresh.statistic_id=h.resulting_statistic_id
                             WHERE i.issue_id=18002 AND old.statistic_id=18001
                             """)) {
                    assertTrue(row.next());
                    assertNull(row.getBigDecimal(1));
                    assertEquals(new BigDecimal("8.50"), row.getBigDecimal(2));
                    assertEquals("SYNTHETIC source amount missing", row.getString(3));
                    assertEquals("8.50", row.getString(4));
                    assertEquals("复核通过", row.getString(5));
                    assertTrue(row.getBoolean(6));
                    var expenseEvidence = AuditDocuments.parse(row.getString(7));
                    assertEquals("classroom-expenses-2026Q4-v1", expenseEvidence.path("manifestVersion").asText());
                    assertEquals("SYNTHETIC clinic utilities", expenseEvidence.path("category").asText());
                    assertEquals("SYNTHETIC classroom verified expense statement", expenseEvidence.path("verifiedBasis").asText());
                    assertEquals("9.00", expenseEvidence.path("originalAmount").asText());
                    assertFalse(row.next());
                }
                try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
                     var sql = c.createStatement();
                     var row = sql.executeQuery("""
                             SELECT count(*), count(DISTINCT operation_name) FROM adc.operation_audit_event
                             WHERE outcome='SUCCESS' AND uc_code IN ('UC17','UC19','UC20')
                             """)) {
                    assertTrue(row.next());
                    assertEquals(4, row.getInt(1));
                    assertEquals(4, row.getInt(2));
                }
                try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
                     var sql = c.createStatement();
                     var row = sql.executeQuery("""
                             SELECT q.consumption_cost, x.consumption_cost
                             FROM adc.quarterly_operating_statistic q
                             CROSS JOIN LATERAL jsonb_array_elements(q.source_basis::jsonb -> 'facts') fact
                             JOIN adc.supply_consumption x ON fact->>'id'='supply_consumption/' || x.consumption_id::text
                             WHERE q.source_data_version='SYNTHETIC-classroom-v7'
                               AND fact->>'metric'='CONSUMPTION' AND fact->>'decision'='纳入'
                             """)) {
                    assertTrue(row.next());
                    assertEquals(new BigDecimal("5.75"), row.getBigDecimal(1));
                    assertEquals(row.getBigDecimal(2), row.getBigDecimal(1));
                    assertFalse(row.next());
                }
                try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
                     var sql = c.createStatement();
                     var row = sql.executeQuery("""
                             SELECT q.receivable_amount, q.received_amount, b.total_amount, p.received_amount
                             FROM adc.quarterly_operating_statistic q
                             CROSS JOIN LATERAL jsonb_array_elements(q.source_basis::jsonb -> 'facts') bill
                             CROSS JOIN LATERAL jsonb_array_elements(q.source_basis::jsonb -> 'facts') payment
                             JOIN adc.payment_transaction p ON payment->>'id'='payment_transaction/' || p.payment_id::text
                             JOIN adc.treatment_invoice_version b ON b.invoice_id=p.invoice_id
                                 AND b.invoice_version_no=p.invoice_version_no
                             WHERE q.source_data_version='SYNTHETIC-classroom-v7'
                               AND bill->>'id'='treatment_invoice/' || b.invoice_id::text
                               AND bill->>'version'=b.invoice_version_no::text
                               AND payment->>'version'=p.invoice_version_no::text
                               AND bill->>'decision'='纳入' AND payment->>'decision'='纳入'
                             """)) {
                    assertTrue(row.next());
                    assertEquals(new BigDecimal("33.33"), row.getBigDecimal(1));
                    assertEquals(row.getBigDecimal(3), row.getBigDecimal(1));
                    assertEquals(row.getBigDecimal(4), row.getBigDecimal(2));
                    assertFalse(row.next());
                }
                try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
                     var sql = c.createStatement();
                     var row = sql.executeQuery("""
                             SELECT due_appointment_count, confirmed_no_show_count, cancelled_appointment_count,
                                    source_basis::jsonb -> 'facts' @> '[{"id":"appointment/904","decision":"单列取消"}]',
                                    source_basis::jsonb -> 'facts' @> '[{"id":"appointment/905","decision":"纳入应到诊"}]',
                                    source_basis::jsonb -> 'facts' @> '[{"id":"appointment/901","decision":"纳入应到诊"}]',
                                    source_basis::jsonb -> 'facts' @> '[{"id":"appointment/902"}]'
                             FROM adc.quarterly_operating_statistic WHERE source_data_version='SYNTHETIC-classroom-v7'
                             """)) {
                    assertTrue(row.next());
                    assertEquals(2, row.getInt(1));
                    assertEquals(0, row.getInt(2));
                    assertEquals(1, row.getInt(3));
                    assertTrue(row.getBoolean(4));
                    assertTrue(row.getBoolean(5));
                    assertTrue(row.getBoolean(6));
                    assertFalse(row.getBoolean(7));
                    assertFalse(row.next());
                }
                try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
                     var sql = c.createStatement();
                     var row = sql.executeQuery("""
                             SELECT p.performance_amount, item.line_amount, p.dentist_id, v.actual_dentist_id
                             FROM adc.quarterly_operating_statistic q
                             JOIN adc.quarterly_dentist_performance_stat p ON p.statistic_id=q.statistic_id
                             CROSS JOIN LATERAL jsonb_array_elements(q.source_basis::jsonb -> 'facts') fact
                             JOIN adc.treatment_invoice_item item ON fact->>'id'='treatment_invoice_item/' || item.invoice_item_id::text
                             JOIN adc.treatment_invoice i ON i.invoice_id=item.invoice_id
                             JOIN adc.visit v ON v.visit_id=i.visit_id
                             WHERE q.source_data_version='SYNTHETIC-classroom-v7'
                               AND fact->>'version'=item.invoice_version_no::text
                               AND fact->>'metric'='PERFORMANCE' AND fact->>'decision'='纳入'
                             """)) {
                    assertTrue(row.next());
                    assertEquals(new BigDecimal("33.33"), row.getBigDecimal(1));
                    assertEquals(row.getBigDecimal(2), row.getBigDecimal(1));
                    assertEquals(row.getLong(4), row.getLong(3));
                    assertFalse(row.next());
                }
                try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
                     var sql = c.createStatement();
                     var rows = sql.executeQuery("""
                             SELECT tablename FROM pg_catalog.pg_tables
                             WHERE schemaname='adc' AND tablename NOT IN
                                ('project_duration_version','extension_operation_audit_event')
                             ORDER BY tablename
                             """)) {
                    var names = new java.util.ArrayList<String>();
                    while (rows.next()) names.add(rows.getString(1));
                    assertEquals(31, names.size(), names.toString());
                    java.nio.file.Files.write(evidence.resolve("classroom-original-tables.txt"), names);
                }
                return;
            }
            if (stockScenario) {
                try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
                     var query = c.createStatement();
                     var row = query.executeQuery("""
                             SELECT s.on_hand_quantity, r.purchase_requirement_id,
                                    (SELECT count(*) FROM adc.operation_audit_event
                                     WHERE uc_code IN ('UC13','UC14','UC15','UC16') AND outcome='SUCCESS')
                             FROM adc.medical_supply_stock s
                             JOIN adc.supply_receipt r ON r.stock_id=s.stock_id
                             JOIN adc.supply_consumption x ON x.stock_id=s.stock_id
                             WHERE s.clinic_id=81001 AND s.supply_id=101
                             """)) {
                    assertTrue(row.next());
                    assertEquals(new BigDecimal("7.500"), row.getBigDecimal(1));
                    assertEquals(801L, row.getLong(2));
                    assertEquals(4, row.getInt(3));
                    assertFalse(row.next());
                }
                return;
            }
            if (patientScenario) {
                assertPatientBooking(miniRequest, server);
                return;
            }
            if (changeScenario) {
                assertChangedAppointment();
                return;
            }
            if (cancellationScenario) {
                assertCancelledAppointment();
                return;
            }
            try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
                 var st = c.createStatement();
                 var rows = st.executeQuery("""
                         SELECT count(*), sum(p.received_amount)
                         FROM adc.payment_transaction p JOIN adc.treatment_invoice i USING (invoice_id)
                         JOIN adc.visit v USING (visit_id)
                         JOIN adc.appointment a USING (appointment_id)
                         WHERE i.invoice_status='已结算' AND p.local_settlement_status='已结算'
                           AND %s
                         """.formatted("true".equals(System.getenv("ADC_CLASSROOM_DEMO"))
                                 ? "v.visit_id<>42 AND a.appointment_id>71000 AND a.appointment_status='已到诊' AND EXISTS (SELECT 1 FROM adc.patient_health_change h WHERE h.visit_id=v.visit_id AND h.allergy_information='SYNTHETIC penicillin allergy')"
                                 : "v.visit_id=42"))) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals(new BigDecimal("33.33"), rows.getBigDecimal(2));
            }
        }
    }

    private static String submitMiniRequest(ServletWebServerApplicationContext server) throws Exception {
        var base = URI.create("http://127.0.0.1:" + server.getWebServer().getPort());
        try (var client = HttpClient.newHttpClient()) {
            var login = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/mini/sessions/wechat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"SYNTHETIC-code\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode(), login.body());
            var token = AuditDocuments.parse(login.body()).path("accessToken").asText();
            var command = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/mini/requests"))
                    .header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"type":"NEW","intendedClinicId":"81001","actingMode":"SELF",
                             "identity":{"patientIdentification":"SYNTHETIC classroom new patient",
                                         "applicantInformation":"SYNTHETIC new patient","contactInformation":"classroom@example.invalid"},
                             "preferred":{"date":"2026-11-08"},"treatmentNeed":"SYNTHETIC treatment",
                             "bookingIntent":{"visitType":"INITIAL"}}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, command.statusCode(), command.body());
            var receipt = AuditDocuments.parse(command.body());
            assertEquals("CONFIRMED_RECEIVED", receipt.path("receiptStatus").asText());
            var requestId = receipt.path("requestId").asText();
            assertNotEquals(MINI_REQUEST, UUID.fromString(requestId).toString());
            return requestId;
        }
    }

    private static void assertPatientBooking(String requestId, ServletWebServerApplicationContext server) throws Exception {
        String patientId, visitId, project;
        try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
             var st = c.prepareStatement("""
                     SELECT p.patient_id, v.visit_id, cv.performed_treatment FROM adc_mini.preliminary_request source
                     JOIN adc.patient p ON p.patient_id=source.associated_patient_id
                     JOIN adc.appointment a ON a.patient_id=p.patient_id
                     JOIN adc.visit v ON v.appointment_id=a.appointment_id
                     JOIN adc.patient_health_change h ON h.visit_id=v.visit_id
                     JOIN adc.treatment_consent_result consent ON consent.visit_id=v.visit_id
                     JOIN adc.clinical_record cr ON cr.visit_id=v.visit_id
                     JOIN adc.clinical_record_version cv ON cv.clinical_record_id=cr.clinical_record_id
                          AND cv.consent_result_id=consent.consent_result_id
                     JOIN adc.treatment_invoice bill ON bill.visit_id=v.visit_id
                     JOIN adc.treatment_invoice_item item ON item.invoice_id=bill.invoice_id
                          AND item.clinical_record_id=cv.clinical_record_id
                          AND item.record_version_no=cv.record_version_no
                     JOIN adc.payment_transaction pay ON pay.invoice_id=bill.invoice_id
                          AND pay.invoice_version_no=item.invoice_version_no
                     JOIN adc_mini.operation_audit_event e ON e.request_id=source.request_id AND e.operation='UC01_ASSOCIATED'
                     WHERE source.request_id=? AND source.request_version=1 AND a.appointment_status='已到诊'
                       AND h.allergy_information='SYNTHETIC penicillin allergy'
                       AND bill.invoice_status='已结算' AND pay.local_settlement_status='已结算'
                       AND pay.received_amount=33.33
                       AND p.identity_information='SYNTHETIC classroom new patient'
                     """)) {
            st.setObject(1, UUID.fromString(requestId));
            try (var rows = st.executeQuery()) {
                assertTrue(rows.next());
                patientId = rows.getString(1);
                visitId = rows.getString(2);
                project = rows.getString(3);
                assertFalse(rows.next());
            }
        }
        var base = URI.create("http://127.0.0.1:" + server.getWebServer().getPort());
        try (var client = HttpClient.newHttpClient()) {
            var login = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/mini/sessions/wechat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"SYNTHETIC-code\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode(), login.body());
            var bearer = "Bearer " + AuditDocuments.parse(login.body()).path("accessToken").asText();
            var history = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/mini/visit-history?patientId=" + patientId))
                    .header("Authorization", bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, history.statusCode(), history.body());
            var items = AuditDocuments.parse(history.body()).path("items");
            assertEquals(1, items.size());
            var visit = items.get(0);
            assertEquals(visitId, visit.path("visitId").asText());
            assertEquals(patientId, visit.path("patientId").asText());
            assertEquals("81001", visit.path("clinicId").asText());
            assertFalse(visit.path("registeredAt").asText().isBlank());
            assertEquals(project, visit.path("performedProjects").get(0).asText());
            var denied = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/mini/visit-history?patientId=88001"))
                    .header("Authorization", bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode(), denied.body());
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("ADC_T028_EVIDENCE"),
                    "mini-visit-history-response.json"), history.body());
            if ("true".equals(System.getenv("ADC_CLASSROOM_HISTORY_NATIVE"))) {
                var evidence = java.nio.file.Path.of(System.getenv("ADC_T028_EVIDENCE"));
                java.nio.file.Files.writeString(evidence.resolve("mini-visit-history-native-ready.txt"),
                        base + System.lineSeparator() + visitId + System.lineSeparator() + project);
                var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
                while (System.nanoTime() < deadline && !java.nio.file.Files.exists(evidence.resolve("classroom-stop"))) {
                    Thread.sleep(500);
                }
            }
        }
    }

    private static void assertChangedAppointment() throws Exception {
        try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
             var st = c.createStatement();
             var rows = st.executeQuery("""
                     SELECT count(*) FROM adc.appointment_change_history h
                     JOIN adc.appointment a USING (appointment_id)
                     WHERE a.appointment_id>71000 AND a.starts_at=h.new_starts_at
                       AND h.old_starts_at='2026-11-08T10:30:00Z' AND h.new_starts_at='2026-11-08T11:00:00Z'
                       AND h.old_schedule_id=87001 AND h.new_schedule_id=87001
                       AND h.old_schedule_version_no=2 AND h.new_schedule_version_no=2
                     """)) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    private static void assertCancelledAppointment() throws Exception {
        try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
             var st = c.createStatement();
             var rows = st.executeQuery("""
                     SELECT a.appointment_status, a.reminder_arrangement::jsonb ->> 'state',
                            (SELECT count(*) FROM adc.visit WHERE appointment_id=904),
                            (SELECT count(*) FROM adc.operation_audit_event
                             WHERE uc_code='UC04' AND outcome='SUCCESS'
                               AND business_reference::jsonb ->> 'appointmentId'='904')
                     FROM adc.appointment a WHERE a.appointment_id=904
                     """)) {
            assertTrue(rows.next());
            assertEquals("已取消", rows.getString(1));
            assertEquals("STOPPED", rows.getString(2));
            assertEquals(0, rows.getInt(3));
            assertEquals(1, rows.getInt(4));
        }
    }

    @Test void receptionCalendarAndPersonalDoctorReadSamePersistedBookings()throws Exception {
      try(var server=start();var client=HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build()){
        var base=URI.create("http://127.0.0.1:"+server.getWebServer().getPort());var before=effectiveSnapshot();
        demoRole(client,base,"reception");
        var response=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/booking-calendar?date=2026-11-08")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());
        var calendar=AuditDocuments.parse(response.body());assertEquals("COMPLETE",calendar.path("coverage").asText());assertFalse(calendar.path("reservesCapacity").asBoolean(true));
        JsonNode local=null,cross=null;for(var item:calendar.path("busy")){if("901".equals(item.path("appointmentId").asText()))local=item;if("跨店占用".equals(item.path("statusLabel").asText()))cross=item;}
        assertNotNull(local);assertNotNull(cross);assertFalse(cross.has("appointmentId"));assertFalse(cross.has("clinicId"));assertFalse(calendar.toString().contains("patientId"));
        demoRole(client,base,"dentist");
        var doctor=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/my-schedule?includeAppointments=true")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,doctor.statusCode(),doctor.body());
        var personal=AuditDocuments.parse(doctor.body());assertEquals("79001",personal.path("dentistId").asText());boolean matched=false;
        for(var window:personal.path("items"))for(var item:window.path("appointments"))if("901".equals(item.path("appointmentId").asText())){assertEquals(OffsetDateTime.parse(local.path("startsAt").asText()).toInstant(),OffsetDateTime.parse(item.path("startsAt").asText()).toInstant());assertEquals(OffsetDateTime.parse(local.path("endsAt").asText()).toInstant(),OffsetDateTime.parse(item.path("endsAt").asText()).toInstant());matched=true;}
        assertTrue(matched);var denied=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/booking-calendar?date=2026-11-08")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,denied.statusCode());assertEquals(before,effectiveSnapshot());
        var evidence=java.nio.file.Path.of(System.getenv("ADC_T028_EVIDENCE"));java.nio.file.Files.writeString(evidence.resolve("reception-calendar.json"),calendar.toString());java.nio.file.Files.writeString(evidence.resolve("doctor-calendar.json"),personal.toString());
      }
    }

    @Test void humanReadablePatientIntakeUsesOriginalIdentityWithoutMagicStrings()throws Exception {
      try(var server=start();var client=HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build()){
        var base=URI.create("http://127.0.0.1:"+server.getWebServer().getPort());
        var login=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/mini/sessions/wechat")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"SYNTHETIC-code\"}")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,login.statusCode());
        String bearer="Bearer "+AuditDocuments.parse(login.body()).path("accessToken").asText();
        var request=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/mini/requests")).header("Authorization",bearer).header("Idempotency-Key",UUID.randomUUID().toString()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("""
          {"type":"NEW","actingMode":"SELF","identity":{"patientIdentification":"li","applicantInformation":"li","contactInformation":"3456789"},"intendedClinicId":"81001","preferred":{"date":"2026-11-21"},"treatmentNeed":"SYNTHETIC 检查","bookingIntent":{"visitType":"INITIAL"}}
          """)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(201,request.statusCode(),request.body());String id=AuditDocuments.parse(request.body()).path("requestId").asText();
        demoRole(client,base,"reception");
        var input=AuditDocuments.JSON.createObjectNode().put("patientName","演示患者小李").put("identityInformation","li").put("contactInformation","3456789");input.putObject("source").put("kind","MINI_REQUEST").put("requestId",id).put("expectedRequestVersion",0).put("identityMatchBasis","课程模拟：已核对本次申请人与就诊人资料，确认是本人预约");
        var bad=input.deepCopy();bad.put("identityInformation","another-person");
        var denied=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/patients")).header("X-CSRF-Token","SYNTHETIC-CSRF").header("Idempotency-Key",UUID.randomUUID().toString()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(bad.toString())).build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,denied.statusCode(),denied.body());
        var saved=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/patients")).header("X-CSRF-Token","SYNTHETIC-CSRF").header("Idempotency-Key",UUID.randomUUID().toString()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(),HttpResponse.BodyHandlers.ofString());assertEquals(201,saved.statusCode(),saved.body());
        var body=AuditDocuments.parse(saved.body());assertFalse(body.path("sourceAssociation").path("patientAccessGrantedByAssociation").asBoolean(true));
        try(var c=new T028IsolatedPgIT.IsolatedData("runtime",0).getConnection();var p=c.prepareStatement("SELECT identity_information,patient_name,contact_information FROM adc.patient WHERE patient_id=?")){
          p.setLong(1,Long.parseLong(body.path("patient").path("patientId").asText()));try(var rows=p.executeQuery()){assertTrue(rows.next());assertEquals("li",rows.getString(1));assertEquals("演示患者小李",rows.getString(2));assertEquals("3456789",rows.getString(3));assertFalse(rows.next());}}
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("ADC_T028_EVIDENCE"),"human-readable-patient-response.json"),saved.body());
      }
    }

    @Test void patientSelectedSlotRemainsImmutableThroughReceptionAndDoctorTimeline() throws Exception {
        try(var server=start();var client=HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build()){
            var base=URI.create("http://127.0.0.1:"+server.getWebServer().getPort());
            var before=effectiveSnapshot();
            var login=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/mini/sessions/wechat")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"SYNTHETIC-code\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,login.statusCode(),login.body());String bearer="Bearer "+AuditDocuments.parse(login.body()).path("accessToken").asText();
            String optionsPath="/api/v1/mini/booking-options?clinicId=81001&date=2026-11-08&projectId=85001";
            var options=client.send(HttpRequest.newBuilder(base.resolve(optionsPath)).header("Authorization",bearer).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,options.statusCode(),options.body());var list=AuditDocuments.parse(options.body());
            assertFalse(list.toString().contains("patientId"));assertFalse(list.toString().contains("appointmentId"));
            JsonNode slot=null;boolean full=false;
            for(var item:list.path("slots")){if("FULL".equals(item.path("availabilityStatus").asText()))full=true;if(slot==null&&"79001".equals(item.path("dentistId").asText())&&"AVAILABLE".equals(item.path("availabilityStatus").asText()))slot=item;}
            assertTrue(full,"Fixture includes an occupied slot");assertNotNull(slot);
            var scheme=AuditDocuments.JSON.createObjectNode().put("startsAt",slot.path("startsAt").asText()).put("endsAt",slot.path("endsAt").asText()).put("treatmentRequest","SYNTHETIC treatment");
            scheme.set("effectiveScheduleRef",slot.path("effectiveScheduleRef"));scheme.set("durationBasis",slot.path("durationBasis"));
            var request=AuditDocuments.JSON.createObjectNode().put("type","NEW").put("actingMode","SELF").put("intendedClinicId","81001").put("treatmentNeed","SYNTHETIC treatment").put("patientConfirmed",true);
            request.putObject("identity").put("patientIdentification",MINI_IDENTITY).put("applicantInformation",MINI_IDENTITY).put("contactInformation","classroom@example.invalid");
            request.putObject("preferred").put("date","2026-11-08");request.putObject("bookingIntent").put("visitType","INITIAL");request.set("selectedScheme",scheme);
            String intakeKey=UUID.randomUUID().toString();
            var received=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/mini/requests")).header("Authorization",bearer).header("Idempotency-Key",intakeKey).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(201,received.statusCode(),received.body());String requestId=AuditDocuments.parse(received.body()).path("requestId").asText();
            demoRole(client,base,"reception");
            var profile=AuditDocuments.JSON.createObjectNode().put("patientName","SYNTHETIC new patient").put("identityInformation",MINI_IDENTITY).put("contactInformation","classroom@example.invalid");
            profile.putObject("source").put("kind","MINI_REQUEST").put("requestId",requestId).put("expectedRequestVersion",0).put("identityMatchBasis","SYNTHETIC classroom identity");
            var created=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/patients")).header("X-CSRF-Token","SYNTHETIC-CSRF").header("Idempotency-Key",UUID.randomUUID().toString()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(profile.toString())).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(201,created.statusCode(),created.body());String patient=AuditDocuments.parse(created.body()).path("patient").path("patientId").asText();
            String ref=CourseMiniBookingContext.PREFIX+requestId+":1";
            var confirmation=AuditDocuments.JSON.createObjectNode().put("patientId",patient).put("confirmed",true).put("actingMode","SELF").put("verificationBasis","SYNTHETIC patient selected, reception verified").put("sourceContextReference",ref);confirmation.set("scheme",scheme);
            var altered=confirmation.deepCopy();altered.withObject("scheme").put("treatmentRequest","SYNTHETIC substituted treatment");
            var denied=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/booking-confirmations")).header("X-CSRF-Token","SYNTHETIC-CSRF").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(altered.toString())).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(409,denied.statusCode(),denied.body());
            var confirmed=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/booking-confirmations")).header("X-CSRF-Token","SYNTHETIC-CSRF").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(confirmation.toString())).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,confirmed.statusCode(),confirmed.body());var c=AuditDocuments.parse(confirmed.body());
            var command=AuditDocuments.JSON.createObjectNode().put("patientId",patient).put("acceptanceEvidenceReference",c.path("acceptanceEvidenceReference").asText()).put("sourceContextReference",ref);command.set("scheme",scheme);
            var saved=demoCommand(client,base,"/api/v1/staff/appointments",command.toString());
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("ADC_T028_EVIDENCE"),"patient-selected-booking-response.json"),saved.toString());
            assertEquals("COMPLETED",saved.path("status").asText(),saved.toString());assertEquals("SUCCEEDED",saved.path("businessOutcome").asText(),saved.toString());
            String appointment=saved.path("links").path("appointmentId").asText();
            var refreshed=client.send(HttpRequest.newBuilder(base.resolve(optionsPath)).header("Authorization",bearer).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,refreshed.statusCode());
            boolean nowFull=false;for(var item:AuditDocuments.parse(refreshed.body()).path("slots"))if(item.path("startsAt").equals(slot.path("startsAt"))&&item.path("effectiveScheduleRef").equals(slot.path("effectiveScheduleRef"))){assertEquals("FULL",item.path("availabilityStatus").asText());nowFull=true;}assertTrue(nowFull);
            var retry=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/mini/requests")).header("Authorization",bearer).header("Idempotency-Key",intakeKey).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),HttpResponse.BodyHandlers.ofString());assertEquals(201,retry.statusCode(),retry.body());assertEquals(AuditDocuments.parse(received.body()),AuditDocuments.parse(retry.body()));
            var stale=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/mini/requests")).header("Authorization",bearer).header("Idempotency-Key",UUID.randomUUID().toString()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),HttpResponse.BodyHandlers.ofString());assertEquals(409,stale.statusCode(),stale.body());
            demoRole(client,base,"dentist");
            var timeline=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/my-schedule?includeAppointments=true")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,timeline.statusCode());
            boolean found=false;for(var w:AuditDocuments.parse(timeline.body()).path("items"))for(var a:w.path("appointments"))if(appointment.equals(a.path("appointmentId").asText()))found=true;assertTrue(found);
            assertEquals(before,effectiveSnapshot());
            demoRole(client,base,"reception");
            var detail=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/appointments/"+appointment)).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,detail.statusCode(),detail.body());
            var cancel=AuditDocuments.JSON.createObjectNode().put("expectedAppointmentToken",AuditDocuments.parse(detail.body()).path("stateToken").asText()).put("cancellationVerification","SYNTHETIC cancellation verified");
            var cancelled=demoCommand(client,base,"/api/v1/staff/appointments/"+appointment+"/cancellations",cancel.toString());assertEquals("SUCCEEDED",cancelled.path("businessOutcome").asText(),cancelled.toString());
            var released=client.send(HttpRequest.newBuilder(base.resolve(optionsPath)).header("Authorization",bearer).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,released.statusCode(),released.body());
            boolean freeAgain=false;for(var item:AuditDocuments.parse(released.body()).path("slots"))if(item.path("startsAt").equals(slot.path("startsAt"))&&item.path("effectiveScheduleRef").equals(slot.path("effectiveScheduleRef"))){assertEquals("AVAILABLE",item.path("availabilityStatus").asText());freeAgain=true;}assertTrue(freeAgain);
            try(var conn=new T028IsolatedPgIT.IsolatedData("runtime",0).getConnection();var p=conn.prepareStatement("SELECT starts_at,ends_at,manual_confirmation,appointment_status FROM adc.appointment WHERE appointment_id=?")){
                p.setLong(1,Long.parseLong(appointment));try(var row=p.executeQuery()){assertTrue(row.next());assertEquals(java.time.OffsetDateTime.parse(slot.path("startsAt").asText()).toInstant(),row.getObject(1,java.time.OffsetDateTime.class).toInstant());assertEquals(java.time.OffsetDateTime.parse(slot.path("endsAt").asText()).toInstant(),row.getObject(2,java.time.OffsetDateTime.class).toInstant());assertTrue(row.getString(3).contains("SYNTHETIC patient selected"));assertEquals("已取消",row.getString(4));assertFalse(row.next());}}
            var evidence=AuditDocuments.JSON.createObjectNode().put("requestId",requestId).put("appointmentId",appointment).put("originalSchemePreserved",true).put("tamperedConfirmationStatus",409).put("fullSlotNewRequestStatus",409).put("sameKeyReceiptPreserved",true).put("doctorTimelineContainsAppointment",true).put("effectiveSnapshotUnchanged",true).put("committedCancellationReleasesSlot",true);evidence.set("scheme",scheme);
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("ADC_T028_EVIDENCE"),"patient-selected-slot-result.json"),evidence.toString());
        }
    }

    @Test void interactiveBookingUsesSelectedCurrentScheme() throws Exception {
        try(var server=start();var client=HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build()){
            var base=URI.create("http://127.0.0.1:"+server.getWebServer().getPort());demoRole(client,base,"reception");
            var before=effectiveSnapshot();
            String requestId=submitMiniRequest(server);
            var profile=AuditDocuments.JSON.createObjectNode().put("patientName","SYNTHETIC new patient").put("identityInformation",MINI_IDENTITY).put("contactInformation","classroom@example.invalid");
            profile.putObject("source").put("kind","MINI_REQUEST").put("requestId",requestId).put("expectedRequestVersion",0).put("identityMatchBasis","SYNTHETIC classroom identity");
            var created=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/patients")).header("X-CSRF-Token","SYNTHETIC-CSRF").header("Idempotency-Key",UUID.randomUUID().toString()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(profile.toString())).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(201,created.statusCode(),created.body());String patient=AuditDocuments.parse(created.body()).path("patient").path("patientId").asText();
            String sourceRef=CourseMiniBookingContext.PREFIX+requestId+":1";
            var sourcePage=client.send(HttpRequest.newBuilder(base.resolve("/staff/appointments?sourceRequest="+requestId+"&sourceVersion=1")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,sourcePage.statusCode(),sourcePage.body());
            var match=java.util.regex.Pattern.compile("name='adc-booking-source-preview' content='([^']+)'").matcher(sourcePage.body());assertTrue(match.find());
            var preview=AuditDocuments.parse(new String(java.util.Base64.getDecoder().decode(match.group(1)),java.nio.charset.StandardCharsets.UTF_8));assertEquals("2026-11-08",preview.path("preferredDate").asText());assertEquals("SYNTHETIC treatment",preview.path("treatmentNeed").asText());assertEquals(patient,preview.path("patientId").asText());

            var empty=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/booking-options?date=2026-12-04")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,empty.statusCode(),empty.body());assertTrue(AuditDocuments.parse(empty.body()).path("windows").isEmpty());
            var options=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/booking-options?date=2026-11-08")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,options.statusCode(),options.body());
            assertTrue(AuditDocuments.parse(options.body()).path("windows").size()>=3);
            var input=AuditDocuments.parse("{\"patientId\":\"88001\",\"scheme\":{\"effectiveScheduleRef\":{\"scheduleId\":\"87001\",\"versionNo\":2},\"startsAt\":\"2026-11-08T10:30:00Z\",\"endsAt\":\"2026-11-08T10:57:00Z\",\"treatmentRequest\":\"SYNTHETIC freely confirmed treatment\",\"durationBasis\":{\"schemaVersion\":1,\"basisMode\":\"INITIAL_CURRENT\",\"primaryProjectId\":\"85001\",\"projectVersion\":1,\"adoptedDurationMinutes\":27}},\"confirmed\":true,\"actingMode\":\"SELF\",\"verificationBasis\":\"SYNTHETIC communicated selected scheme\"}");
            ((tools.jackson.databind.node.ObjectNode)input).put("patientId",patient).put("sourceContextReference",sourceRef);
            var confirmation=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/booking-confirmations")).header("X-CSRF-Token","SYNTHETIC-CSRF").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,confirmation.statusCode(),confirmation.body());var confirmed=AuditDocuments.parse(confirmation.body());
            var command=AuditDocuments.JSON.createObjectNode().put("patientId",patient).put("sourceContextReference",sourceRef).put("acceptanceEvidenceReference",confirmed.path("acceptanceEvidenceReference").asText());command.set("scheme",confirmed.path("scheme"));
            var saved=demoCommand(client,base,"/api/v1/staff/appointments",command.toString());assertEquals("SUCCEEDED",saved.path("businessOutcome").asText(),saved.toString());
            long appointment=Long.parseLong(saved.path("links").path("appointmentId").asText());
            try(var c=new T028IsolatedPgIT.IsolatedData("runtime",0).getConnection();var p=c.prepareStatement("SELECT manual_confirmation,starts_at,ends_at FROM adc.appointment WHERE appointment_id=?")){
                p.setLong(1,appointment);try(var row=p.executeQuery()){assertTrue(row.next());assertTrue(row.getString(1).contains("SYNTHETIC communicated selected scheme"));assertEquals(java.time.Instant.parse("2026-11-08T10:30:00Z"),row.getObject(2,java.time.OffsetDateTime.class).toInstant());assertFalse(row.next());}
            }
            var pendingRequests=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/mini-requests")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,pendingRequests.statusCode(),pendingRequests.body());for(var row:AuditDocuments.parse(pendingRequests.body()).path("items"))assertNotEquals(requestId,row.path("requestId").asText());
            demoRole(client,base,"dentist");
            var timeline=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/staff/my-schedule?includeAppointments=true")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,timeline.statusCode(),timeline.body());boolean linked=false;
            for(var item:AuditDocuments.parse(timeline.body()).path("items"))for(var ap:item.path("appointments"))if(Long.toString(appointment).equals(ap.path("appointmentId").asText())){linked=true;assertEquals("待到诊",ap.path("statusLabel").asText());assertFalse(ap.has("patientId"));}
            assertTrue(linked,"Saved appointment must appear in this dentist's timeline");
            assertEquals(before,effectiveSnapshot());
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("ADC_T028_EVIDENCE"),"booking-interaction-http-result.json"),AuditDocuments.JSON.createObjectNode().put("passed",true).put("synthetic",true).put("appointmentId",Long.toString(appointment)).put("optionsWindows",AuditDocuments.parse(options.body()).path("windows").size()).put("effectiveUnchanged",true).put("requestId",requestId).put("patientId",patient).put("requestPreviewReadBack",true).put("pendingRequestRemoved",true).put("doctorTimelineReadBack",true).put("confirmationSnapshotPersisted",true).put("businessOutcome",saved.path("businessOutcome").asText()).toString());
        }
    }
    private static String effectiveSnapshot()throws Exception{
        try(var c=new T028IsolatedPgIT.IsolatedData("runtime",0).getConnection();var p=c.prepareStatement("SELECT coalesce(jsonb_agg(to_jsonb(v) ORDER BY schedule_id,schedule_version_no)::text,'[]') FROM adc.reception_schedule_version v WHERE activation_status='EFFECTIVE'");var rows=p.executeQuery()){rows.next();return rows.getString(1);}
    }
    @Test void serveClassroomDemo() throws Exception {
        var evidence = java.nio.file.Path.of(System.getenv("ADC_T028_EVIDENCE"));
        try (var server = start(); var client = HttpClient.newBuilder()
                .cookieHandler(new java.net.CookieManager()).build()) {
            var base = URI.create("http://127.0.0.1:" + server.getWebServer().getPort());
            var home = client.send(HttpRequest.newBuilder(base).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, home.statusCode());
            assertTrue(home.body().contains("ADC 课堂工作台"));
            var login = client.send(HttpRequest.newBuilder(base.resolve("/demo/login"))
                    .header("Sec-Fetch-Site", "same-origin").header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(demoLoginForm("billing"))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(302, login.statusCode());
            var screen = client.send(HttpRequest.newBuilder(base.resolve(login.headers().firstValue("Location").orElseThrow()))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, screen.statusCode());
            assertTrue(screen.body().contains("adc-uc10-csrf"));
            if ("true".equals(System.getenv("ADC_CLASSROOM_INVOICE_QUESTION_WEB"))) {
                assertTrue(screen.body().contains("/staff/invoice-question"));
                var question = client.send(HttpRequest.newBuilder(base.resolve("/staff/invoice-question"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, question.statusCode());
                assertTrue(question.body().contains("adc-uc11-csrf"));
            }
            var invoice = client.send(HttpRequest.newBuilder(base.resolve(VISIT + "/invoice"))
                    .header("X-CSRF-Token", "SYNTHETIC-CSRF").header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, invoice.statusCode(), "A fresh visit has no billable treatment");
            if (!"true".equals(System.getenv("ADC_CLASSROOM_HOLD"))) {
                verifyArrivalHandoff(client, base);
            }
            java.nio.file.Files.writeString(evidence.resolve("classroom-ready.txt"), base + System.lineSeparator());
            if ("true".equals(System.getenv("ADC_CLASSROOM_HOLD"))) {
                int minutes = Integer.parseInt(System.getenv().getOrDefault("ADC_CLASSROOM_HOLD_MINUTES", "120"));
                if (minutes < 15 || minutes > 180) throw new IllegalArgumentException("INVALID_CLASSROOM_DURATION");
                var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(minutes);
                while (System.nanoTime() < deadline && !java.nio.file.Files.exists(evidence.resolve("classroom-stop"))) {
                    Thread.sleep(500);
                }
            }
        }
    }

    private static void verifyArrivalHandoff(HttpClient client, URI base) throws Exception {
        demoRole(client, base, "reception");
        var created = demoCommand(client, base, "/api/v1/staff/appointments", """
                {"patientId":"88001","acceptanceEvidenceReference":"SYNTHETIC-acceptance",
                 "scheme":{"effectiveScheduleRef":{"scheduleId":"87001","versionNo":2},
                 "startsAt":"2026-11-08T10:30:00Z","endsAt":"2026-11-08T10:57:00Z","treatmentRequest":"SYNTHETIC treatment",
                 "durationBasis":{"schemaVersion":1,"basisMode":"INITIAL_CURRENT","primaryProjectId":"85001",
                 "projectVersion":1,"adoptedDurationMinutes":27}}}
                """);
        assertEquals("SUCCEEDED", created.path("businessOutcome").asText());
        var appointment = created.path("links").path("appointmentId").asText();
        assertTrue(appointment.matches("[1-9][0-9]*"));
        var path = "/api/v1/staff/appointments/" + appointment;
        var lookup = client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, lookup.statusCode(), lookup.body());
        var token = AuditDocuments.parse(lookup.body()).path("stateToken").asText();
        assertFalse(token.isBlank());
        var result = demoCommand(client, base, path + "/arrival-verifications", """
                {"expectedAppointmentToken":"%s","verificationBasis":"SYNTHETIC human evidence",
                 "verifiedIdentityReference":"SYNTHETIC patient verified","actualClinicId":"81001",
                 "actualDentistId":"79001","finding":"ARRIVAL_VERIFIED"}
                """.formatted(token));
        var visit = result.path("links").path("visitId").asText();
        assertEquals("SUCCEEDED", result.path("businessOutcome").asText());
        assertTrue(visit.matches("[1-9][0-9]*"));
        assertNotEquals("42", visit);
        demoRole(client, base, "dentist");
        var saved = demoCommand(client, base, "/api/v1/staff/visits/" + visit + "/consent", """
                {"patientId":"88001","communicationParty":"SYNTHETIC patient","guardianRequired":false,
                 "guardianParticipation":null,"proposedTreatment":"SYNTHETIC treatment","consentResult":"同意","comments":null}
                """);
        assertEquals(visit, saved.path("visitId").asText());
        try (var c = new T028IsolatedPgIT.IsolatedData("runtime", 0).getConnection();
             var count = c.prepareStatement("""
                     SELECT count(*) FROM adc.visit v JOIN adc.treatment_consent_result r USING (visit_id)
                     WHERE v.appointment_id=? AND v.visit_id=?
                     """)) {
            count.setLong(1, Long.parseLong(appointment));
            count.setLong(2, Long.parseLong(visit));
            try (var rows = count.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    private static void demoRole(HttpClient client, URI base, String role) throws Exception {
        var response = client.send(HttpRequest.newBuilder(base.resolve("/demo/login"))
                .header("Sec-Fetch-Site", "same-origin").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(demoLoginForm(role))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, response.statusCode(), response.body());
    }

    private static void createQuarterBillingFacts(ServletWebServerApplicationContext server) throws Exception {
        var base = URI.create("http://127.0.0.1:" + server.getWebServer().getPort());
        try (var client = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build()) {
            var invoice = createUnsettledInvoice(client, base);
            var invoiceId = invoice.path("invoiceId").asText();
            int version = invoice.path("invoiceVersionNo").asInt();
            var payment = demoCommand(client, base, VISIT + "/demo-settlement", """
                    {"invoiceId":"%s","invoiceVersionNo":%d,"operation":"DEMO_SETTLEMENT_START","paymentId":null,
                     "confirmation":{"applicant":"SYNTHETIC patient","amount":"33.33","basis":"SYNTHETIC confirmed"}}
                    """.formatted(invoiceId, version)).path("payment");
            var verified = demoCommand(client, base, VISIT + "/demo-settlement", """
                    {"invoiceId":"%s","invoiceVersionNo":%d,"operation":"DEMO_SETTLEMENT_VERIFY","paymentId":"%s","confirmation":null}
                    """.formatted(invoiceId, version, payment.path("paymentId").asText()));
            assertEquals("已结算", verified.path("payment").path("status").asText());
        }
    }

    private static JsonNode createUnsettledInvoice(HttpClient client, URI base) throws Exception {
        demoRole(client, base, "dentist");
            var consent = demoCommand(client, base, VISIT + "/consent", """
                    {"patientId":"88001","communicationParty":"SYNTHETIC patient","guardianRequired":false,
                     "guardianParticipation":null,"proposedTreatment":"SYNTHETIC treatment","consentResult":"同意","comments":null}
                    """);
            demoCommand(client, base, VISIT + "/treatment", """
                    {"patientId":"88001","consentResultId":"%s","performed":true,"project":"SYNTHETIC treatment",
                     "unit":"次","quantity":"1.250","specialRequirements":null,"nonPerformanceReason":null}
                    """.formatted(consent.path("consentResultId").asText()));
            demoRole(client, base, "billing");
            var invoice = demoCommand(client, base, VISIT + "/invoice", "{}").path("invoice");
            assertEquals("33.33", invoice.path("total").asText());
            return invoice;
    }

    private static JsonNode demoCommand(HttpClient client, URI base, String path, String input) throws Exception {
        var response = client.send(HttpRequest.newBuilder(base.resolve(path))
                .header("X-CSRF-Token", "SYNTHETIC-CSRF").header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(input)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return AuditDocuments.parse(response.body());
    }

    private static ServletWebServerApplicationContext start() {
        return (ServletWebServerApplicationContext) SpringApplication.run(Runtime.class,
                "--server.address=127.0.0.1", "--server.port=0", "--adc.mini.preliminary-intake-enabled=" + System.getenv("ADC_CLASSROOM_DEMO"),
                "--adc.mini.appointment-read-enabled=" + System.getenv("ADC_CLASSROOM_DEMO"),
                "--adc.mini.profile-read-enabled=" + System.getenv("ADC_CLASSROOM_DEMO"), "--adc.uc01.enabled=true", "--adc.uc07.enabled=true", "--adc.uc08.enabled=true",
                "--adc.mini.booking-options-enabled=true", "--adc.course.booking-interaction-enabled=true", "--adc.uc09.enabled=true", "--adc.uc10.enabled=true", "--adc.uc12.demo.enabled=true",
                "--adc.uc11.enabled=" + System.getenv("ADC_CLASSROOM_INVOICE_QUESTION_WEB"),
                "--adc.uc13.enabled=" + System.getenv("ADC_CLASSROOM_STOCK_WEB"),
                "--adc.uc14.enabled=" + System.getenv("ADC_CLASSROOM_STOCK_WEB"),
                "--adc.uc15.enabled=" + System.getenv("ADC_CLASSROOM_STOCK_WEB"),
                "--adc.uc16.enabled=" + System.getenv("ADC_CLASSROOM_STOCK_WEB"),
                "--adc.uc17.enabled=" + System.getenv("ADC_CLASSROOM_QUARTER_WEB"),
                "--adc.uc18.enabled=" + System.getenv("ADC_CLASSROOM_QUARTER_WEB"),
                "--adc.uc19.enabled=" + System.getenv("ADC_CLASSROOM_QUARTER_WEB"),
                "--adc.uc20.enabled=" + System.getenv("ADC_CLASSROOM_QUARTER_WEB"),
                "--adc.uc21.enabled=" + System.getenv("ADC_CLASSROOM_STOCK_WEB"),
                "--adc.uc22.resolution-enabled=" + System.getenv("ADC_CLASSROOM_STOCK_WEB"));
    }

    private static JsonNode post(ServletWebServerApplicationContext server, String role, String path,
                                 UUID key, String input) throws Exception {
        var uri = URI.create("http://127.0.0.1:" + server.getWebServer().getPort() + VISIT + path);
        var request = HttpRequest.newBuilder(uri).header("X-Test-Classroom-Role", role)
                .header("X-CSRF-Token", "SYNTHETIC-CSRF").header("Idempotency-Key", key.toString())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(input)).build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path + " " + response.body());
            return AuditDocuments.parse(response.body());
        }
    }
}
