package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.config.DentistEligibilityProvider;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.node.ObjectNode;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static cn.adc.appointments.ArrivalNoShowService.Action.ARRIVAL;
import static cn.adc.appointments.ArrivalNoShowService.Action.NO_SHOW;
import static cn.adc.appointments.T028IsolatedPgIT.IsolatedData;
import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AppointmentFlowIT {
    static final long CLINIC = 81001;
    private static final long OTHER = 81002;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T12:27:00Z"), ZoneOffset.UTC);
    private static final SourceStamp STAMP = new SourceStamp("SYNTHETIC","t034-v1","isolated",CLOCK.instant(),true);
    private static final Basis BASIS = new Basis(85001,1,27);
    private static final Version VERSION = new Version(87001,2);
    private static final AtomicLong APPOINTMENTS = new AtomicLong(1400);
    private static final AtomicLong AUDITS = new AtomicLong(61000);
    private static final AtomicLong HISTORY = new AtomicLong(62000);
    private static final AtomicLong VISITS = new AtomicLong(63000);
    private static final String ACTOR = "SYNTHETIC appointments reception";
    private final StaffAuthorization reception = new StaffAuthorization(() -> Optional.of(ACTOR),
            () -> Map.of(ACTOR,new StaffAuthorization.Grant("t034-v1",Set.of("BOOKING_RECEPTION","CLINIC_RECEPTION"),
                    Set.of("UC02","UC03","UC04","UC05","UC06"),Set.of(CLINIC,OTHER),false)));
    private final ClinicDirectoryProvider clinics = id -> new ClinicDirectoryProvider.Clinic(
            id,"SYNTHETIC",ZoneId.of("Etc/UTC"),STAMP);
    private final DentistEligibilityProvider dentists = (dentist,clinic) -> new DentistEligibilityProvider.Eligibility(
            dentist,clinic,"SYNTHETIC qualification",STAMP);
    private final AppointmentRepository repository = new AppointmentRepository();

    @Test void connectsSubmittedMiniRequestToCommittedBooking() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var tx = new JdbcTransactions(db);
        var content = JSON.createObjectNode().put("type", "NEW").put("intendedClinicId", Long.toString(CLINIC))
                .put("actingMode", "SELF").put("treatmentNeed", "SYNTHETIC request");
        content.putObject("identity").put("patientIdentification", "SYNTHETIC patient")
                .put("applicantInformation", "SYNTHETIC patient").put("contactInformation", "SYNTHETIC");
        content.putObject("preferred").put("date", "2026-11-16");
        content.putObject("bookingIntent").put("visitType", "INITIAL");
        var session = miniSession();
        var intake = new MiniNewRequestIntake(session, new JdbcMiniRequestIntakeStore(tx, CLOCK));
        var active = new AtomicBoolean(false);
        var mvc = standaloneSetup(new MiniIntakeController(session, intake),
                new MiniAppointmentReadController(session, tx, patientScope(88001, active))).build();
        var submission = mvc.perform(post("/api/v1/mini/requests")
                .header("Authorization", "Bearer SYNTHETIC-token").header("Idempotency-Key", "SYNTHETIC-UC02")
                .contentType("application/json").content(content.toString())).andReturn().getResponse();
        assertEquals(201, submission.getStatus());
        var receipt = JSON.readTree(submission.getContentAsString());
        assertEquals("CONFIRMED_RECEIVED", receipt.path("receiptStatus").asText());
        var request = UUID.fromString(receipt.path("requestId").asText());
        tx.execute(c -> {
            // Fixture for the separately verified and associated patient.
            try (var p = c.prepareStatement("""
                    UPDATE adc_mini.preliminary_request
                    SET associated_patient_id=88001, request_version=1 WHERE request_id=?
                    """)) {
                p.setObject(1, request);
                assertEquals(1, p.executeUpdate());
            }
            return null;
        });
        var source = new JdbcMiniBookingSource(new JdbcMiniBookingSource.Contexts() {
            @Override public JdbcMiniBookingSource.Context require(Connection c, String ref,
                    InitialBookingReview.AcceptedScheme scheme) {
                assertEquals("SYNTHETIC-verified-context", ref);
                assertEquals(88001, scheme.patientId());
                return new JdbcMiniBookingSource.Context(request, 1);
            }
            @Override public UUID originalRequest(Connection c, String ref) {
                assertEquals("SYNTHETIC-verified-context", ref);
                return request;
            }
        }, CLOCK);
        var review = new InitialBookingReview(tx, reception, clinics, acceptance("09:30:00", true));
        var service = new FormalBookingService(tx, reception, review, dentists, c -> APPOINTMENTS.incrementAndGet(),
                c -> AUDITS.incrementAndGet(), (c, scheme, coordination, ref) -> "SYNTHETIC reminder",
                (c, overlap) -> false, CLOCK, source);
        var list = get("/api/v1/mini/appointments").header("Authorization", "Bearer SYNTHETIC-token")
                .param("patientId", "88001");
        assertEquals(403, mvc.perform(list).andReturn().getResponse().getStatus());
        active.set(true);
        var submissionCommand = verifyBookingClients(mvc, new AppointmentController(service), request, null, false);
        var saved = service.recover(CLINIC, submissionCommand.key(), submissionCommand.input());
        assertEquals("SUCCEEDED", saved.businessOutcome());
        try (var c = db.getConnection(); var p = c.prepareStatement("""
                SELECT uc.audit_event_id::text
                FROM adc_mini.operation_audit_event mini
                JOIN adc.operation_audit_event uc ON uc.request_reference=mini.original_request_reference
                WHERE mini.request_id=? AND mini.operation='UC02_BOOKED' AND uc.outcome='SUCCESS'
                """)) {
            p.setObject(1, request);
            try (var rows = p.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(saved.successEventId(), rows.getString(1));
                assertFalse(rows.next());
            }
        }
    }

    @Test void committedBookingAppearsOnlyWithinCurrentMiniPatientScope() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var bookingResult = booking(db, "09:30:00", true, cancellation(new JdbcTransactions(db))::confirmsRelease,
                (c, s, coord, ref) -> "SYNTHETIC original reminder")
                .book(CLINIC, UUID.randomUUID(), create("09:30:00"));
        assertEquals("SUCCEEDED", bookingResult.businessOutcome());
        long id = bookingResult.result().path("appointmentId").asLong();
        long patient = read(db, id, CLINIC).patientId();
        var active = new AtomicBoolean(true);
        var controller = new MiniAppointmentReadController(miniSession(), new JdbcTransactions(db),
                patientScope(patient, active));
        var mvc = standaloneSetup(controller).build();
        var auth = get("/api/v1/mini/authorizations").header("Authorization", "Bearer SYNTHETIC-token");
        var list = get("/api/v1/mini/appointments").header("Authorization", "Bearer SYNTHETIC-token")
                .param("patientId", Long.toString(patient)).param("cursor", Long.toString(id - 1));
        var response = mvc.perform(auth).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals(Long.toString(patient), JSON.readTree(response.getContentAsString()).path("items").get(0)
                .path("patientId").asText());
        response = mvc.perform(list).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var item = JSON.readTree(response.getContentAsString()).path("items").get(0);
        assertEquals(Long.toString(id), item.path("appointmentId").asText());
        assertEquals("VERIFIED", item.path("factVerification").asText());
        active.set(false);
        assertEquals(403, mvc.perform(list).andReturn().getResponse().getStatus());
    }

    @Test void miniPatientReadsSameAppointmentAfterCommittedChange() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var tx = new JdbcTransactions(db);
        var first = booking(db, "09:30:00", true, (c, overlap) -> false, (c, s, coord, ref) -> "SYNTHETIC reminder")
                .book(CLINIC, UUID.randomUUID(), create("09:30:00"));
        assertEquals("SUCCEEDED", first.businessOutcome());
        long id = first.result().path("appointmentId").asLong();
        var original = read(db, id, CLINIC);
        var active = new AtomicBoolean(true);
        var session = miniSession();
        var intake = new MiniNewRequestIntake(session, new JdbcMiniRequestIntakeStore(tx, CLOCK,
                (c, account, patient, appointment) -> "SYNTHETIC-account".equals(account)
                        && patient == original.patientId() && appointment == id));
        var mvc = standaloneSetup(new MiniAppointmentReadController(session, tx,
                patientScope(original.patientId(), active)), new MiniIntakeController(session, intake)).build();
        var list = get("/api/v1/mini/appointments").header("Authorization", "Bearer SYNTHETIC-token")
                .param("patientId", Long.toString(original.patientId())).param("cursor", Long.toString(id - 1));
        var originalResponse = mvc.perform(list).andReturn().getResponse();
        assertEquals(200, originalResponse.getStatus());
        var originalView = JSON.readTree(originalResponse.getContentAsString()).path("items").get(0);
        assertEquals("VERIFIED", originalView.path("factVerification").asText());
        assertEquals(time("09:30:00").toString(), originalView.path("startsAt").asText());

        var content = JSON.createObjectNode().put("type", "CHANGE").put("actingMode", "SELF")
                .put("patientId", Long.toString(original.patientId())).put("originalAppointmentId", Long.toString(id));
        content.putObject("identity").put("patientIdentification", "SYNTHETIC")
                .put("applicantInformation", "SYNTHETIC").put("contactInformation", "SYNTHETIC");
        content.putObject("preferred").put("date", "2026-11-08");
        var submitted = mvc.perform(post("/api/v1/mini/requests")
                .header("Authorization", "Bearer SYNTHETIC-token").header("Idempotency-Key", "SYNTHETIC-UC03")
                .contentType("application/json").content(content.toString())).andReturn().getResponse();
        assertEquals(201, submitted.getStatus());
        var requestReceipt = JSON.readTree(submitted.getContentAsString());
        assertEquals("CONFIRMED_RECEIVED", requestReceipt.path("receiptStatus").asText());
        var request = UUID.fromString(requestReceipt.path("requestId").asText());
        assertEquals(original, read(db, id, CLINIC));
        var currentVersion = new java.util.concurrent.atomic.AtomicInteger(1);
        String sourceReference = "SYNTHETIC-change-source";
        var source = new JdbcMiniChangeSource(new JdbcMiniChangeSource.Contexts() {
            @Override public JdbcMiniChangeSource.Context require(Connection c, String reference,
                    AppointmentRepository.Appointment before, InitialBookingReview.AcceptedScheme scheme,
                    String acceptanceReference) {
                assertEquals(sourceReference, reference);
                assertEquals(id, before.id());
                assertEquals(time("10:00:00"), scheme.startsAt());
                assertEquals("SYNTHETIC acceptance", acceptanceReference);
                return new JdbcMiniChangeSource.Context(request, currentVersion.get());
            }
            @Override public UUID originalRequest(Connection c, String reference) {
                assertEquals(sourceReference, reference);
                return request;
            }
        }, CLOCK);
        var change = new AppointmentChangeService(tx, reception, clinics, dentists, acceptance("10:00:00", true),
                c -> HISTORY.incrementAndGet(), c -> AUDITS.incrementAndGet(), (c, o, t) -> "SYNTHETIC new reminder",
                (c, overlap) -> false, CLOCK, repository, source);
        var proposal = JSON.createObjectNode().put("expectedAppointmentToken", AppointmentReadService.token(original))
                .put("acceptanceEvidenceReference", "SYNTHETIC acceptance").put("changeBasis", "SYNTHETIC change")
                .put("sourceContextReference", sourceReference);
        proposal.set("scheme", scheme("10:00:00", "PRESERVED_ORIGINAL"));
        assertEquals("MINI_CHANGE_SOURCE_STALE", change.change(id, CLINIC, CLINIC, UUID.randomUUID(), proposal).reason());
        assertEquals(original, read(db, id, CLINIC));
        currentVersion.set(0);
        var staff = new AppointmentController(null, new AppointmentReadService(tx, reception, CLOCK), change);
        var command = verifyBookingClients(mvc, staff, request, id, false);
        var changed = change.recover(id, CLINIC, CLINIC, command.key(), command.input());
        assertEquals("SUCCEEDED", changed.businessOutcome());
        assertEquals(id, changed.result().path("appointmentId").asLong());
        var updatedResponse = mvc.perform(list).andReturn().getResponse();
        assertEquals(200, updatedResponse.getStatus());
        var updated = JSON.readTree(updatedResponse.getContentAsString()).path("items").get(0);
        assertEquals(Long.toString(id), updated.path("appointmentId").asText());
        assertEquals(time("10:00:00").toString(), updated.path("startsAt").asText());
        assertEquals("VERIFIED", updated.path("factVerification").asText());
        active.set(false);
        assertEquals(403, mvc.perform(list).andReturn().getResponse().getStatus());
    }

    @Test void miniPatientReadsCommittedCancellationAndCannotReadAnotherPatient() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var tx = new JdbcTransactions(db);
        var first = booking(db, "09:30:00", true, (c, overlap) -> false, (c, s, coord, ref) -> "SYNTHETIC reminder")
                .book(CLINIC, UUID.randomUUID(), create("09:30:00"));
        assertEquals("SUCCEEDED", first.businessOutcome());
        long id = first.result().path("appointmentId").asLong();
        var original = read(db, id, CLINIC);
        var active = new AtomicBoolean(true);
        var session = miniSession();
        var intake = new MiniNewRequestIntake(session, new JdbcMiniRequestIntakeStore(tx, CLOCK, null,
                (c, account, patient, appointment) -> "SYNTHETIC-account".equals(account)
                        && patient == original.patientId() && appointment == id));
        var mvc = standaloneSetup(new MiniAppointmentReadController(session, tx,
                patientScope(original.patientId(), active)), new MiniIntakeController(session, intake)).build();
        var list = get("/api/v1/mini/appointments").header("Authorization", "Bearer SYNTHETIC-token")
                .param("patientId", Long.toString(original.patientId())).param("cursor", Long.toString(id - 1));
        var before = mvc.perform(list).andReturn().getResponse();
        assertEquals(200, before.getStatus());
        assertEquals("待到诊", JSON.readTree(before.getContentAsString()).path("items").get(0).path("formalStatus").asText());

        var content = JSON.createObjectNode().put("type", "CANCEL").put("actingMode", "SELF")
                .put("patientId", Long.toString(original.patientId())).put("originalAppointmentId", Long.toString(id));
        content.putObject("identity").put("patientIdentification", "SYNTHETIC")
                .put("applicantInformation", "SYNTHETIC").put("contactInformation", "SYNTHETIC");
        var received = mvc.perform(post("/api/v1/mini/requests").header("Authorization", "Bearer SYNTHETIC-token")
                .header("Idempotency-Key", "SYNTHETIC-UC04").contentType("application/json")
                .content(content.toString())).andReturn().getResponse();
        assertEquals(201, received.getStatus());
        var receipt = JSON.readTree(received.getContentAsString());
        assertEquals("CONFIRMED_RECEIVED", receipt.path("receiptStatus").asText());
        var requestId = UUID.fromString(receipt.path("requestId").asText());
        assertEquals(original, read(db, id, CLINIC));

        var version = new java.util.concurrent.atomic.AtomicInteger(1);
        var source = new JdbcMiniCancellationSource(new JdbcMiniCancellationSource.Contexts() {
            public JdbcMiniCancellationSource.Context require(Connection c, String sourceReference,
                    AppointmentRepository.Appointment before, String verifiedCancellation) {
                assertEquals(id, before.id());
                assertEquals("SYNTHETIC verified", verifiedCancellation);
                return new JdbcMiniCancellationSource.Context(originalRequest(c, sourceReference), version.get());
            }
            public UUID originalRequest(Connection c, String sourceReference) {
                assertEquals("SYNTHETIC cancellation source", sourceReference);
                return requestId;
            }
        }, CLOCK);
        var service = new AppointmentCancellationService(tx, reception,
                (c, row, ref) -> Optional.of("SYNTHETIC verified"), c -> AUDITS.incrementAndGet(), CLOCK, repository, source);
        var command = JSON.createObjectNode().put("expectedAppointmentToken", AppointmentReadService.token(original))
                .put("cancellationVerification", "SYNTHETIC cancellation")
                .put("sourceContextReference", "SYNTHETIC cancellation source");
        assertEquals("MINI_CANCEL_SOURCE_STALE", service.cancel(id, CLINIC, UUID.randomUUID(), command).reason());
        assertEquals(original, read(db, id, CLINIC));
        version.set(0);
        var staff = new AppointmentController(null, new AppointmentReadService(tx, reception, CLOCK), null, service);
        var submitted = verifyBookingClients(mvc, staff, requestId, id, true);
        var cancelled = service.recover(id, CLINIC, submitted.key(), submitted.input());
        assertEquals("SUCCEEDED", cancelled.businessOutcome());
        assertEquals(id, cancelled.result().path("appointmentId").asLong());
        var row = read(db, id, CLINIC);
        assertEquals("STOPPED", JSON.readTree(row.terms().reminder()).path("state").asText());
        var after = mvc.perform(list).andReturn().getResponse();
        assertEquals(200, after.getStatus());
        var item = JSON.readTree(after.getContentAsString()).path("items").get(0);
        assertEquals(Long.toString(id), item.path("appointmentId").asText());
        assertEquals("已取消", item.path("formalStatus").asText());
        assertEquals("VERIFIED", item.path("factVerification").asText());
        assertEquals(403, mvc.perform(get("/api/v1/mini/appointments").header("Authorization", "Bearer SYNTHETIC-token")
                .param("patientId", "88002")).andReturn().getResponse().getStatus());
    }

    private static MiniRequestSession miniSession() {
        return new MiniRequestSession(code -> "SYNTHETIC-code".equals(code)
                ? Optional.of("SYNTHETIC-account") : Optional.empty(), new MiniRequestSession.Sessions() {
            public String issue(String account) { return "SYNTHETIC-token"; }
            public Optional<String> activeAccount(String token) {
                return "SYNTHETIC-token".equals(token) ? Optional.of("SYNTHETIC-account") : Optional.empty();
            }
        });
    }

    private record BrowserBooking(UUID key, tools.jackson.databind.JsonNode input) {}

    private static BrowserBooking verifyBookingClients(MockMvc mvc, AppointmentController staff, UUID requestId,
                                                       Long changeAppointmentId, boolean cancel)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var secret = UUID.randomUUID().toString();
        var staffRoot = "/api/v1/staff/appointments";
        var staffDetail = staffRoot + "/" + changeAppointmentId;
        var staffSupport = staffDetail + "/schedule-support";
        var staffWrite = changeAppointmentId == null ? staffRoot : staffDetail + (cancel ? "/cancellations" : "/changes");
        var paths = new java.util.HashSet<>(Set.of("/api/v1/mini/sessions/wechat", "/api/v1/mini/authorizations",
                "/api/v1/mini/appointments", staffWrite));
        if (changeAppointmentId != null) {
            paths.add(staffDetail);
            paths.add(staffSupport);
        }
        var posted = new AtomicReference<BrowserBooking>();
        var bookedId = new AtomicReference<String>();
        var nativeBookingId = new AtomicReference<String>();
        var nativeRead = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            boolean verifiedNativeResponse = false;
            try (exchange) {
                var uri = exchange.getRequestURI();
                boolean automated = secret.equals(exchange.getRequestHeaders().getFirst("X-Synthetic-Test"));
                boolean nativeMini = !automated && nativeBookingId.get() != null
                        && uri.getPath().startsWith("/api/v1/mini/");
                if (!paths.contains(uri.getPath()) || (!automated && !nativeMini)) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                var body = exchange.getRequestBody().readNBytes(8193);
                if (body.length > 8192) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                byte[] bytes;
                int status;
                if (uri.getPath().equals(staffWrite)) {
                    if (!"POST".equals(exchange.getRequestMethod())
                            || !"SYNTHETIC-CSRF".equals(exchange.getRequestHeaders().getFirst("X-CSRF-Token"))) {
                        exchange.sendResponseHeaders(403, -1);
                        return;
                    }
                    var key = UUID.fromString(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    var input = JSON.readTree(body);
                    assertTrue(posted.compareAndSet(null, new BrowserBooking(key, input)));
                    var response = changeAppointmentId == null ? staff.create(CLINIC, key.toString(), input)
                            : cancel ? staff.cancel(changeAppointmentId, CLINIC, key.toString(), input)
                            : staff.change(changeAppointmentId, CLINIC, CLINIC, key.toString(), input);
                    if (response.status() == 200) bookedId.set(response.body().path("links").path("appointmentId").asText());
                    bytes = JSON.writeValueAsBytes(response.body());
                    status = response.status();
                } else if (changeAppointmentId != null && uri.getPath().startsWith(staffDetail)) {
                    if (!"GET".equals(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(405, -1);
                        return;
                    }
                    var response = uri.getPath().equals(staffDetail)
                            ? staff.detail(CLINIC, changeAppointmentId.toString())
                            : staff.support(CLINIC, changeAppointmentId.toString());
                    bytes = JSON.writeValueAsBytes(response.body());
                    status = response.status();
                } else {
                    var query = request(HttpMethod.valueOf(exchange.getRequestMethod()), uri).content(body);
                    exchange.getRequestHeaders().forEach((name, values) -> values.forEach(value -> query.header(name, value)));
                    var response = mvc.perform(query).andReturn().getResponse();
                    bytes = response.getContentAsByteArray();
                    status = response.getStatus();
                    if (nativeMini && status == 200 && uri.getPath().equals("/api/v1/mini/appointments")) {
                        for (var item : JSON.readTree(bytes).path("items")) {
                            if (nativeBookingId.get().equals(item.path("appointmentId").asText())
                                    && "VERIFIED".equals(item.path("factVerification").asText())
                                    && (!cancel || "已取消".equals(item.path("formalStatus").asText()))
                                    && (changeAppointmentId == null || cancel
                                        || time("10:00:00").toString().equals(item.path("startsAt").asText()))) {
                                verifiedNativeResponse = true;
                            }
                        }
                    }
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception failure) {
                throw new java.io.IOException(failure);
            }
            // Let the simulator receive the completed response before the test stops the server.
            if (verifiedNativeResponse) nativeRead.countDown();
        });
        server.start();
        try {
            var actorScope = cn.adc.shared.audit.AuditDocuments.hash(JSON.getNodeFactory().textNode(ACTOR)).substring(0, 32);
            var output = java.nio.file.Files.createTempFile("adc-uc02-client-", ".log");
            var builder = new ProcessBuilder("node", "../apps/patient-miniapp/tests/isolated-appointment-read.cjs",
                    Integer.toString(server.getAddress().getPort()), requestId.toString(), actorScope,
                    changeAppointmentId == null ? "" : changeAppointmentId.toString(), cancel ? "cancel" : "")
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().keySet().removeIf(key -> key.startsWith("ADC_T028_") || key.startsWith("PG"));
            builder.environment().put("ADC_MINI_READ_PROBE", secret);
            var client = builder.start();
            try {
                assertTrue(client.waitFor(35, TimeUnit.SECONDS), "Booking clients timed out");
                System.out.print(java.nio.file.Files.readString(output));
                assertEquals(0, client.exitValue(), "Mini client readback failed");
                var booking = java.util.Objects.requireNonNull(posted.get(), "Booking was not submitted");
                var nativeCase = changeAppointmentId == null ? "uc02" : cancel ? "uc04" : "uc03";
                if ("true".equals(System.getenv("ADC_" + nativeCase.toUpperCase(java.util.Locale.ROOT) + "_NATIVE_READ"))) {
                    var id = bookedId.get();
                    assertTrue(id != null && id.matches("[1-9][0-9]*"));
                    nativeBookingId.set(id);
                    var evidence = java.nio.file.Path.of(System.getenv("ADC_T028_EVIDENCE"));
                    var expected = cancel ? "formalStatus=已取消\n"
                            : changeAppointmentId == null ? "" : "startsAt=" + time("10:00:00") + "\n";
                    java.nio.file.Files.writeString(evidence.resolve(nativeCase + "-native-ready.txt"),
                            "origin=http://127.0.0.1:" + server.getAddress().getPort()
                                    + "\nappointmentId=" + id + "\n" + expected);
                    assertTrue(nativeRead.await(8, TimeUnit.MINUTES), "Native appointment readback timed out");
                    java.nio.file.Files.writeString(evidence.resolve(nativeCase + "-native-readback.txt"),
                            "appointmentId=" + id + "\nfactVerification=VERIFIED\n" + expected);
                }
                return booking;
            } finally {
                if (client.isAlive()) client.destroyForcibly();
                java.nio.file.Files.deleteIfExists(output);
            }
        } finally {
            server.stop(0);
        }
    }

    private static MiniAppointmentReadController.PatientScope patientScope(long patient, AtomicBoolean active) {
        return new MiniAppointmentReadController.PatientScope() {
            @Override public boolean mayRead(Connection c, String account, long requested) {
                return active.get() && account.equals("SYNTHETIC-account") && requested == patient;
            }
            @Override public List<MiniAppointmentReadController.Authorization> authorizations(Connection c, String account) {
                return List.of(new MiniAppointmentReadController.Authorization("SYNTHETIC-grant", patient,
                        MiniAppointmentReadController.Authorization.Kind.SELF, List.of(), 1));
            }
        };
    }

    @Test void createChangeAndCancelKeepTheSameAppointmentAndCommittedHistory() throws Exception {
        var db = new IsolatedData("runtime",0);
        if ("true".equals(System.getenv("ADC_T037_FULL_CHAIN"))) verifyPatientMaintenance(db);
        var tx = new JdbcTransactions(db);
        var cancel = cancellation(tx);
        var first = booking(db,"09:30:00",true,cancel::confirmsRelease,(c,s,coord,ref) -> "SYNTHETIC original reminder")
                .book(CLINIC,UUID.randomUUID(),create("09:30:00"));
        assertEquals("SUCCEEDED",first.businessOutcome());
        long id = first.result().path("appointmentId").asLong();
        var original = read(db,id,CLINIC);
        var change = new AppointmentChangeService(tx,reception,clinics,dentists,acceptance("10:00:00",true),
                c -> HISTORY.incrementAndGet(),c -> AUDITS.incrementAndGet(),(c,o,t) -> "SYNTHETIC new reminder",
                cancel::confirmsRelease,CLOCK);
        var proposal = JSON.createObjectNode().put("expectedAppointmentToken",AppointmentReadService.token(original))
                .put("acceptanceEvidenceReference","SYNTHETIC acceptance").put("changeBasis","SYNTHETIC change");
        proposal.set("scheme",scheme("10:00:00","PRESERVED_ORIGINAL"));
        assertEquals("VERIFIED_MINIPROGRAM_CHANGE_UNAVAILABLE",change.change(id,CLINIC,CLINIC,UUID.randomUUID(),
                proposal.deepCopy().put("sourceContextReference","SYNTHETIC untrusted")).reason());
        var changed = change.change(id,CLINIC,CLINIC,UUID.randomUUID(),proposal);
        assertEquals("SUCCEEDED",changed.businessOutcome());
        assertEquals(id,changed.result().path("appointmentId").asLong());
        var updated = read(db,id,CLINIC);
        var evidence = new AppointmentResolutionEvidence();
        long changeEvent = Long.parseLong(changed.successEventId());
        long changeId = changed.result().path("appointmentChangeId").asLong();
        if ("true".equals(System.getenv("ADC_T037_FULL_CHAIN"))) {
            cn.adc.scheduling.T037IsolatedPgIT.assertFormalFactCommits(db, id, AppointmentReadService.token(updated),
                    "UC03", changed.successEventId(), Long.toString(changeId), 76001);
        }
        try (var c = db.getConnection()) {
            assertTrue(evidence.confirms(c, CLINIC, id, AppointmentReadService.token(updated),
                    changeEvent, "UC03", changeId, null));
            assertFalse(evidence.confirms(c, OTHER, id, AppointmentReadService.token(updated),
                    changeEvent, "UC03", changeId, null));
            assertFalse(evidence.confirms(c, CLINIC, id, AppointmentReadService.token(updated),
                    changeEvent, "UC03", changeId + 1, null));
        }
        var cancellation = JSON.createObjectNode().put("expectedAppointmentToken",AppointmentReadService.token(updated))
                .put("cancellationVerification","SYNTHETIC cancellation");
        assertEquals("VERIFIED_MINIPROGRAM_CANCELLATION_UNAVAILABLE",cancel.cancel(id,CLINIC,UUID.randomUUID(),
                cancellation.deepCopy().put("sourceContextReference","SYNTHETIC untrusted")).reason());
        var cancelled = cancel.cancel(id,CLINIC,UUID.randomUUID(),cancellation);
        assertEquals("SUCCEEDED",cancelled.businessOutcome());
        var current = read(db,id,CLINIC);
        if ("true".equals(System.getenv("ADC_T037_FULL_CHAIN"))) {
            cn.adc.scheduling.T037IsolatedPgIT.assertFormalFactCommits(db, id, AppointmentReadService.token(current),
                    "UC04", cancelled.successEventId(), cancelled.requestReference(), 76002);
        }
        assertEquals("已取消",current.status());
        assertEquals(time("10:00:00"),current.terms().startsAt());
        assertEquals("STOPPED",JSON.readTree(current.terms().reminder()).path("state").asText());
        try (var c=db.getConnection()) {
            long cancelEvent = Long.parseLong(cancelled.successEventId());
            var token = AppointmentReadService.token(current);
            assertTrue(evidence.confirms(c, CLINIC, id, token, cancelEvent, "UC04", null,
                    cancelled.requestReference()));
            assertFalse(evidence.confirms(c, CLINIC, id, token, cancelEvent, "UC04", null,
                    changed.requestReference()));
            assertFalse(evidence.confirms(c, CLINIC, id, token, changeEvent, "UC03", changeId, null));
            var history = repository.findChange(c,id,changed.result().path("appointmentChangeId").asLong()).orElseThrow();
            assertEquals(original.terms(),history.before());
            assertEquals(updated.terms(),history.after());
            assertEquals(1,count(c,"SELECT count(*) FROM adc.appointment_change_history WHERE appointment_id="+id));
        }
    }

    private static void verifyPatientMaintenance(IsolatedData db) throws Exception {
        var auth = new StaffAuthorization(() -> Optional.of(ACTOR), () -> Map.of(ACTOR,
                new StaffAuthorization.Grant("SYNTHETIC-v1", Set.of("BOOKING_RECEPTION"), Set.of("UC01"),
                        Set.of(CLINIC), false)));
        var tx = new JdbcTransactions(db);
        var service = new PatientMaintenanceService(tx, auth, (c, actor, clinic, patient, input) -> {
            if (!ACTOR.equals(actor) || clinic != CLINIC || (patient != null && patient != 88101L)
                    || !input.path("identityInformation").asText().equals("SYNTHETIC UC01 identity")) {
                throw new SecurityException("PATIENT_CONTEXT_DENIED");
            }
        }, c -> 88101L, c -> AUDITS.incrementAndGet(), CLOCK);
        var query = new PatientDirectoryQuery(tx, auth, (c, ref, actor, clinic) ->
                "SYNTHETIC identity context".equals(ref) ? Optional.of("SYNTHETIC UC01 identity") : Optional.empty());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new PatientMaintenanceController(new PatientMaintenanceController.Access() {
                    private void csrf(String csrf) {
                        if (!"SYNTHETIC-CSRF".equals(csrf)) throw new SecurityException();
                    }
                    public tools.jackson.databind.JsonNode query(jakarta.servlet.http.HttpServletRequest req,
                            String csrf, tools.jackson.databind.JsonNode input) {
                        csrf(csrf);
                        return query.query(input);
                    }
                    public cn.adc.shared.audit.CommandRecovery.Receipt save(jakarta.servlet.http.HttpServletRequest req,
                            String csrf, UUID key, Long patient, tools.jackson.databind.JsonNode input) {
                        csrf(csrf);
                        return service.save(CLINIC, patient, key, input);
                    }
                })).build();
        var request = JSON.createObjectNode().put("identityInformation", "SYNTHETIC UC01 identity")
                .put("patientName", "SYNTHETIC UC01").put("contactInformation", "SYNTHETIC contact");
        String patientPath = "/api/v1/staff/patients";
        for (int i = 0; i < 2; i++) {
            var response = postPatient(mvc, patientPath, "create", request);
            assertEquals(201, response.getStatus(), response.getContentAsString());
            assertEquals("88101", JSON.readTree(response.getContentAsString()).path("patient").path("patientId").asText());
        }
        assertEquals(409, postPatient(mvc, patientPath, "duplicate", request).getStatus());
        assertEquals(200, postPatient(mvc, patientPath + "/88101/updates", "update",
                request.deepCopy().put("patientName", "SYNTHETIC updated")).getStatus());
        var lookup = JSON.createObjectNode().put("clinicId", Long.toString(CLINIC))
                .put("verifiedIdentityContext", "SYNTHETIC identity context");
        var found = postPatient(mvc, "/api/v1/staff/patient-queries", "query", lookup);
        assertEquals(200, found.getStatus());
        assertEquals("SYNTHETIC updated", JSON.readTree(found.getContentAsString()).path("items").path(0).path("displayName").asText());
        assertEquals(403, postPatient(mvc, "/api/v1/staff/patient-queries", "denied",
                lookup.deepCopy().put("clinicId", Long.toString(OTHER))).getStatus());
        try (var c = db.getConnection()) {
            assertEquals(1, count(c, "SELECT count(*) FROM adc.patient WHERE patient_id=88101"));
            assertEquals(2, count(c, "SELECT count(*) FROM adc.operation_audit_event WHERE uc_code='UC01' AND outcome='SUCCESS'"));
        }
    }

    private static org.springframework.mock.web.MockHttpServletResponse postPatient(
            org.springframework.test.web.servlet.MockMvc mvc, String path, String key, ObjectNode request) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Idempotency-Key", key).header("X-CSRF-Token", "SYNTHETIC-CSRF")
                .contentType("application/json").content(request.toString())).andReturn().getResponse();
    }

    @Test void attendanceAndNoShowRequireTrustAndPersistDistinctFacts() throws Exception {
        var db = new IsolatedData("runtime",0);
        var svc = new ArrivalNoShowService(new JdbcTransactions(db),reception,repository,
                (c,row,finding) -> "SYNTHETIC human evidence".equals(finding.basis())
                        ? Optional.of(new ArrivalNoShowService.Verified("SYNTHETIC verified",null)) : Optional.empty(),
                c -> VISITS.incrementAndGet(),c -> AUDITS.incrementAndGet(),CLOCK);
        var booking = booking(db,"10:27:00",false,(c,o)->false,(c,s,coord,ref)->"SYNTHETIC reminder");
        var rejected = booking.book(CLINIC,UUID.randomUUID(),create("10:27:00"));
        assertEquals("ACCEPTANCE_RECONFIRMATION_REQUIRED",rejected.reason());
        booking = booking(db,"10:27:00",true,(c,o)->false,(c,s,coord,ref)->"SYNTHETIC reminder");
        var created = booking.book(CLINIC,UUID.randomUUID(),create("10:27:00"));
        assertEquals("SUCCEEDED",created.businessOutcome(),created::reason);
        long id = created.result().path("appointmentId").asLong();
        var arrival = finding(read(db,id,CLINIC),"ARRIVAL_VERIFIED")
                .put("actualClinicId","81001").put("actualDentistId","79001")
                .put("verifiedIdentityReference","SYNTHETIC identity");
        assertEquals("ATTENDANCE_VERIFICATION_REQUIRED",svc.submit(ARRIVAL,id,CLINIC,UUID.randomUUID(),
                arrival.deepCopy().put("verificationBasis","SYNTHETIC untrusted")).reason());
        var attended = svc.submit(ARRIVAL,id,CLINIC,UUID.randomUUID(),arrival);
        assertEquals("SUCCEEDED",attended.businessOutcome());
        var noShow = svc.submit(NO_SHOW,902,OTHER,UUID.randomUUID(),finding(read(db,902,OTHER),"NO_SHOW_VERIFIED"));
        assertEquals("SUCCEEDED",noShow.businessOutcome());
        try (var c=db.getConnection()) {
            var visit = repository.findVisit(c,read(db,id,CLINIC)).orElseThrow();
            assertEquals(visit.id(),attended.result().path("visitId").asLong());
            assertTrue(repository.findVisit(c,read(db,902,OTHER)).isEmpty());
        }
        assertEquals("已到诊",read(db,id,CLINIC).status());
        assertEquals("已确认未到诊",read(db,902,OTHER).status());
    }

    FormalBookingService booking(IsolatedData db, String start, boolean trusted, FormalBookingService.ReleaseEvidence release,
                                  FormalBookingService.BookingPreparation preparation) {
        var tx = new JdbcTransactions(db);
        var review = new InitialBookingReview(tx,reception,clinics,acceptance(start,trusted));
        return new FormalBookingService(tx,reception,review,dentists,c -> APPOINTMENTS.incrementAndGet(),
                c -> AUDITS.incrementAndGet(),preparation,release,CLOCK);
    }

    private AppointmentCancellationService cancellation(JdbcTransactions tx) {
        return new AppointmentCancellationService(tx,reception,
                (c,row,ref) -> "SYNTHETIC cancellation".equals(ref) ? Optional.of("SYNTHETIC verified") : Optional.empty(),
                c -> AUDITS.incrementAndGet(),CLOCK,repository);
    }

    private BookingAcceptanceVerifier acceptance(String start, boolean trusted) {
        var at = time(start);
        var scheme = new InitialBookingReview.AcceptedScheme(88001,VERSION,CLINIC,79001,at,at.plusSeconds(1620),
                "SYNTHETIC request",BASIS);
        return new BookingAcceptanceVerifier((c,ref) -> trusted ? Optional.of(new BookingAcceptanceVerifier.Confirmation(
                "SYNTHETIC acceptance",STAMP,"SYNTHETIC patient",null,CLOCK.instant(),null,scheme)) : Optional.empty(),
                new BookingAcceptanceVerifier.ApplicantAuthority() {
                    public boolean isPatient(Connection c,String applicant,long id) {
                        return "SYNTHETIC patient".equals(applicant) && id==88001;
                    }
                    public boolean mayAcceptBooking(Connection c,String applicant,InitialBookingReview.AcceptedScheme s,String grant) { return false; }
                },CLOCK);
    }

    static ObjectNode create(String start) {
        var input = JSON.createObjectNode().put("patientId","88001").put("acceptanceEvidenceReference","SYNTHETIC acceptance");
        input.set("scheme",scheme(start,"INITIAL_CURRENT"));
        return input;
    }

    private static ObjectNode scheme(String start,String mode) {
        var at = time(start);
        var scheme = JSON.createObjectNode().put("startsAt",at.toString()).put("endsAt",at.plusSeconds(1620).toString())
                .put("treatmentRequest","SYNTHETIC request");
        scheme.putObject("effectiveScheduleRef").put("scheduleId","87001").put("versionNo",2);
        scheme.putObject("durationBasis").put("schemaVersion",1).put("basisMode",mode).put("primaryProjectId","85001")
                .put("projectVersion",1).put("adoptedDurationMinutes",27);
        return scheme;
    }

    private static ObjectNode finding(AppointmentRepository.Appointment row,String value) {
        return JSON.createObjectNode().put("expectedAppointmentToken",AppointmentReadService.token(row))
                .put("finding",value).put("verificationBasis","SYNTHETIC human evidence");
    }

    private AppointmentRepository.Appointment read(IsolatedData db,long id,long clinic) throws SQLException {
        try (var c=db.getConnection()) { return repository.findAppointment(c,id,clinic).orElseThrow(); }
    }

    static int count(Connection c,String sql) throws SQLException {
        try (var s=c.createStatement(); var r=s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getInt(1);
        }
    }

    private static Instant time(String value) { return Instant.parse("2026-11-08T"+value+"Z"); }
}
