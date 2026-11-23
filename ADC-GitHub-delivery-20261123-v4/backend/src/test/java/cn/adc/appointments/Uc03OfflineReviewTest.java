package cn.adc.appointments;

import cn.adc.appointments.BookingAcceptanceVerifier.ApplicantAuthority;
import cn.adc.appointments.BookingAcceptanceVerifier.Confirmation;
import cn.adc.appointments.InitialBookingReview.AcceptedScheme;
import cn.adc.duration.DurationPolicyService;
import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.duration.DurationPolicyService.ChangeKind;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Test-first UC03 boundary evidence. No controller, command wiring, or persistent appointment writes. */
class Uc03OfflineReviewTest {
    private static final long APPOINTMENT = 901, PATIENT = 88001, OLD_CLINIC = 81001, TARGET_CLINIC = 81002;
    private static final String ACTOR = "SYNTHETIC-UC03-reception", DEMAND = "SYNTHETIC-UC03-patient-demand";
    private static final String ACCEPTANCE = "SYNTHETIC-UC03-acceptance";
    private static final Instant START = instant("09:00:00.123456"), END = instant("09:27:00.123456");
    private static final Instant NOW = instant("08:00:00");
    private static final Basis FROZEN = new Basis(85001, 1, 27);
    private static final Basis CURRENT = new Basis(85001, 2, 27);
    private static final Basis OTHER_PROJECT = new Basis(85002, 1, 31);
    private static final Version TARGET = new Version(87002, 2);
    private static final AcceptedScheme PROPOSAL =
            new AcceptedScheme(PATIENT, TARGET, TARGET_CLINIC, 79002, START, END, DEMAND, CURRENT);

    @Test void u03r01RequiresReceptionAndBothClinicsAndRechecksRevocation() {
        var enabled = new AtomicBoolean(true);
        var both = auth("BOOKING_RECEPTION", Set.of(OLD_CLINIC, TARGET_CLINIC), enabled);
        var scope = Set.of(OLD_CLINIC, TARGET_CLINIC);
        assertEquals(ACTOR, both.require("UC03", scope).actor());
        for (long clinic : scope)
            assertThrows(SecurityException.class, () -> auth("BOOKING_RECEPTION", Set.of(clinic), enabled).require("UC03", scope));
        assertThrows(SecurityException.class, () -> auth("CLINIC_MANAGER", scope, enabled).require("UC03", scope));
        enabled.set(false);
        assertThrows(SecurityException.class, () -> both.require("UC03", scope));
    }

    @Test void u03r02ScheduleOrTimeChangeKeepsFrozenBasisAndChangedProjectUsesCurrent() {
        assertEquals(FROZEN, DurationPolicyService.selectBasis(ChangeKind.TIME_ONLY, FROZEN, CURRENT));
        assertEquals(FROZEN, DurationPolicyService.selectBasis(ChangeKind.SCHEDULE_ONLY, FROZEN, CURRENT));
        assertEquals(OTHER_PROJECT, DurationPolicyService.selectBasis(ChangeKind.PROJECT_CHANGED, FROZEN, OTHER_PROJECT));
        assertEquals(27, CURRENT.minutes());
        assertNotEquals(FROZEN, CURRENT);
    }

