package cn.adc.inventory;

import cn.adc.appointments.T028IsolatedPgIT.IsolatedData;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.security.StaffBindingAdapter;
import cn.adc.shared.security.StaffSessionIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@EnabledIfEnvironmentVariable(named = "ADC_CLASSROOM_PURCHASE", matches = "true")
class PurchaseRequirementIsolatedPgIT {
    @Test void registersConfirmedDemandAndRecordsSupplierUnavailabilityOnTheSameRow() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var clock = Clock.systemUTC();
        var actor = "SYNTHETIC procurement";
        var stamp = new SourceStamp("SYNTHETIC", "uc14-fixture", "isolated", clock.instant(), true);
        var bindings = new StaffBindingAdapter(() -> List.of(new StaffBindingAdapter.Entry(actor,
                new StaffAuthorization.Grant(stamp.version(), Set.of("PROCUREMENT_STAFF"), Set.of("UC14"),
                        Set.of(81001L), false), stamp)));
        var auditIds = new AtomicLong(74100);
        var confirmation = new PurchaseRequirementService.Confirmation(81001L, 101L, new BigDecimal("6.000"),
                "SYNTHETIC-CONFIRMED-UC14", "SYNTHETIC manager", "SYNTHETIC confirmed demand");
        var access = new PurchaseRequirementController.StaffAccess(new JdbcTransactions(db), bindings, c -> 801L,
                (c, ref) -> confirmation.reference().equals(ref) ? Optional.of(confirmation) : Optional.empty(),
                c -> auditIds.incrementAndGet(), clock);
        var mvc = standaloneSetup(new PurchaseRequirementController(access)).build();
        var session = new MockHttpSession();
        session.setAttribute(StaffSessionIdentityAdapter.SESSION_ATTRIBUTE,
                new StaffSessionIdentityAdapter.VerifiedSession(actor, "SYNTHETIC-CSRF", stamp));
        String route = "/api/v1/staff/clinics/81001/purchase-requirements";
        mvc.perform(post(route).session(session).header("X-CSRF-Token", "SYNTHETIC-CSRF")
                        .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                        .content("""
                                {"supplyId":"101","quantity":"6.000","confirmationReference":"SYNTHETIC-CONFIRMED-UC14"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requirement.requirementId").value("801"))
                .andExpect(jsonPath("$.requirement.status").value("待联系供应商"));
        mvc.perform(post(route + "/801/unavailable").session(session).header("X-CSRF-Token", "SYNTHETIC-CSRF")
                        .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                        .content("{\"explanation\":\"SYNTHETIC supplier unavailable\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requirement.requirementId").value("801"))
                .andExpect(jsonPath("$.requirement.status").value("无法供货待调整"));
        try (var c = db.getConnection(); var statement = c.createStatement(); var rows = statement.executeQuery("""
                SELECT clinic_id, supply_id, required_quantity, unavailable_supply_explanation,
                       requirement_confirmation::jsonb ->> 'reference',
                       (SELECT count(DISTINCT operation_name) FROM adc.operation_audit_event
                        WHERE uc_code='UC14' AND outcome='SUCCESS'
                          AND operation_name IN ('PURCHASE_REQUIREMENT_REGISTER','PURCHASE_REQUIREMENT_UNAVAILABLE')
                          AND evidence::jsonb #>> '{businessResultSnapshot,after,requirementId}'='801')
                FROM adc.purchase_requirement WHERE purchase_requirement_id=801
                """)) {
            assertTrue(rows.next());
            assertEquals(81001L, rows.getLong(1));
            assertEquals(101L, rows.getLong(2));
            assertEquals(new BigDecimal("6.000"), rows.getBigDecimal(3));
            assertEquals("SYNTHETIC supplier unavailable", rows.getString(4));
            assertEquals(confirmation.reference(), rows.getString(5));
            assertEquals(2, rows.getInt(6));
        }
    }
}
