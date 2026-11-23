package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppointmentResolutionEvidenceTest {
    @Test void startsFailuresAndRecoveryObservationsAreNeverBusinessSuccess() {
        var evidence = AuditDocuments.JSON.createObjectNode();
        assertFalse(AppointmentResolutionEvidence.isCommittedResult(evidence));
        for (String phase : new String[]{"REQUEST_STARTED", "RECOVERY_OBSERVATION"}) {
            evidence.put("eventPhase", phase).put("confirmedCommit", "YES");
            assertFalse(AppointmentResolutionEvidence.isCommittedResult(evidence));
        }
        evidence.put("eventPhase", "BUSINESS_RESULT").put("confirmedCommit", "NO");
        assertFalse(AppointmentResolutionEvidence.isCommittedResult(evidence));
        evidence.put("confirmedCommit", "YES");
        assertTrue(AppointmentResolutionEvidence.isCommittedResult(evidence));
    }
}
