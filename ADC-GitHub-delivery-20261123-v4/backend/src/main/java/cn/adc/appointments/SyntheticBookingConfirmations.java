package cn.adc.appointments;

import cn.adc.shared.config.SourceStamp;
import java.time.Clock;
import java.util.*;

/** Explicit SYNTHETIC classroom-only source. Not a real patient's consent or a durable pre-commit ledger.
 * Own one instance per verified staff session. Store success evidence via the existing booking transaction.
 * No controller or production bean is registered by this class. */
final class SyntheticBookingConfirmations {
    private record Entry(String staff, BookingAcceptanceVerifier.Confirmation confirmation) {}
    private final Map<String,Entry> entries=new HashMap<>();
    private final Clock clock;
    SyntheticBookingConfirmations(Clock clock){this.clock=Objects.requireNonNull(clock);}
    synchronized String record(String staff,String syntheticApplicant,InitialBookingReview.AcceptedScheme scheme,
                               SourceStamp stamp,boolean confirmed,String representativeAuthority) {
        stamp.requireUsable();
        if(!confirmed||staff==null||!staff.startsWith("SYNTHETIC ")||syntheticApplicant==null
                ||!syntheticApplicant.startsWith("SYNTHETIC ")||scheme==null)
            throw new SecurityException("EXPLICIT_SYNTHETIC_CONFIRMATION_REQUIRED");
        // Do not silently claim proxy authority. Existing ApplicantAuthority still rechecks it on submission.
        if(representativeAuthority!=null&&representativeAuthority.isBlank())throw new IllegalArgumentException("PROXY_AUTHORITY_REQUIRED");
        var ref="SYNTHETIC-confirmation:"+UUID.randomUUID();
        entries.put(ref,new Entry(staff,new BookingAcceptanceVerifier.Confirmation(ref,stamp,syntheticApplicant,
                representativeAuthority,clock.instant(),null,scheme)));
        return ref;
    }
    synchronized void revoke(String staff,String reference){var row=entries.get(reference);if(row!=null&&!row.staff().equals(staff))throw new SecurityException("CONFIRMATION_SCOPE_DENIED");entries.remove(reference);}
    synchronized Optional<BookingAcceptanceVerifier.Confirmation> findFor(String staff,String reference){var row=entries.get(reference);return row==null||!row.staff().equals(staff)?Optional.empty():Optional.of(row.confirmation());}
    BookingAcceptanceVerifier.Source forStaff(String staff){return (connection,reference)->findFor(staff,reference);}
}

