package cn.adc.appointments;

import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AppointmentReadServiceTest {
    private static final long CLINIC = 81001;
    private static final long ID = 901;
    private static final String SUBJECT = "SYNTHETIC-booking-reception";
    private static final Instant START = Instant.parse("2026-11-08T09:00:00.123456Z");
    private static final Instant END = START.plusSeconds(1620);
    private static final SourceStamp STAMP = new SourceStamp("SYNTHETIC", "demo-v1", "test only", START, true);
    private final DataSource source = fake(DataSource.class);
    private final Connection connection = fake(Connection.class);
    private final PreparedStatement query = fake(PreparedStatement.class);
    private final List<Integer> candidates = new ArrayList<>(List.of(2));
    private final Map<Integer,Long> keys = new HashMap<>();
    private boolean authenticated = true;
    private boolean allowed = true;
    private boolean revokeDuringQuery;
    private boolean covers = true;
    private boolean sameResource = true;
    private boolean conflict;
    private boolean uncertain;
    private boolean representative;
    private String statusLabel = "待到诊";
    private String duration = """
            {"treatmentRequest":"SYNTHETIC","bookingDurationBasis":{"schemaVersion":1,
            "primaryProjectId":"85001","projectVersion":1,"projectNameSnapshot":"SYNTHETIC old",
            "adoptedDurationMinutes":27,"basisMode":"INITIAL_CURRENT","timeInterpretation":{
            "clinicId":"81001","zoneId":"Asia/Shanghai","zonePolicyVersion":"demo-v1","tzdbVersion":"demo",
            "localEndSource":"DERIVED_FROM_DURATION","sourceLocalStart":"2026-11-08T17:00:00.123456",
            "sourceLocalEnd":"2026-11-08T17:27:00.123456","selectedOffsetStart":"+08:00",
            "selectedOffsetEnd":"+08:00","resolvedInstantStart":"2026-11-08T09:00:00.123456Z",
            "resolvedInstantEnd":"2026-11-08T09:27:00.123456Z"}}}
            """;
    private StaffAuthorization.Grant grant = grant("BOOKING_RECEPTION", "UC02");
    private AppointmentReadService reads;
    private MockMvc mvc;

    @BeforeEach void setUp() throws Exception {
        var session = new StaffSessionIdentityAdapter(() -> authenticated
                ? new StaffSessionIdentityAdapter.VerifiedSession(SUBJECT, "SYNTHETIC-CSRF", STAMP) : null);
        var bindings = new StaffBindingAdapter(() -> allowed
                ? List.of(new StaffBindingAdapter.Entry(SUBJECT, grant, STAMP)) : List.of());
        reads = new AppointmentReadService(new JdbcTransactions(source),
                new StaffAuthorization(session, bindings::bindings), Clock.fixed(START, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new DemoRoutes(reads)).build();
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(fake(Statement.class));
        when(connection.prepareStatement(anyString())).thenAnswer(i -> {
            String sql = i.getArgument(0);
            assertTrue(sql.contains("WHERE a.appointment_id=? AND origin.clinic_id=?"));
            assertTrue(sql.contains("v.schedule_id=origin.schedule_id AND v.activation_status='EFFECTIVE'"));
            assertTrue(sql.contains("b.appointment_id<>a.appointment_id"));
            assertTrue(sql.contains("v.starts_at<=a.starts_at AND a.ends_at<=v.ends_at"));
            assertFalse(sql.contains("MAX("));
            return query;
        });
        doAnswer(i -> { keys.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(query).setLong(anyInt(), anyLong());
        when(query.executeQuery()).thenAnswer(i -> {
            if (revokeDuringQuery) allowed = false;
            return rows();
        });
    }

    @Test void historicalOriginalAndCurrentSupportAreIndependent() throws Exception {
        mvc.perform(get("/staff/appointments/901/schedule-support")).andExpect(status().isOk())
                .andExpect(jsonPath("$.originScheduleRef.versionNo").value(1))
                .andExpect(jsonPath("$.effectiveScheduleRef.versionNo").value(2))
                .andExpect(jsonPath("$.result").value("SUPPORTED"))
                .andExpect(jsonPath("$.patientId").doesNotExist());
        verify(query, never()).executeUpdate();
    }

    @Test void courseReceptionReadsTheSameAppointmentThroughBothPackagedRoutes() throws Exception {
        var course = MockMvcBuilders.standaloneSetup(new CourseAppointmentRead(CLINIC, new JdbcTransactions(source))).build();
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession("SYNTHETIC reception", "SYNTHETIC-CSRF", STAMP));
        var path = "/api/v1/staff/appointments/901";
        course.perform(get(path).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId").value("901"))
                .andExpect(jsonPath("$.durationBasis.projectVersion").value(1))
                .andExpect(jsonPath("$.reminderSummary").doesNotExist());
        course.perform(get(path + "/schedule-support").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.originScheduleRef.versionNo").value(1))
                .andExpect(jsonPath("$.effectiveScheduleRef.versionNo").value(2));
        verify(query, times(2)).setLong(2, CLINIC);
        verify(query, never()).executeUpdate();
    }

    @Test void patientAcceptanceDetailReturnsFrozenVersionAndNoPrivateNotes() throws Exception {
        assertDetail();
        assertFalse(reads.detail(CLINIC, ID).orElseThrow().toString().contains("SYNTHETIC-private"));
    }

    @Test void representativeAppointmentUsesSameStaffScopeAndDoesNotExposeAuthority() throws Exception {
        representative = true;
        assertDetail();
        assertFalse(reads.detail(CLINIC, ID).orElseThrow().toString().contains("SYNTHETIC-proxy"));
    }

    @Test void oldOpaqueTextNeverReceivesInventedDurationAndTokenFollowsRow() {
        var first = reads.detail(CLINIC, ID).orElseThrow();
        assertEquals(first.path("stateToken"), reads.detail(CLINIC, ID).orElseThrow().path("stateToken"));
        duration = "SYNTHETIC legacy document";
        statusLabel = "已取消";
        var old = reads.detail(CLINIC, ID).orElseThrow();
        assertFalse(old.has("durationBasis"));
        assertNotEquals(first.path("stateToken"), old.path("stateToken"));
    }

    @Test void absentOrMultipleEffectiveVersionsAreUnresolved() {
        candidates.clear();
        assertReason("UNRESOLVED", "NO_EFFECTIVE_VERSION");
        candidates.addAll(List.of(2, 3));
        assertReason("UNRESOLVED", "MULTIPLE_EFFECTIVE_VERSIONS");
    }

    @Test void shortWindowChangedResourceAndConflictCannotBeSupported() {
        covers = false;
        assertReason("NOT_SUPPORTED", "WINDOW_NOT_COVERED");
        covers = true;
        sameResource = false;
        assertReason("NOT_SUPPORTED", "RESOURCE_CHANGED");
        sameResource = true;
        conflict = true;
        assertReason("NOT_SUPPORTED", "RESOURCE_CONFLICT");
        conflict = false;
        uncertain = true;
        assertReason("UNRESOLVED", "OCCUPANCY_NOT_VERIFIED");
    }

    @Test void crossClinicCannotQueryAndUnknownIdIsNotFound() throws Exception {
        assertThrows(SecurityException.class, () -> reads.detail(CLINIC + 1, ID));
        verifyNoInteractions(source);
        mvc.perform(get("/staff/appointments/902")).andExpect(status().isNotFound());
        mvc.perform(get("/staff/appointments/902/schedule-support")).andExpect(status().isNotFound());
    }

    @Test void noSessionAndUnrelatedRoleAreDenied() throws Exception {
        authenticated = false;
        mvc.perform(get("/staff/appointments/901/schedule-support")).andExpect(status().isUnauthorized());
        mvc.perform(get("/staff/appointments/901")).andExpect(status().isForbidden());
        authenticated = true;
        grant = grant("HQ", "DURATION_PUBLISH");
        mvc.perform(get("/staff/appointments/901")).andExpect(status().isForbidden());
        verifyNoInteractions(source);
    }

    @Test void originalManagerAndClinicReceptionRolesReadScopedMinimum() throws Exception {
        grant = grant("CLINIC_MANAGER", "UC21");
        assertDetail();
        grant = grant("CLINIC_RECEPTION", "UC05");
        assertDetail();
    }

    @Test void revocationDuringQueryPreventsResponse() throws Exception {
        revokeDuringQuery = true;
        mvc.perform(get("/staff/appointments/901")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.patientId").doesNotExist());
        verify(connection).rollback();
    }

    @Test void invalidIdAndDependencyFailureAreSanitized() throws Exception {
        mvc.perform(get("/staff/appointments/9223372036854775808/schedule-support")).andExpect(status().isBadRequest());
        mvc.perform(get("/staff/appointments/9223372036854775808")).andExpect(status().isNotFound());
        when(query.executeQuery()).thenThrow(new SQLException("SYNTHETIC-private-SQL", "08006"));
        for (String suffix : List.of("", "/schedule-support")) {
            mvc.perform(get("/staff/appointments/901" + suffix)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("APPOINTMENT_READ_UNAVAILABLE"));
        }
    }

    private void assertDetail() throws Exception {
        mvc.perform(get("/staff/appointments/901")).andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value("88001"))
                .andExpect(jsonPath("$.stateToken").isNotEmpty())
                .andExpect(jsonPath("$.durationBasis.projectVersion").value(1))
                .andExpect(jsonPath("$.reminderSummary").doesNotExist());
    }

    private void assertReason(String outcome, String reason) {
        var result = reads.support(CLINIC, ID).orElseThrow();
        assertEquals(outcome, result.path("result").asText());
        assertEquals(reason, result.path("reasons").get(0).asText());
        assertFalse(result.has("effectiveScheduleRef"));
    }

    private ResultSet rows() throws SQLException {
        var row = fake(ResultSet.class);
        var index = new AtomicInteger(-1);
        when(row.next()).thenAnswer(i -> keys.equals(Map.of(1, ID, 2, CLINIC))
                && index.incrementAndGet() < Math.max(1, candidates.size()));
        when(row.getLong(1)).thenReturn(ID);
        when(row.getLong(2)).thenReturn(88001L);
        when(row.getObject(3, Long.class)).thenReturn(87001L);
        when(row.getObject(4, Integer.class)).thenReturn(1);
        when(row.getObject(5, OffsetDateTime.class)).thenReturn(START.atOffset(ZoneOffset.UTC));
        when(row.getObject(6, OffsetDateTime.class)).thenReturn(END.atOffset(ZoneOffset.UTC));
        when(row.getString(7)).thenReturn(duration);
        when(row.getString(8)).thenReturn("SYNTHETIC-private-acceptance");
        when(row.getString(9)).thenReturn(statusLabel);
        when(row.getString(11)).thenReturn("SYNTHETIC-private-reminder");
        when(row.getString(13)).thenReturn(SUBJECT);
        when(row.getObject(14, OffsetDateTime.class)).thenReturn(START.atOffset(ZoneOffset.UTC));
        if (representative) when(row.getString(15)).thenReturn("SYNTHETIC-proxy");
        when(row.getObject(17, Integer.class)).thenAnswer(i -> candidates.isEmpty() ? null : candidates.get(index.get()));
        when(row.getBoolean(18)).thenReturn(sameResource);
        when(row.getBoolean(19)).thenReturn(covers);
        when(row.getBoolean(20)).thenReturn(conflict);
        when(row.getBoolean(22)).thenReturn(uncertain);
        return row;
    }

    private static StaffAuthorization.Grant grant(String role, String action) {
        return new StaffAuthorization.Grant("demo-v1", Set.of(role), Set.of(action), Set.of(CLINIC), false);
    }

    private static <T> T fake(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.PROXY));
    }

    @RestController
    static class DemoRoutes {
        private final AppointmentController controller;
        DemoRoutes(AppointmentReadService reads) { controller = new AppointmentController(null, reads); }

        @GetMapping("/staff/appointments/{id}")
        ResponseEntity<String> detail(@PathVariable String id) { return response(controller.detail(CLINIC, id)); }

        @GetMapping("/staff/appointments/{id}/schedule-support")
        ResponseEntity<String> support(@PathVariable String id) { return response(controller.support(CLINIC, id)); }

        private static ResponseEntity<String> response(AppointmentController.Response response) {
            return ResponseEntity.status(response.status()).header("Content-Type", "application/json")
                    .body(response.body().toString());
        }
    }
}
