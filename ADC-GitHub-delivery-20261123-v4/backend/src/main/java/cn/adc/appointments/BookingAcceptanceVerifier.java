package cn.adc.appointments;

import cn.adc.appointments.InitialBookingReview.AcceptedScheme;
import cn.adc.shared.config.SourceStamp;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;

/** Offline adapter boundary. No source is registered for the live application. */
public final class BookingAcceptanceVerifier implements InitialBookingReview.AcceptanceEvidence {
    public record Confirmation(String reference, SourceStamp provenance, String applicantSubject,
                               String representativeAuthorityReference, Instant acceptedAt, Instant validUntil,
                               AcceptedScheme scheme) {}

    @FunctionalInterface
    public interface Source {
        /**
         * Resolve actual booking confirmations only. A revoked, superseded or unverified record
         * must be absent or unusable. A source outage must not be reported as a confirmation.
         * A representative record must carry the verified authority basis from when acceptance
         * was recorded. The current grant is checked separately by ApplicantAuthority.
         */
        Optional<Confirmation> find(Connection connection, String reference) throws SQLException;
    }

    public interface ApplicantAuthority {
        boolean isPatient(Connection connection, String subject, long patientId) throws SQLException;

        /** Resolve this verified grant for this subject and the exact UC02 scheme at review time. */
        boolean mayAcceptBooking(Connection connection, String subject, AcceptedScheme scheme,
                                 String authorityReference) throws SQLException;
    }

    private final Source source;
    private final ApplicantAuthority authority;
    private final InstantSource clock;

    public BookingAcceptanceVerifier(Source source, ApplicantAuthority authority, InstantSource clock) {
        this.source = Objects.requireNonNull(source);
        this.authority = Objects.requireNonNull(authority);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public boolean matches(Connection connection, String reference, AcceptedScheme scheme) throws SQLException {
        return verified(connection, reference, scheme).isPresent();
    }

    Optional<Confirmation> verified(Connection connection, String reference, AcceptedScheme scheme) throws SQLException {
        if (reference == null || reference.isBlank()) return Optional.empty();
        var record = source.find(connection, reference).orElse(null);
        if (record == null || !reference.equals(record.reference()) || !scheme.equals(record.scheme())
                || record.applicantSubject() == null || record.applicantSubject().isBlank()
                || record.provenance() == null || !record.provenance().usable()) return Optional.empty();

        record.provenance().requireUsable();
        var now = clock.instant();
        if (record.acceptedAt() == null || record.acceptedAt().isAfter(now)
                || !beforeExpiry(record, now)) return Optional.empty();

        var grant = record.representativeAuthorityReference();
        var authorized = grant == null ? authority.isPatient(connection, record.applicantSubject(), scheme.patientId())
                : !grant.isBlank()
                && authority.mayAcceptBooking(connection, record.applicantSubject(), scheme, grant);
        return authorized && (record.validUntil() == null || beforeExpiry(record, clock.instant()))
                ? Optional.of(record) : Optional.empty();
    }

    private static boolean beforeExpiry(Confirmation record, Instant now) {
        return record.validUntil() == null || now.isBefore(record.validUntil());
    }
}
