package cn.adc.appointments;

import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class VisitHealthIsolatedPgIT {
    @Test void treatingDentistReadsCurrentHistoryAndCommitsOneHealthSnapshot() throws Exception {
        var db = new T028IsolatedPgIT.IsolatedData("runtime", 0);
        var clock = Clock.fixed(Instant.parse("2026-11-08T11:00:00Z"), ZoneOffset.UTC);
        var stamp = new SourceStamp("SYNTHETIC", "uc07-fixture", "isolated", clock.instant(), true);
        var actor = "SYNTHETIC dentist";
        var bindings = new StaffBindingAdapter(() -> List.of(new StaffBindingAdapter.Entry(actor,
                new StaffAuthorization.Grant(stamp.version(), Set.of("DENTIST"), Set.of("UC07"), Set.of(81001L), false),
                stamp)));
        var auditIds = new AtomicLong(70100);
        var access = new VisitHealthAccess(new JdbcTransactions(db), bindings, request -> 81001L,
                (subject, clinic) -> actor.equals(subject) && clinic == 81001 ? 79001L : 0L,
                c -> 401L, c -> auditIds.incrementAndGet(), clock);
        var mvc = standaloneSetup(new VisitHealthController(access)).build();
        var session = new MockHttpSession();
        session.setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession(actor, "SYNTHETIC-CSRF", stamp));
        String route = "/api/v1/staff/visits/42/health";
        mvc.perform(get(route).session(session).header("X-CSRF-Token", "SYNTHETIC-CSRF").param("patientId", "88001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snapshot.id").value("400"))
                .andExpect(jsonPath("$.priorTreatments.length()").value(1))
                .andExpect(jsonPath("$.priorTreatments[0].version").value(2))
                .andExpect(jsonPath("$.priorTreatments[0].treatment").value("SYNTHETIC current treatment"));
        mvc.perform(post(route).session(session).header("X-CSRF-Token", "SYNTHETIC-CSRF")
                        .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content("""
                        {"patientId":"88001","expectedSnapshotId":"400","medicalHistory":"SYNTHETIC prior history",
                         "allergyInformation":"SYNTHETIC verified allergy","changeContent":"SYNTHETIC verification"}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.healthChangeId").value("401"));
        mvc.perform(get(route).session(session).header("X-CSRF-Token", "SYNTHETIC-CSRF").param("patientId", "88001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snapshot.id").value("401"))
                .andExpect(jsonPath("$.snapshot.allergyInformation").value("SYNTHETIC verified allergy"));
        try (var c = db.getConnection(); var statement = c.createStatement();
             var rows = statement.executeQuery("""
                     SELECT (SELECT count(*) FROM adc.patient_health_change WHERE visit_id=41),
                            (SELECT count(*) FROM adc.patient_health_change WHERE visit_id=42 AND verified_dentist_id=79001),
                            (SELECT count(*) FROM adc.operation_audit_event
                             WHERE uc_code='UC07' AND operation_name='HEALTH_CHANGE_SAVE' AND outcome='SUCCESS'
                               AND evidence::jsonb #>> '{businessResultSnapshot,after,healthChangeId}'='401')
                     """)) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertEquals(1, rows.getInt(2));
            assertEquals(1, rows.getInt(3));
        }
    }
}