    @Test void u03r03NoFallbackWhenTheApplicableBasisIsMissingOrInvalid() {
        assertThrows(IllegalArgumentException.class, () -> DurationPolicyService.selectBasis(ChangeKind.PROJECT_CHANGED, FROZEN, null));
        assertThrows(IllegalArgumentException.class, () -> DurationPolicyService.selectBasis(ChangeKind.TIME_ONLY, null, CURRENT));
        assertThrows(IllegalArgumentException.class, () -> DurationPolicyService.selectBasis(ChangeKind.SCHEDULE_ONLY, null, CURRENT));
        for (String input : Set.of("0", "-1", "27.25", "null", "\"27\"")) {
            var minutes = new ObjectMapper().readTree(input);
            assertThrows(IllegalArgumentException.class, () -> StrictInputPolicy.positiveMinutes(minutes));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void u03r04ExactAcceptanceRequiresPatientOrStillAuthorizedRepresentative(boolean representative) throws Exception {
        var proxyGrant = new AtomicBoolean(true);
        var verifier = verifier(PROPOSAL, representative, proxyGrant, NOW.plusSeconds(1));
        assertTrue(verifier.matches(null, ACCEPTANCE, PROPOSAL));
        if (representative) {
            proxyGrant.set(false);
            assertFalse(verifier.matches(null, ACCEPTANCE, PROPOSAL));
        }
        assertFalse(verifier(PROPOSAL, representative, proxyGrant, NOW).matches(null, ACCEPTANCE, PROPOSAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"patient", "clinic", "dentist", "scheduleVersion", "project", "versionSameMinutes", "duration", "time", "demand"})
    void u03r05OldConfirmationCannotAuthorizeChangedScheme(String changed) throws Exception {
        var verifier = verifier(PROPOSAL, false, new AtomicBoolean(true), NOW.plusSeconds(10));
        var proposal = new AcceptedScheme(
                changed.equals("patient") ? PATIENT + 1 : PATIENT,
                changed.equals("scheduleVersion") ? new Version(TARGET.scheduleId(), 3) : TARGET,
                changed.equals("clinic") ? OLD_CLINIC : TARGET_CLINIC,
                changed.equals("dentist") ? 79001 : 79002,
                changed.equals("time") ? START.plusNanos(1000) : START,
                changed.equals("time") ? END.plusNanos(1000) : END,
                changed.equals("demand") ? "SYNTHETIC-CHANGED-DEMAND" : DEMAND,
                switch (changed) {
                    case "project" -> OTHER_PROJECT;
                    case "versionSameMinutes" -> FROZEN;
                    case "duration" -> new Basis(CURRENT.projectId(), CURRENT.versionNo(), 28);
                    default -> CURRENT;
                });
        assertFalse(verifier.matches(null, ACCEPTANCE, proposal));
    }

    @Test void u03r06FixtureKeepsPatientAndAppointmentIdentityAndProtectsOriginalFacts() {
        var original = new Appointment(APPOINTMENT, PATIENT, OLD_CLINIC, 79001, new Version(87001, 2),
                START.minusSeconds(3600), END.minusSeconds(3600), "待到诊");
        var observed = new Appointment(APPOINTMENT, PATIENT, TARGET_CLINIC, 79002, TARGET, START, END, "待到诊");
        // Expected fixtures under B 表4 and D 表51, no UC03 write-side service exists yet.
        assertEquals(original.id, observed.id);
        assertEquals(original.patient, observed.patient);
        assertNotEquals(original.schedule, observed.schedule);
        assertFalse(eligible(original, APPOINTMENT + 1, PATIENT));
        assertFalse(eligible(original, APPOINTMENT, PATIENT + 1));
        assertFalse(eligible(new Appointment(original.id, original.patient, original.clinic, original.dentist,
                original.schedule, original.startsAt, original.endsAt, "已取消"), APPOINTMENT, PATIENT));
        assertTrue(eligible(original, APPOINTMENT, PATIENT));
    }

    @Test void u03r07FixtureRejectsStaleNonEffectiveAndPartialWindow() {
        var published = new Version(87002, 3);
        assertTrue(covers(TARGET, TARGET, "EFFECTIVE", START, END));
        assertFalse(covers(TARGET, published, "EFFECTIVE", START, END));
        assertFalse(covers(TARGET, TARGET, "DRAFT", START, END));
        assertFalse(covers(TARGET, TARGET, "EFFECTIVE", START.minusNanos(1000), END));
        assertFalse(covers(TARGET, TARGET, "EFFECTIVE", START, END.plusNanos(1000)));
        assertFalse(covers(TARGET, TARGET, "EFFECTIVE", END, END));
    }

    @Test void u03r08ApprovedContractRequiresTokenEvidenceReasonAndSuccessHistory() throws Exception {
        Path path = Path.of("../specs/002-dentist-scheduling/contracts/api.openapi.json");
        if (!Files.isRegularFile(path)) path = Path.of("specs/002-dentist-scheduling/contracts/api.openapi.json");
        JsonNode api = new ObjectMapper().readTree(Files.readString(path));
        var op = api.at("/paths/~1staff~1appointments~1{appointmentId}~1changes/post");
        assertEquals("SCH-OP-014", op.path("operationId").asText());
        assertTrue(op.path("responses").has("409"));
        assertTrue(op.path("responses").has("202"));
        var required = api.at("/components/schemas/ChangeAppointmentInput/required");
        assertEquals(Set.of("expectedAppointmentToken", "scheme", "acceptanceEvidenceReference", "changeBasis"), strings(required));
        var receipt = api.at("/components/schemas/CommandReceipt/oneOf");
        var completed = receipt.valueStream().filter(n -> n.at("/properties/status/enum").isArray()
                && strings(n.at("/properties/status/enum")).contains("COMPLETED")).findFirst().orElseThrow();
        var change = completed.at("/allOf/0/oneOf").valueStream()
                .filter(n -> n.at("/properties/ucCode/enum").isArray()
                        && strings(n.at("/properties/ucCode/enum")).contains("UC03")).findFirst().orElseThrow();
        assertEquals(Set.of("appointmentId", "appointmentChangeId", "originalUcSuccessEventId"),
                strings(change.at("/properties/links/required")));
        var unknown = receipt.valueStream().filter(n -> n.at("/properties/status/enum").isArray()
                && strings(n.at("/properties/status/enum")).contains("UNKNOWN")).findFirst().orElseThrow();
        assertEquals(Set.of("links"), strings(unknown.at("/not/required")));
    }

    private static StaffAuthorization auth(String role, Set<Long> scope, AtomicBoolean enabled) {
        var grant = new StaffAuthorization.Grant("SYNTHETIC-UC03", Set.of(role), Set.of("UC03"), scope, false);
        return new StaffAuthorization(() -> Optional.of(ACTOR), () -> enabled.get() ? Map.of(ACTOR, grant) : Map.of());
    }

    private static BookingAcceptanceVerifier verifier(AcceptedScheme accepted, boolean representative,
                                                       AtomicBoolean proxyGrant, Instant validUntil) {
        var stamp = new SourceStamp("SYNTHETIC", "UC03-1", "fixture", NOW, true);
        var record = new Confirmation(ACCEPTANCE, stamp, representative ? "SYNTHETIC-proxy" : "SYNTHETIC-patient",
                representative ? "SYNTHETIC-proxy-grant" : null, NOW.minusSeconds(1), validUntil, accepted);
        return new BookingAcceptanceVerifier((c, key) -> Optional.of(record), new ApplicantAuthority() {
            public boolean isPatient(java.sql.Connection c, String subject, long patient) {
                return "SYNTHETIC-patient".equals(subject) && patient == PATIENT;
            }
            public boolean mayAcceptBooking(java.sql.Connection c, String subject, AcceptedScheme scheme, String ref) {
                return "SYNTHETIC-proxy".equals(subject) && "SYNTHETIC-proxy-grant".equals(ref)
                        && accepted.equals(scheme) && proxyGrant.get();
            }
        }, InstantSource.fixed(NOW));
    }

    private record Appointment(long id, long patient, long clinic, long dentist, Version schedule,
                               Instant startsAt, Instant endsAt, String status) {}
    private static boolean eligible(Appointment original, long id, long patient) {
        return original.id == id && original.patient == patient && original.status.equals("待到诊");
    }

    private static boolean covers(Version requested, Version observed, String status, Instant start, Instant end) {
        return requested.equals(observed) && status.equals("EFFECTIVE") && start.isBefore(end)
                && !start.isBefore(START) && !end.isAfter(END);
    }

    private static Set<String> strings(JsonNode array) {
        assertTrue(array.isArray(), array::toString);
        return array.valueStream().map(JsonNode::asText).collect(Collectors.toSet());
    }

    private static Instant instant(String time) { return Instant.parse("2026-11-08T" + time + "Z"); }
}
