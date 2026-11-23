package cn.adc.appointments;

import cn.adc.appointments.BookingAcceptanceVerifier.Confirmation;
import cn.adc.appointments.InitialBookingReview.AcceptedScheme;
import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingAcceptanceVerifierTest {
    private static final String REF = "SYNTHETIC-UC02-confirmation";
    private static final String SELF = "SYNTHETIC-UC02-patient";
    private static final String PROXY = "SYNTHETIC-UC02-representative";
    private static final String GRANT = "SYNTHETIC-UC02-authority";
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final Instant START = NOW.plusSeconds(3600);
    private static final Instant END = START.plusSeconds(1800);
    private static final Basis BASIS = new Basis(8, 3, 30);
    private static final Version EFFECTIVE_FIXTURE = new Version(6, 2);
    private static final String REQUEST = "SYNTHETIC-UC02-intent";
    private static final AcceptedScheme SCHEME = scheme(1, EFFECTIVE_FIXTURE, 2, 3, START, END, REQUEST, BASIS);

    private final Connection transaction = mock(Connection.class);
    private final BookingAcceptanceVerifier.Source source = mock(BookingAcceptanceVerifier.Source.class);
    private final BookingAcceptanceVerifier.ApplicantAuthority authority =
            mock(BookingAcceptanceVerifier.ApplicantAuthority.class);
    private final BookingAcceptanceVerifier verifier =
            new BookingAcceptanceVerifier(source, authority, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void actualPatientMatchesTrustedIdentityAndScheme() throws SQLException {
        supply(SELF, null, true, null);
        when(authority.isPatient(transaction, SELF, 1)).thenReturn(true);
        assertTrue(verifier.matches(transaction, REF, SCHEME));
        verify(authority).isPatient(transaction, SELF, 1);
        verifyNoMoreInteractions(authority);
    }

    @Test void proxyMustHaveVerifiedCurrentAuthorityForThisPatient() throws SQLException {
        supply(PROXY, GRANT, true, NOW.plusSeconds(60));
        when(authority.mayAcceptBooking(transaction, PROXY, SCHEME, GRANT)).thenReturn(true, false);
        assertTrue(verifier.matches(transaction, REF, SCHEME));
        assertFalse(verifier.matches(transaction, REF, SCHEME));
        verify(authority, times(2)).mayAcceptBooking(transaction, PROXY, SCHEME, GRANT);
        verifyNoMoreInteractions(authority);
    }

    @Test void receptionistOrProxyWithoutVerifiedAuthorityCannotStandInForPatient() throws SQLException {
        supply("SYNTHETIC-UC02-receptionist", null, true, null);
        assertFalse(verifier.matches(transaction, REF, SCHEME));
        supply(PROXY, " ", true, null);
        assertFalse(verifier.matches(transaction, REF, SCHEME));
    }

    @Test void missingUnusableOrExpiredEvidenceFailsBeforeIdentityCheck() throws SQLException {
        when(source.find(transaction, REF)).thenReturn(Optional.empty());
        assertFalse(verifier.matches(transaction, REF, SCHEME));
        supply(SELF, null, false, null);
        assertFalse(verifier.matches(transaction, REF, SCHEME));
        supply(SELF, null, true, NOW);
        assertFalse(verifier.matches(transaction, REF, SCHEME));
        verifyNoInteractions(authority);
    }

    @Test void sourceFailureRemainsDependencyFailure() throws SQLException {
        var outage = new SQLException("SYNTHETIC source outage");
        when(source.find(transaction, REF)).thenThrow(outage);
        assertSame(outage, assertThrows(SQLException.class, () -> verifier.matches(transaction, REF, SCHEME)));
        verifyNoInteractions(authority);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sourceExpiryDuringAuthorityLookupCannotReturnAcceptance(boolean proxy) throws SQLException {
        var expiry = NOW.plusNanos(1000);
        supply(proxy ? PROXY : SELF, proxy ? GRANT : null, true, expiry);
        var clock = mock(InstantSource.class);
        when(clock.instant()).thenReturn(NOW, expiry);
        if (proxy) when(authority.mayAcceptBooking(transaction, PROXY, SCHEME, GRANT)).thenReturn(true);
        else when(authority.isPatient(transaction, SELF, SCHEME.patientId())).thenReturn(true);
        var review = new BookingAcceptanceVerifier(source, authority, clock);
        assertFalse(review.matches(transaction, REF, SCHEME));
        verify(clock, times(2)).instant();
    }

    @Test void blankReferenceOrSourceReturningWrongRecordCannotReachAuthority() throws SQLException {
        assertFalse(verifier.matches(transaction, " ", SCHEME));
        verifyNoInteractions(source);
        var stamp = new SourceStamp("SYNTHETIC-fixture", "1", "SYNTHETIC-verifier", NOW, true);
        when(source.find(transaction, REF)).thenReturn(Optional.of(
                new Confirmation("SYNTHETIC-wrong-reference", stamp, SELF, null, NOW, null, SCHEME)));
        assertFalse(verifier.matches(transaction, REF, SCHEME));
        verifyNoInteractions(authority);
    }

    @ParameterizedTest
    @MethodSource("changedSchemes")
    void rejectsEvidenceFromAnyOtherScheme(AcceptedScheme other) throws SQLException {
        supply(SELF, null, true, null);
        assertFalse(verifier.matches(transaction, REF, other));
        verifyNoInteractions(authority);
    }

    @Test void staleReviewMapsOnlyItsOwnConflictWithoutSuccessLinks() {
        var response = BookingReviewFeedback.forRejectedReview("STALE_DURATION_BASIS").orElseThrow();
        assertEquals(409, response.status());
        assertEquals("STALE_DURATION_BASIS", response.body().code());
        assertTrue(response.body().message().contains("重新匹配"));
        assertTrue(BookingReviewFeedback.forRejectedReview("COMMIT_OUTCOME_UNKNOWN").isEmpty());
    }

    private void supply(String subject, String grant, boolean usable, Instant expiry) throws SQLException {
        var stamp = new SourceStamp("SYNTHETIC-fixture", "1", "SYNTHETIC-verifier", NOW, usable);
        when(source.find(transaction, REF)).thenReturn(Optional.of(
                new Confirmation(REF, stamp, subject, grant, NOW.minusSeconds(5), expiry, SCHEME)));
    }

    private static Stream<AcceptedScheme> changedSchemes() {
        return Stream.of(
                scheme(11, EFFECTIVE_FIXTURE, 2, 3, START, END, REQUEST, BASIS),
                scheme(1, new Version(6, 3), 2, 3, START, END, REQUEST, BASIS),
                scheme(1, EFFECTIVE_FIXTURE, 12, 3, START, END, REQUEST, BASIS),
                scheme(1, EFFECTIVE_FIXTURE, 2, 13, START, END, REQUEST, BASIS),
                scheme(1, EFFECTIVE_FIXTURE, 2, 3, START.plusNanos(1000), END, REQUEST, BASIS),
                scheme(1, EFFECTIVE_FIXTURE, 2, 3, START, END.plusNanos(1000), REQUEST, BASIS),
                scheme(1, EFFECTIVE_FIXTURE, 2, 3, START, END, "SYNTHETIC-changed", BASIS),
                scheme(1, EFFECTIVE_FIXTURE, 2, 3, START, END, REQUEST, new Basis(18, 3, 30)),
                scheme(1, EFFECTIVE_FIXTURE, 2, 3, START, END, REQUEST, new Basis(8, 4, 30)),
                scheme(1, EFFECTIVE_FIXTURE, 2, 3, START, END, REQUEST, new Basis(8, 3, 31)));
    }

    private static AcceptedScheme scheme(long patient, Version schedule, long clinic, long dentist,
                                         Instant start, Instant end, String request, Basis basis) {
        return new AcceptedScheme(patient, schedule, clinic, dentist, start, end, request, basis);
    }
}
