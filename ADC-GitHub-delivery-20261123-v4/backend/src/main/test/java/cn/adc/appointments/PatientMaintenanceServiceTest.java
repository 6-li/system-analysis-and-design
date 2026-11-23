package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PatientMaintenanceServiceTest {
    @Test void rejectsInvalidSourceRepresentativeAndPatientReferenceBeforeOpeningTransaction() {
        var source = mock(DataSource.class);
        var verifier = mock(PatientMaintenanceService.VerifiedContext.class);
        var auth = new StaffAuthorization(() -> Optional.of("TEST reception"), () -> Map.of("TEST reception",
                new StaffAuthorization.Grant("test", Set.of("BOOKING_RECEPTION"), Set.of("UC01"), Set.of(1L), false)));
        var service = new PatientMaintenanceService(new JdbcTransactions(source), auth, verifier,
                c -> 1L, c -> 2L, Clock.systemUTC());
        var input = AuditDocuments.JSON.createObjectNode().put("patientName", "SYNTHETIC")
                .put("identityInformation", "SYNTHETIC id").put("contactInformation", "SYNTHETIC contact");
        assertThrows(IllegalArgumentException.class,
                () -> service.save(1, null, UUID.randomUUID(), input.deepCopy().putObject("source")));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(1, null, UUID.randomUUID(), input.deepCopy()
                        .put("representativeIdentityInformation", "SYNTHETIC proxy")));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(1, 9L, UUID.randomUUID(), input.deepCopy().put("existingPatientId", "8")));
        verifyNoInteractions(source, verifier);
    }
}
