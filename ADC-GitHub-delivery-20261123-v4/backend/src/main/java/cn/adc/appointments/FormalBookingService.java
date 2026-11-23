package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.AuditStore;
import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.audit.CommandRecovery.Command;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.audit.UcAuditService;
import cn.adc.shared.config.ConfigurationUnavailable;
import cn.adc.shared.config.DentistEligibilityProvider;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.StrictInputPolicy;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

/** UC02 internal write path. No component registration or live database bindings. */
final class FormalBookingService implements CommandRecovery.SuccessVerifier {
    private static final String OPERATION = "APPOINTMENT_CREATE";

    @FunctionalInterface
    interface AppointmentIds {
        long next(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    interface BookingPreparation {
        /**
         * Read-only preparation under the booking locks. Verify any supplied coordination or source
         * reference against the accepted scheme, enforce any required coordination, and return the
         * original reminder arrangement to store in table 29. No external notifications.
         */
        String reminder(Connection connection, InitialBookingReview.AcceptedScheme scheme,
                        String coordination, String sourceReference) throws SQLException;
    }

    @FunctionalInterface
    interface ReleaseEvidence {
        /** Verify the original committed UC04/06 fact for this candidate, including any required reminder state. */
        boolean confirms(Connection connection, AppointmentRepository.Overlap candidate) throws SQLException;
    }

    interface MiniSource {
        /** Lock and verify the current request revision, patient, clinic and category evidence. */
        void requireCurrent(Connection c, String reference, InitialBookingReview.AcceptedScheme scheme)
                throws SQLException;

        /** Persist the result link and extension success audit using this transaction. */
        void linkCommittedResult(Connection c, String reference, AppointmentRepository.Appointment appointment,
                                 String requestReference) throws SQLException;

        boolean matchesCommitted(Connection c, String reference, long appointmentId, String requestReference)
                throws SQLException;
    }

    private final StaffAuthorization authorization;
    private final InitialBookingReview review;
    private final DentistEligibilityProvider dentists;
    private final AppointmentIds ids;
    private final BookingPreparation preparation;
    private final ReleaseEvidence releaseEvidence;
    private final MiniSource miniSource;
    private final AppointmentRepository appointments = new AppointmentRepository();
    private final CommandRecovery commands;
    private final Clock clock;

    FormalBookingService(JdbcTransactions transactions, StaffAuthorization authorization,
                         InitialBookingReview review, DentistEligibilityProvider dentists,
                         AppointmentIds ids, AuditStore.EventIdAllocator auditIds,
                         BookingPreparation preparation, ReleaseEvidence releaseEvidence, Clock clock) {
        this(transactions, authorization, review, dentists, ids, auditIds, preparation, releaseEvidence, clock, null);
    }

    FormalBookingService(JdbcTransactions transactions, StaffAuthorization authorization,
                         InitialBookingReview review, DentistEligibilityProvider dentists,
                         AppointmentIds ids, AuditStore.EventIdAllocator auditIds,
                         BookingPreparation preparation, ReleaseEvidence releaseEvidence, Clock clock,
                         MiniSource miniSource) {
        this.authorization = Objects.requireNonNull(authorization);
        this.review = Objects.requireNonNull(review);
        this.dentists = Objects.requireNonNull(dentists);
        this.ids = Objects.requireNonNull(ids);
        this.preparation = Objects.requireNonNull(preparation);
        this.releaseEvidence = Objects.requireNonNull(releaseEvidence);
        this.miniSource = miniSource;
        this.clock = Objects.requireNonNull(clock);
        commands = new UcAuditService(transactions, authorization, this, clock,
                Objects.requireNonNull(auditIds)).commands();
    }

    /** Clinic comes from an internal staff context, never from the proposed scheme. */
    CommandRecovery.Receipt book(long clinic, UUID key, JsonNode input) {
        var actor = authorization.evaluate("UC02", Set.of(clinic)).actor();
        var command = command(clinic, key, input);
        return commands.execute(command, (c, locks) -> {
            var booking = review.reviewForCreate(c, locks, actor, key, clinic, command.payload());
            var scheme = booking.scheme();
            requireDentist(scheme);
            for (var overlap : appointments.overlaps(c, scheme.dentistId(), scheme.startsAt(), scheme.endsAt())) {
                if (!released(c, overlap, releaseEvidence, clock)) throw new Rejection("APPOINTMENT_CAPACITY_CONFLICT");
            }
            var source = optionalText(command.payload(), "sourceContextReference");
            if (source != null) {
                if (miniSource == null) throw new Rejection("VERIFIED_MINIPROGRAM_BOOKING_UNAVAILABLE");
                miniSource.requireCurrent(c, source, scheme);
            }
            var reminder = preparation.reminder(c, scheme, optionalText(command.payload(), "manualCoordinationBasis"), source);
            if (reminder == null) throw new SQLException("BOOKING_PREPARATION_UNAVAILABLE", "55000");
            if (!actor.equals(authorization.require("UC02", Set.of(clinic)).actor())) {
                throw new SecurityException("REQUEST_ACTOR_CHANGED");
            }
            long id = ids.next(c);
            if (id <= 0) throw new SQLException("APPOINTMENT_ID_UNAVAILABLE", "22003");
            var confirmation = booking.confirmation();
            var requestReference = requestReference(actor, key);
            var acceptance = JSON.createObjectNode().put("schemaVersion", 1)
                    .put("requestReference", requestReference)
                    .put("acceptanceEvidenceReference", confirmation.reference())
                    .put("applicantSubject", confirmation.applicantSubject())
                    .put("acceptedAt", confirmation.acceptedAt().toString());
            if (confirmation.reference().startsWith("SYNTHETIC-confirmation:")) acceptance.putObject("syntheticConfirmationVerification")
                    .put("basis", confirmation.provenance().reference()).put("recordedBy", confirmation.provenance().verifiedBy())
                    .put("sourceVersion", confirmation.provenance().version());
            var terms = new AppointmentRepository.Terms(scheme.schedule(), scheme.startsAt(), scheme.endsAt(),
                    freeze(booking), reminder);
            var appointment = new AppointmentRepository.Appointment(id, scheme.patientId(), terms,
                    acceptance.toString(), "待到诊", null, null, actor, StrictInputPolicy.eventTime(clock),
                    confirmation.representativeAuthorityReference(), null);
            appointments.insertAppointment(c, appointment);
            if (source != null) miniSource.linkCommittedResult(c, source, appointment, requestReference);
            var ref = JSON.createObjectNode().put("appointmentId", Long.toString(id));
            var verification = JSON.createObjectNode().put("creationFingerprint", fingerprint(appointment))
                    .put("requestReference", requestReference)
                    .put("acceptanceEvidenceReference", confirmation.reference());
            return new AuditDocuments.Change(ref, null, ref, verification);
        });
    }

    /** An explicit inquiry only. No fallback to booking Work, even if the journal is absent. */
    CommandRecovery.Receipt recover(long clinic, UUID key, JsonNode input) {
        return commands.recover(command(clinic, key, input), false);
    }

    private static String requestReference(String actor, UUID key) {
        return AuditDocuments.requestReference(actor, "UC", OPERATION, key).replace("/v1/", "/v1/UC02/");
    }

    private static Command command(long clinic, UUID key, JsonNode input) {
        if (input == null || !input.isObject()) throw new IllegalArgumentException("BOOKING_INPUT_REQUIRED");
        return new Command(OPERATION, "UC02", key, input, Set.of(clinic));
    }

    static boolean released(Connection c, AppointmentRepository.Overlap overlap,
                            ReleaseEvidence releaseEvidence, Clock clock) throws SQLException {
        if (overlap.hasVisit() || overlap.basis() == null || overlap.basis().isBlank()) return false;
        boolean eligible = "已取消".equals(overlap.status())
                || ("已确认未到诊".equals(overlap.status()) && !overlap.endsAt().isAfter(clock.instant()));
        return eligible && releaseEvidence.confirms(c, overlap);
    }

    private void requireDentist(InitialBookingReview.AcceptedScheme scheme) {
        requireDentist(dentists, scheme);
    }

    static void requireDentist(DentistEligibilityProvider dentists, InitialBookingReview.AcceptedScheme scheme) {
        var eligibility = dentists.require(scheme.dentistId(), scheme.clinicId());
        if (eligibility == null || eligibility.provenance() == null
                || eligibility.dentistId() != scheme.dentistId() || eligibility.clinicId() != scheme.clinicId()
                || eligibility.evidenceReference() == null || eligibility.evidenceReference().isBlank()) {
            throw new ConfigurationUnavailable();
        }
        eligibility.provenance().requireUsable();
    }

    private static String optionalText(JsonNode input, String key) {
        return input.has(key) ? AuditDocuments.text(input, key) : null;
    }

    private static String freeze(InitialBookingReview.VerifiedBooking booking) {
        return freeze(booking.scheme(), booking.projectName(), booking.time(), "INITIAL_CURRENT");
    }

    static String freeze(InitialBookingReview.AcceptedScheme s, String projectName,
                         cn.adc.shared.time.ClinicTimePolicy.Resolved t, String mode) {
        var document = JSON.createObjectNode().put("treatmentRequest", s.treatmentRequest());
        var basis = document.putObject("bookingDurationBasis").put("schemaVersion", 1)
                .put("primaryProjectId", Long.toString(s.duration().projectId()))
                .put("projectVersion", s.duration().versionNo()).put("projectNameSnapshot", projectName)
                .put("adoptedDurationMinutes", s.duration().minutes()).put("basisMode", mode);
        basis.putObject("timeInterpretation").put("clinicId", Long.toString(s.clinicId()))
                .put("zonePolicyVersion", t.policyVersion()).put("zoneId", t.zoneId())
                .put("sourceLocalStart", t.localStart().toString()).put("sourceLocalEnd", t.localEnd().toString())
                .put("selectedOffsetStart", t.startOffset().toString()).put("selectedOffsetEnd", t.endOffset().toString())
                .put("resolvedInstantStart", t.start().atOffset(ZoneOffset.UTC).toString())
                .put("resolvedInstantEnd", t.end().atOffset(ZoneOffset.UTC).toString())
                .put("localEndSource", t.localEndSource()).put("tzdbVersion", t.tzdbVersion());
        return document.toString();
    }

    static String fingerprint(AppointmentRepository.Appointment a) {
        var t = a.terms();
        return AuditDocuments.hash(JSON.createObjectNode().put("appointmentId", a.id()).put("patientId", a.patientId())
                .put("scheduleId", t.schedule().scheduleId()).put("versionNo", t.schedule().versionNo())
                .put("start", t.startsAt().toString()).put("end", t.endsAt().toString())
                .put("document", t.treatmentRequest()).put("reminder", t.reminder())
                .put("acceptance", a.acceptance()).put("representative", a.representative()));
    }

    @Override
    public boolean matchesCommitted(Connection c, Command command, JsonNode saved) throws SQLException {
        if (!OPERATION.equals(command.operation()) || !"UC02".equals(command.ucCode())
                || command.clinics().size() != 1 || !saved.path("before").isNull()) return false;
        var after = saved.path("after");
        var idText = after.path("appointmentId").asText();
        if (!idText.matches("-?[0-9]+") || !after.equals(saved.path("businessReference"))) return false;
        long id;
        try {
            id = Long.parseLong(idText);
        } catch (NumberFormatException e) {
            return false;
        }
        var row = appointments.findAppointment(c, id, command.clinics().iterator().next());
        if (row.isEmpty()) return false;
        var original = row.get();
        var verification = saved.path("verification");
        var reference = requestReference(original.actor(), command.clientKey());
        if (!reference.equals(verification.path("requestReference").asText())
                || !fingerprint(original).equals(verification.path("creationFingerprint").asText())) return false;
        var source = optionalText(command.payload(), "sourceContextReference");
        return source == null || (miniSource != null && miniSource.matchesCommitted(c, source, id, reference));
    }

    @Override
    public boolean hasContradictorySuccess(Connection c, Command command, String requestReference) {
        // Original UC recovery never closes an unverified request or runs work again.
        return true;
    }
}

