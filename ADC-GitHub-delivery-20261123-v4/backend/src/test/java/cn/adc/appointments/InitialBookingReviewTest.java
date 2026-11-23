package cn.adc.appointments;

import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockMakers;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the review using JDBC doubles. It does not connect to any server. */
class InitialBookingReviewTest {
    private static final long CLINIC = 81001;
    private static final long DENTIST = 79001;
    private static final long PROJECT = 85001;
    private static final long PATIENT = 88001;
    private static final long SCHEDULE = 87001;
    private static final UUID KEY = UUID.fromString("76c45f5b-6214-491b-966e-c3c82cb0034e");
    private static final String L1 = "SELECT pg_advisory_xact_lock(41001,?)";
    private static final String L2 = "SELECT pg_advisory_xact_lock_shared(41002,1)";
    private static final String L3 = "SELECT pg_advisory_xact_lock(?)";
    private static final String L4 = "SELECT dentist_id FROM adc.dentist WHERE dentist_id=? FOR UPDATE";
    private static final String L5 = "SELECT schedule_id FROM adc.reception_schedule_version WHERE schedule_id=? AND schedule_version_no=? FOR UPDATE";

    private final DataSource source = fake(DataSource.class);
    private final Connection connection = fake(Connection.class);
    private final PreparedStatement requestLock = fake(PreparedStatement.class);
    private final PreparedStatement catalogLock = fake(PreparedStatement.class);
    private final PreparedStatement scheduleLock = fake(PreparedStatement.class);
    private final PreparedStatement dentistLock = fake(PreparedStatement.class);
    private final PreparedStatement versionLock = fake(PreparedStatement.class);
    private final PreparedStatement catalog = fake(PreparedStatement.class);
    private final PreparedStatement schedules = fake(PreparedStatement.class);
    private final InitialBookingReview.AcceptanceEvidence acceptance = fake(InitialBookingReview.AcceptanceEvidence.class);
    private JdbcTransactions transactions;
    private StaffAuthorization authorization;
    private ClinicDirectoryProvider clinics;
    private InitialBookingReview review;
    private int currentVersion = 2;
    private boolean hasCurrent = true;

