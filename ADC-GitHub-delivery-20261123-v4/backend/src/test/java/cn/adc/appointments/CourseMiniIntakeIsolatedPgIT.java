package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CourseMiniIntakeIsolatedPgIT {
    @Test void courseDirectoryAndIntakeUseTheSameDatabaseAndReturnTheOriginalReceipt() throws Exception {
        var config = new CourseMiniIntakeConfiguration();
        var tx = config.courseMiniTransactions(new StandardEnvironment());
        var sessions = new MiniRequestSession(
                code -> "SYNTHETIC-code".equals(code) ? Optional.of("SYNTHETIC-course-mini") : Optional.empty(),
                new WeChatCourseSessionConfiguration.ExpiringSessions(java.time.Clock.systemUTC()));
        var controllers = new java.util.ArrayList<Object>();
        controllers.add(new MiniIntakeController(sessions, config.courseMiniIntake(sessions, tx)));
        controllers.add(new CourseMiniClinicDirectory(sessions, tx));
        boolean patientIntake = System.getenv("ADC_COURSE_DB_PATIENT_INTAKE_PASSWORD") != null;
        boolean bookingJourney = System.getenv("ADC_COURSE_DB_WRITE_PASSWORD") != null;
        if (patientIntake) controllers.add(new PatientMaintenanceController(
                new CoursePatientIntakeConfiguration().coursePatientIntake(new StandardEnvironment())));
        var explicitGrants = new CourseMiniPatientScope(bookingJourney ? java.util.Map.of("SYNTHETIC-course-mini",
                java.util.List.of(new MiniAppointmentReadController.Authorization("SYNTHETIC-explicit-self", 100000,
                        MiniAppointmentReadController.Authorization.Kind.SELF, java.util.List.of("APPOINTMENT_READ"), 1)))
                : java.util.Map.of());
        if (bookingJourney) {
            controllers.add(new CourseAppointmentWrite(new StandardEnvironment()));
            controllers.add(new MiniAppointmentReadController(sessions,
                    CourseAppointmentRead.courseTransactions(new StandardEnvironment(), true), explicitGrants));
        }
        var mvc = MockMvcBuilders.standaloneSetup(controllers.toArray()).build();
        String root = "/api/v1/mini";
        var login = mvc.perform(post(root + "/sessions/wechat").contentType("application/json")
                        .content("{\"code\":\"SYNTHETIC-code\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.identityVerified").value(false)).andReturn();
        var token = "Bearer " + AuditDocuments.parse(login.getResponse().getContentAsString()).path("accessToken").asText();
        var directory = mvc.perform(get(root + "/booking/clinics").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].clinicName").value("SYNTHETIC A")).andReturn();
        String clinic = AuditDocuments.parse(directory.getResponse().getContentAsString()).path("items").get(0).path("clinicId").asText();
        String key = "0123456789abcdef0123456789abcdef";
        String payload = """
                {"type":"NEW","actingMode":"SELF","identity":{"patientIdentification":"SYNTHETIC classroom new patient",
                 "applicantInformation":"SYNTHETIC","contactInformation":"synthetic@example.invalid"},
                 "intendedClinicId":"%s","preferred":{"date":"2026-11-18"},
                 "treatmentNeed":"SYNTHETIC","bookingIntent":{"visitType":"INITIAL"}}
                """.formatted(clinic);
        String requestId = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            var request = mvc.perform(post(root + "/requests").header("Authorization", token)
                            .header("Idempotency-Key", key).contentType("application/json").content(payload))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.receiptStatus").value("CONFIRMED_RECEIVED")).andReturn();
            String id = AuditDocuments.parse(request.getResponse().getContentAsString()).path("requestId").asText();
            assertDoesNotThrow(() -> java.util.UUID.fromString(id));
            if (requestId != null) assertEquals(requestId, id);
            requestId = id;
        }
        mvc.perform(get(root + "/submission-receipts/" + key).param("operation", "op008").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestId").value(requestId));
        if (patientIntake) {
            var session = new org.springframework.mock.web.MockHttpSession();
            session.setAttribute(cn.adc.shared.security.StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                    new cn.adc.shared.security.StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC reception",
                            "SYNTHETIC-CSRF", new cn.adc.shared.config.SourceStamp("SYNTHETIC", "course", "test",
                            java.time.Instant.now(), true)));
            String profile = """
                    {"patientName":"SYNTHETIC new patient","identityInformation":"SYNTHETIC classroom new patient",
                     "contactInformation":"synthetic@example.invalid","source":{"kind":"MINI_REQUEST",
                     "requestId":"%s","expectedRequestVersion":0,"identityMatchBasis":"SYNTHETIC classroom identity"}}
                    """.formatted(requestId);
            String first = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                var registered = mvc.perform(post("/api/v1/staff/patients").session(session)
                                .header("X-CSRF-Token", "SYNTHETIC-CSRF").header("Idempotency-Key", "SYNTHETIC-staff-command")
                                .contentType("application/json").content(profile))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.sourceAssociation.requestId").value(requestId))
                        .andExpect(jsonPath("$.sourceAssociation.patientAccessGrantedByAssociation").value(false))
                        .andExpect(jsonPath("$.patient.patientId").value("100000")).andReturn();
                String body = registered.getResponse().getContentAsString();
                if (first != null) assertEquals(AuditDocuments.parse(first), AuditDocuments.parse(body));
                first = body;
            }
            Files.writeString(Path.of(System.getenv("ADC_COURSE_EVIDENCE"), "course-patient-receipt.json"), first);
            if (bookingJourney) {
                String bookingKey = "00000000-0000-4000-8000-000000000202";
                String booking = """
                        {"patientId":"100000","acceptanceEvidenceReference":"SYNTHETIC-course-booking-acceptance",
                         "sourceContextReference":"%s%s:1",
                         "scheme":{"effectiveScheduleRef":{"scheduleId":"87001","versionNo":2},
                         "startsAt":"2026-11-08T10:30:00Z","endsAt":"2026-11-08T10:57:00Z",
                         "treatmentRequest":"SYNTHETIC treatment","durationBasis":{"schemaVersion":1,
                         "basisMode":"INITIAL_CURRENT","primaryProjectId":"85001","projectVersion":1,
                         "adoptedDurationMinutes":27}}}
                        """.formatted(CourseMiniBookingContext.PREFIX, requestId);
                for (int attempt = 0; attempt < 2; attempt++) {
                    var booked = mvc.perform(post("/api/v1/staff/appointments").session(session)
                                    .header("X-CSRF-Token", "SYNTHETIC-CSRF").header("Idempotency-Key", bookingKey)
                                    .contentType("application/json").content(booking))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.businessOutcome").value("SUCCEEDED"))
                            .andExpect(jsonPath("$.links.appointmentId").value("100000")).andReturn();
                    Files.writeString(Path.of(System.getenv("ADC_COURSE_EVIDENCE"), "course-booking-receipt-" + attempt + ".json"),
                            booked.getResponse().getContentAsString());
                }
                var readback = mvc.perform(get(root + "/appointments").param("patientId", "100000")
                                .header("Authorization", token)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].appointmentId").value("100000"))
                        .andExpect(jsonPath("$.items[0].formalStatus").value("待到诊"))
                        .andExpect(jsonPath("$.items[0].factVerification").value("VERIFIED")).andReturn();
                Files.writeString(Path.of(System.getenv("ADC_COURSE_EVIDENCE"), "course-booking-mini-readback.json"),
                        readback.getResponse().getContentAsString());
                assertFalse(explicitGrants.mayRead(null, "SYNTHETIC-other-account", 100000));
            }
        }
        Files.writeString(Path.of(System.getenv("ADC_COURSE_EVIDENCE"), "course-mini-verified.txt"),
                "clinic=" + clinic + "\nrequest=" + requestId + "\n");
    }
}
