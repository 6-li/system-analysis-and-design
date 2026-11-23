package cn.adc.inventory;

import cn.adc.shared.audit.CommandRecovery;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StockInquiryServiceTest {
    @Test void managerReadsExistingWarningsWithClinicScopeAndPreservesMissingThresholds() throws Exception {
        var c = mock(Connection.class);
        var sql = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(c.prepareStatement(contains("s.clinic_id=?"))).thenReturn(sql);
        when(sql.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getLong(1)).thenReturn(101L, 102L);
        when(rows.getLong(4)).thenReturn(701L, 0L);
        when(rows.wasNull()).thenReturn(false, true);
        when(rows.getString(2)).thenReturn("SYNTHETIC gloves", "SYNTHETIC gauze");
        when(rows.getString(3)).thenReturn("盒");
        when(rows.getBigDecimal(5)).thenReturn(new BigDecimal("4.000"), null);
        when(rows.getString(7)).thenReturn("SYNTHETIC 临近有效期预警", (String) null);
        var actor = "SYNTHETIC manager";
        var auth = new StaffAuthorization(() -> Optional.of(actor), () -> Map.of(actor,
                new StaffAuthorization.Grant("fixture", Set.of("CLINIC_MANAGER"), Set.of("UC13"), Set.of(11L), false)));
        var service = new StockInquiryService(new JdbcTransactions(mock(DataSource.class)), auth, conn -> 501L,
                Clock.fixed(Instant.parse("2026-11-17T04:05:06Z"), ZoneOffset.UTC));
        var change = service.snapshot(c, 11, null);
        var first = change.after().path("items").get(0);
        var second = change.after().path("items").get(1);
        assertEquals("4.000", first.path("onHand").asText());
        assertTrue(first.path("threshold").isNull());
        assertEquals("SYNTHETIC 临近有效期预警", first.path("existingWarning").asText());
        assertFalse(second.path("stockPresent").asBoolean());
        assertTrue(second.path("onHand").isNull());
        assertEquals("2026-11-17T04:05:06Z", change.after().path("queriedAt").asText());
        verify(sql).setLong(1, 11);
        verify(sql, never()).executeUpdate();

        var mockedService = mock(StockInquiryService.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        var key = UUID.randomUUID();
        when(mockedService.query(11, null, key)).thenReturn(new CommandRecovery.Receipt(
                "SYNTHETIC reference", "SUCCEEDED", CommandRecovery.Persistence.CONFIRMED, change.after(), false, "501", null));
        var mvc = MockMvcBuilders.standaloneSetup(new StockInquiryController((req, csrf) -> mockedService)).build();
        mvc.perform(post("/api/v1/staff/clinics/11/stock-inquiry").header("X-CSRF-Token", "SYNTHETIC")
                        .header("Idempotency-Key", key).contentType("application/json").content("{\"supplyId\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock.items[0].supplyId").value("101"))
                .andExpect(jsonPath("$.stock.items[1].stockPresent").value(false));
    }
}