    private static <T> T fake(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.PROXY));
    }

    @BeforeEach void setUp() throws Exception {
        when(source.getConnection()).thenReturn(connection);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.createStatement()).thenReturn(fake(Statement.class));
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            return switch (sql) {
                case L1 -> requestLock;
                case L2 -> catalogLock;
                case L3 -> scheduleLock;
                case L4 -> dentistLock;
                case L5 -> versionLock;
                default -> {
                    if (sql.startsWith("SELECT row_to_json(t)::text FROM adc.project_duration_version ")) yield catalog;
                    if (sql.startsWith("SELECT clinic_id,dentist_id,starts_at,ends_at,activation_status")) yield schedules;
                    throw new AssertionError("Unexpected statement: " + sql);
                }
            };
        });
        when(catalog.executeQuery()).thenAnswer(call -> {
            var row = fake(ResultSet.class);
            when(row.next()).thenReturn(hasCurrent);
            when(row.getString(1)).thenReturn("""
                    {"project_id":85001,"duration_version_no":%d,"duration_minutes":27,"publication_status":"CURRENT"}
                    """.formatted(currentVersion));
            return row;
        });
        when(schedules.executeQuery()).thenAnswer(call -> scheduleRow("EFFECTIVE"));
        when(dentistLock.executeQuery()).thenAnswer(call -> presentRow());
        when(versionLock.executeQuery()).thenAnswer(call -> presentRow());
        when(acceptance.matches(eq(connection), eq("synthetic-acceptance"), any())).thenReturn(true);
        var grant = new StaffAuthorization.Grant("synthetic-policy", Set.of("BOOKING_RECEPTION"), Set.of("UC02"), Set.of(CLINIC), false);
        authorization = new StaffAuthorization(() -> Optional.of("synthetic-staff"), () -> Map.of("synthetic-staff", grant));
        var stamp = new SourceStamp("synthetic-clinics", "fixture-1", "fixture", Instant.EPOCH, true);
        transactions = new JdbcTransactions(source);
        clinics = id -> new ClinicDirectoryProvider.Clinic(id, "synthetic", ZoneId.of("Asia/Shanghai"), stamp);
        review = new InitialBookingReview(transactions, authorization, clinics, acceptance);
    }

    @Test void validFirstModeUsesExactAcceptedSchemeAndOriginalLockOrder() throws Exception {
        var result = review.review(KEY, input());
        assertEquals(2, result.duration().versionNo());
        assertEquals(27, result.duration().minutes());
        assertEquals(PATIENT, result.patientId());
        assertEquals(PROJECT, result.duration().projectId());
        assertEquals(Instant.parse("2026-11-08T09:27:00.123456Z"), result.endsAt());
        var ordered = inOrder(requestLock, catalogLock, catalog, scheduleLock, schedules, dentistLock, versionLock, acceptance, connection);
        ordered.verify(requestLock).execute();
        ordered.verify(catalogLock).execute();
        ordered.verify(schedules).executeQuery();
        ordered.verify(catalog).executeQuery();
        ordered.verify(scheduleLock).execute();
        ordered.verify(dentistLock).executeQuery();
        ordered.verify(versionLock).executeQuery();
        ordered.verify(schedules).executeQuery();
        ordered.verify(acceptance).matches(connection, "synthetic-acceptance", result);
        ordered.verify(connection).commit();
    }

    @Test void nonFirstModesAreRejectedBeforeOpeningTransaction() {
        for (String mode : Set.of("PRESERVED_ORIGINAL", "CHANGED_PROJECT_CURRENT")) {
            var request = input();
            basis(request).put("basisMode", mode);
            assertEquals("INITIAL_CURRENT_REQUIRED", assertThrows(Rejection.class, () -> review.review(KEY, request)).getMessage());
        }
        verifyNoInteractions(source);
    }

    @Test void missingModeIsRejectedWithoutUsingDefaultOrOpeningTransaction() {
        var request = input();
        basis(request).remove("basisMode");
        assertThrows(IllegalArgumentException.class, () -> review.review(KEY, request));
        verifyNoInteractions(source);
    }

    @Test void newerVersionWithSameMinutesRequiresReconfirmation() throws Exception {
        currentVersion = 3;
        assertEquals("STALE_DURATION_BASIS", assertThrows(Rejection.class, () -> review.review(KEY, input())).getMessage());
        verify(connection).rollback();
        verify(connection, never()).commit();
        verifyNoInteractions(scheduleLock, acceptance);
    }

    @Test void publicationAtSharedLockBoundaryIsObservedByNewQuery() throws Exception {
        when(catalogLock.execute()).thenAnswer(call -> { currentVersion = 3; return true; });
        assertEquals("STALE_DURATION_BASIS", assertThrows(Rejection.class, () -> review.review(KEY, input())).getMessage());
        var order = inOrder(requestLock, catalogLock, catalog);
        order.verify(requestLock).execute();
        order.verify(catalogLock).execute();
        order.verify(catalog).executeQuery();
        verifyNoInteractions(acceptance);
    }

    @Test void missingCurrentDoesNotFallBackToPresentedVersion() throws Exception {
        hasCurrent = false;
        assertEquals("CURRENT_DURATION_REQUIRED", assertThrows(Rejection.class, () -> review.review(KEY, input())).getMessage());
        verify(connection).rollback();
        verifyNoInteractions(scheduleLock, acceptance);
    }

    @Test void effectiveStatusIsRereadAfterResourceLocks() throws Exception {
        var before = scheduleRow("EFFECTIVE");
        var after = scheduleRow("SUPERSEDED");
        when(schedules.executeQuery()).thenReturn(before, after);
        assertEquals("EFFECTIVE_SCHEDULE_REQUIRED", assertThrows(Rejection.class, () -> review.review(KEY, input())).getMessage());
        verify(versionLock).executeQuery();
        verify(schedules, times(2)).executeQuery();
        verifyNoInteractions(acceptance);
    }

    @Test void incorrectIntervalOrUnverifiedAcceptanceCannotPass() throws Exception {
        var incorrectEnd = input();
        incorrectEnd.path("scheme").asObject().put("endsAt", "2026-11-08T17:26:00.123456+08:00");
        assertEquals("INVALID_BOOKING_INTERVAL", assertThrows(Rejection.class, () -> review.review(KEY, incorrectEnd)).getMessage());
        when(acceptance.matches(eq(connection), anyString(), any())).thenReturn(false);
        assertEquals("ACCEPTANCE_RECONFIRMATION_REQUIRED", assertThrows(Rejection.class, () -> review.review(KEY, input())).getMessage());
        verify(connection, times(2)).rollback();
        verify(connection, never()).commit();
    }

    @Test void internalWorkCannotReviewUsingSomeoneElsesRequestLock() {
        assertThrows(JdbcTransactions.Failure.class, () -> transactions.execute(c -> {
            var locks = new ResourceLockCoordinator().scope(c);
            locks.command("wrong-reference");
            return review.reviewLocked(c, locks, "synthetic-staff", KEY, input());
        }));
        verifyNoInteractions(catalogLock, catalog, acceptance);
    }

    @Test void receptionistRevokedDuringAcceptanceCannotCompleteReview() throws Exception {
        var revoked = new AtomicBoolean();
        var grant = new StaffAuthorization.Grant("synthetic-policy", Set.of("BOOKING_RECEPTION"),
                Set.of("UC02"), Set.of(CLINIC), false);
        var changingAuthorization = new StaffAuthorization(() -> Optional.of("synthetic-staff"),
                () -> revoked.get() ? Map.of() : Map.of("synthetic-staff", grant));
        var guarded = new InitialBookingReview(transactions, changingAuthorization, clinics, acceptance);
        when(acceptance.matches(eq(connection), eq("synthetic-acceptance"), any())).thenAnswer(call -> {
            revoked.set(true);
            return true;
        });

        assertThrows(SecurityException.class, () -> guarded.review(KEY, input()));
        var order = inOrder(versionLock, acceptance, connection);
        order.verify(versionLock).executeQuery();
        order.verify(acceptance).matches(eq(connection), eq("synthetic-acceptance"), any());
        order.verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @ParameterizedTest
    @CsvSource({"81002, 79001", "81001, 79002"})
    void t02501ChangedClinicOrDentistAfterLockRejectsWithoutAcceptance(long clinic, long dentist) throws Exception {
        var before = scheduleRow("EFFECTIVE");
        var changed = scheduleRow("EFFECTIVE");
        when(changed.getLong(1)).thenReturn(clinic);
        when(changed.getLong(2)).thenReturn(dentist);
        when(schedules.executeQuery()).thenReturn(before, changed);

        assertEquals("STALE_SCHEDULE", assertThrows(Rejection.class, () -> review.review(KEY, input())).getMessage());
        var order = inOrder(schedules, dentistLock, versionLock, connection);
        order.verify(schedules).executeQuery();
        order.verify(dentistLock).setLong(1, DENTIST);
        order.verify(dentistLock).executeQuery();
        order.verify(versionLock).executeQuery();
        order.verify(schedules).executeQuery();
        order.verify(connection).rollback();
        verifyNoInteractions(acceptance);
        verify(connection, never()).commit();
    }

    @Test void t02502OutOfScopeFirstReadRejectsBeforeAcquiringResourceLocks() throws Exception {
        var otherClinic = scheduleRow("EFFECTIVE");
        when(otherClinic.getLong(1)).thenReturn(81002L);
        when(schedules.executeQuery()).thenReturn(otherClinic);

        assertThrows(SecurityException.class, () -> review.review(KEY, input()));
        verify(connection).rollback();
        verify(connection, never()).commit();
        verifyNoInteractions(catalog, scheduleLock, dentistLock, versionLock, acceptance);
    }

    @ParameterizedTest
    @CsvSource({
            "17:00:00, 17:27:00, true",
            "16:59:59.999999, 17:26:59.999999, false",
            "18:33:00, 19:00:00, true",
            "18:33:00.000001, 19:00:00.000001, false",
            "19:00:00, 19:27:00, false"
    })
    void t02503ReviewChecksBothEdgesOfEffectiveWindow(String start, String end, boolean allowed) throws Exception {
        var request = input();
        request.path("scheme").asObject()
                .put("startsAt", "2026-11-08T" + start + "+08:00")
                .put("endsAt", "2026-11-08T" + end + "+08:00");
        if (allowed) {
            var result = review.review(KEY, request);
            assertEquals(OffsetDateTime.parse("2026-11-08T" + start + "+08:00").toInstant(), result.startsAt());
            assertEquals(OffsetDateTime.parse("2026-11-08T" + end + "+08:00").toInstant(), result.endsAt());
            verify(acceptance).matches(connection, "synthetic-acceptance", result);
            verify(connection).commit();
        } else {
            assertEquals("INVALID_BOOKING_INTERVAL", assertThrows(Rejection.class, () -> review.review(KEY, request)).getMessage());
            verifyNoInteractions(acceptance);
            verify(connection).rollback();
            verify(connection, never()).commit();
        }
    }

    @ParameterizedTest
    @CsvSource({"false, true", "true, true", "true, false"})
    void trustedAdapterChecksActualApplicantOnTheLockedReviewConnection(boolean proxy, boolean allowed) throws Exception {
        var now = Instant.parse("2026-11-08T08:30:00Z");
        var expected = new InitialBookingReview.AcceptedScheme(PATIENT, new ResourceLockCoordinator.Version(SCHEDULE, 2),
                CLINIC, DENTIST, Instant.parse("2026-11-08T09:00:00.123456Z"),
                Instant.parse("2026-11-08T09:27:00.123456Z"), "synthetic request",
                new cn.adc.duration.DurationPolicyService.Basis(PROJECT, 2, 27));
        var evidenceSource = fake(BookingAcceptanceVerifier.Source.class);
        var authority = fake(BookingAcceptanceVerifier.ApplicantAuthority.class);
        var stamp = new SourceStamp("synthetic-source", "1", "fixture", now, true);
        var applicant = proxy ? "synthetic-proxy" : "synthetic-patient";
        var grantRef = "synthetic-proxy-authorization";
        var confirmation = new BookingAcceptanceVerifier.Confirmation("synthetic-acceptance",
                stamp, applicant, proxy ? grantRef : null,
                now.minusSeconds(1), null, expected);
        when(evidenceSource.find(connection, "synthetic-acceptance")).thenReturn(Optional.of(confirmation));
        if (proxy) when(authority.mayAcceptBooking(connection, applicant, expected, grantRef)).thenReturn(allowed);
        else when(authority.isPatient(connection, applicant, PATIENT)).thenReturn(allowed);
        var staffIdentity = new StaffSessionIdentityAdapter(() ->
                new StaffSessionIdentityAdapter.VerifiedSession("synthetic-staff", "synthetic-csrf", stamp));
        var staffGrant = new StaffAuthorization.Grant("1", Set.of("BOOKING_RECEPTION"), Set.of("UC02"), Set.of(CLINIC), false);
        var bindings = new StaffBindingAdapter(() -> List.of(new StaffBindingAdapter.Entry("synthetic-staff", staffGrant, stamp)));
        var verifier = new BookingAcceptanceVerifier(evidenceSource, authority, Clock.fixed(now, ZoneOffset.UTC));
        var integrated = new InitialBookingReview(transactions, new StaffAuthorization(staffIdentity, bindings::bindings),
                clinics, verifier);

        if (allowed) {
            assertEquals(expected, integrated.review(KEY, input()));
        } else {
            assertEquals("ACCEPTANCE_RECONFIRMATION_REQUIRED",
                    assertThrows(Rejection.class, () -> integrated.review(KEY, input())).getMessage());
            verify(connection, never()).commit();
        }
        var order = inOrder(versionLock, evidenceSource, authority, connection);
        order.verify(versionLock).executeQuery();
        order.verify(evidenceSource).find(connection, "synthetic-acceptance");
        if (proxy) order.verify(authority).mayAcceptBooking(connection, applicant, expected, grantRef);
        else order.verify(authority).isPatient(connection, applicant, PATIENT);
        if (allowed) order.verify(connection).commit();
        else order.verify(connection).rollback();
    }

    private static ResultSet presentRow() throws Exception {
        var row = fake(ResultSet.class);
        when(row.next()).thenReturn(true);
        return row;
    }

    private static ResultSet scheduleRow(String status) throws Exception {
        var row = presentRow();
        when(row.getLong(1)).thenReturn(CLINIC);
        when(row.getLong(2)).thenReturn(DENTIST);
        when(row.getObject(3, OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-11-08T09:00:00Z"));
        when(row.getObject(4, OffsetDateTime.class)).thenReturn(OffsetDateTime.parse("2026-11-08T11:00:00Z"));
        when(row.getString(5)).thenReturn(status);
        return row;
    }

    private static ObjectNode input() {
        return JSON.readTree("""
                {"patientId":"88001","acceptanceEvidenceReference":"synthetic-acceptance","scheme":{
                  "effectiveScheduleRef":{"scheduleId":"87001","versionNo":2},
                  "startsAt":"2026-11-08T17:00:00.123456+08:00","endsAt":"2026-11-08T17:27:00.123456+08:00",
                  "treatmentRequest":"synthetic request",
                  "durationBasis":{"basisMode":"INITIAL_CURRENT","schemaVersion":1,"primaryProjectId":"85001",
                                   "projectVersion":2,"adoptedDurationMinutes":27}}}
                """).asObject();
    }

    private static ObjectNode basis(ObjectNode input) { return input.at("/scheme/durationBasis").asObject(); }
}
