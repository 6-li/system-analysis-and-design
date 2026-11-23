package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService;
import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.shared.time.ClinicTimePolicy;
import cn.adc.shared.time.StrictInputPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static cn.adc.duration.DurationPolicyService.ChangeKind.FIRST;
import static org.junit.jupiter.api.Assertions.*;

/** UC02 fixture oracle and shared utility tests. No booking service, database, or activation. */
class FormalAppointmentTest {
    private static final long SYNTHETIC_DENTIST = 79001;
    private static final SyntheticSchedule SYNTHETIC_EFFECTIVE = new SyntheticSchedule(
            87001, 2, 81001, SYNTHETIC_DENTIST, "EFFECTIVE", window("09:00:00", "11:00:00"));

    @Test void adjacentAppointmentsCanShareOneEffectiveWindow() {
        var held = new SyntheticBooking(SYNTHETIC_EFFECTIVE, window("09:00:00", "09:27:00"));
        var proposed = window("09:27:00", "09:54:00");
        assertTrue(fixtureAllows(SYNTHETIC_EFFECTIVE, held.slot(), List.of()));
        assertTrue(fixtureAllows(SYNTHETIC_EFFECTIVE, proposed, List.of(held)));
    }

    @Test void sameDentistConflictsAcrossClinicAndScheduleAtOneMicrosecond() {
        var otherClinic = new SyntheticSchedule(87002, 1, 81002, SYNTHETIC_DENTIST,
                "EFFECTIVE", window("09:00:00", "11:00:00"));
        var held = new SyntheticBooking(otherClinic, window("09:00:00", "09:27:00"));
        var start = StrictInputPolicy.instant("2026-11-08T17:26:59.999999+08:00");
        var end = StrictInputPolicy.instant("2026-11-08T17:53:59.999999+08:00");
        assertFalse(fixtureAllows(SYNTHETIC_EFFECTIVE, new Window(start, end), List.of(held)));
    }

    @Test void simultaneousAppointmentsForDifferentDentistsDoNotShareThisCapacity() {
        var anotherDentist = new SyntheticSchedule(87003, 1, 81001, 79002, "EFFECTIVE",
                SYNTHETIC_EFFECTIVE.window());
        var slot = window("09:00:00", "09:27:00");
        assertTrue(fixtureAllows(SYNTHETIC_EFFECTIVE, slot, List.of(new SyntheticBooking(anotherDentist, slot))));
    }

    @Test void t02504DifferentDentistInOtherClinicDoesNotShareThisCapacity() {
        var otherClinicDentist = new SyntheticSchedule(87004, 1, 81002, 79002, "EFFECTIVE",
                SYNTHETIC_EFFECTIVE.window());
        var slot = window("09:00:00", "09:27:00");
        assertTrue(fixtureAllows(SYNTHETIC_EFFECTIVE, slot, List.of(new SyntheticBooking(otherClinicDentist, slot))));
    }

    @Test void effectiveWindowMustCoverTheWholeNonemptyAppointment() {
        assertTrue(fixtureAllows(SYNTHETIC_EFFECTIVE, window("10:33:00", "11:00:00"), List.of()));
        assertFalse(fixtureAllows(SYNTHETIC_EFFECTIVE, window("10:33:00.000001", "11:00:00.000001"), List.of()));
        assertFalse(fixtureAllows(SYNTHETIC_EFFECTIVE, window("08:59:59.999999", "09:26:59.999999"), List.of()));
        assertFalse(fixtureAllows(SYNTHETIC_EFFECTIVE, window("09:30:00", "09:30:00"), List.of()));
    }

    @Test void largerDraftVersionAndSupersededVersionAreNotBookingResources() {
        var slot = window("09:00:00", "09:27:00");
        var draft = new SyntheticSchedule(87001, 3, 81001, SYNTHETIC_DENTIST, "DRAFT", SYNTHETIC_EFFECTIVE.window());
        var old = new SyntheticSchedule(87001, 1, 81001, SYNTHETIC_DENTIST, "SUPERSEDED", SYNTHETIC_EFFECTIVE.window());
        assertTrue(fixtureAllows(SYNTHETIC_EFFECTIVE, slot, List.of()));
        assertFalse(fixtureAllows(draft, slot, List.of()));
        assertFalse(fixtureAllows(old, slot, List.of()));
    }

    @Test void firstBookingUsesTheSuppliedCurrentProjectVersionAndMinutes() {
        var current = new Basis(85001, 2, 27);
        assertEquals(current, DurationPolicyService.selectBasis(FIRST, new Basis(85001, 1, 13), current));
    }

    @Test void firstBookingCannotFallBackToAFormerVersionOrDefaultMinutes() {
        assertThrows(IllegalArgumentException.class, () -> DurationPolicyService.selectBasis(FIRST, new Basis(85001, 1, 13), null));
    }

    @Test void sharedDurationInputRejectsZeroNegativeFractionalAndMissingMinutes() {
        var mapper = new ObjectMapper();
        assertEquals(27, StrictInputPolicy.positiveMinutes(mapper.readTree("27")));
        for (String invalid : List.of("0", "-1", "27.5", "\"27\"", "null", "2147483648")) {
            assertThrows(IllegalArgumentException.class, () -> StrictInputPolicy.positiveMinutes(mapper.readTree(invalid)), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> StrictInputPolicy.positiveMinutes(null));
    }

    @Test void wholeMinuteDurationPreservesMicrosecondStartAndDerivesEnd() {
        var policy = new ClinicTimePolicy("SYNTHETIC-T025", Map.of(SYNTHETIC_EFFECTIVE.clinicId(), "Asia/Shanghai"));
        var basis = DurationPolicyService.selectBasis(FIRST, null, new Basis(85001, 2, 27));
        var resolved = policy.resolve(SYNTHETIC_EFFECTIVE.clinicId(), "2026-11-08T17:00:00.123456",
                ZoneOffset.ofHours(8), basis.minutes());
        assertEquals(at("09:00:00.123456"), resolved.start());
        assertEquals(at("09:27:00.123456"), resolved.end());
    }

    private record Window(Instant start, Instant end) {}
    private record SyntheticSchedule(long id, int version, long clinicId, long dentistId, String status, Window window) {}
    private record SyntheticBooking(SyntheticSchedule schedule, Window slot) {}

    // Executable expectation from the specification, not a production availability check.
    private static boolean fixtureAllows(SyntheticSchedule schedule, Window slot, List<SyntheticBooking> held) {
        return schedule.status().equals("EFFECTIVE")
                && slot.start().isBefore(slot.end())
                && !slot.start().isBefore(schedule.window().start()) && !slot.end().isAfter(schedule.window().end())
                && held.stream().noneMatch(existing -> existing.schedule().dentistId() == schedule.dentistId()
                    && slot.start().isBefore(existing.slot().end()) && existing.slot().start().isBefore(slot.end()));
    }

    private static Window window(String start, String end) { return new Window(at(start), at(end)); }
    private static Instant at(String time) { return StrictInputPolicy.instant("2026-11-08T" + time + "Z"); }
}
