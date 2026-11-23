package cn.adc.inventory;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class SupplyReceiptServiceTest {
    @Test void pendingDeliveryBecomesOneStockReceiptAndRepeatingItDoesNotIncreaseStock() throws Exception {
        var c = mock(Connection.class);
        when(c.prepareStatement(contains("pg_advisory_xact_lock"))).thenReturn(mock(PreparedStatement.class));
        var purchase = query(c, "SELECT supply_id FROM adc.purchase_requirement", mock(ResultSet.class));
        when(purchase.next()).thenReturn(true);
        when(purchase.getLong(1)).thenReturn(101L);
        var empty = mock(ResultSet.class);
        var pending = mock(ResultSet.class);
        var accepted = mock(ResultSet.class);
        var load = mock(PreparedStatement.class);
        when(c.prepareStatement(contains("FROM adc.supply_receipt WHERE"))).thenReturn(load);
        when(load.executeQuery()).thenReturn(empty, pending, accepted);
        when(pending.next()).thenReturn(true);
        when(pending.getLong(1)).thenReturn(901L);
        when(pending.getString(4)).thenReturn("SYNTHETIC difference");
        when(pending.getString(5)).thenReturn("SYNTHETIC missing quantity");
        when(pending.getString(6)).thenReturn("差异待处理");
        when(accepted.next()).thenReturn(true);
        when(accepted.getLong(1)).thenReturn(901L);
        when(accepted.getObject(2, Long.class)).thenReturn(601L);
        when(accepted.getString(3)).thenReturn("SYNTHETIC supplier slip");
        when(accepted.getString(4)).thenReturn("SYNTHETIC accepted");
        when(accepted.getString(6)).thenReturn("已入库");
        when(accepted.getBigDecimal(7)).thenReturn(new BigDecimal("6.000"));
        when(accepted.getBigDecimal(8)).thenReturn(new BigDecimal("12.40"));
        when(accepted.getObject(9, LocalDate.class)).thenReturn(LocalDate.parse("2027-04-05"));
        var stock = mock(PreparedStatement.class);
        var balance = mock(ResultSet.class);
        when(c.prepareStatement(contains("UPDATE adc.medical_supply_stock"))).thenReturn(stock);
        when(stock.executeQuery()).thenReturn(balance);
        when(balance.next()).thenReturn(true, false);
        when(balance.getLong(1)).thenReturn(601L);
        when(balance.getBigDecimal(2)).thenReturn(new BigDecimal("10.000"));
        var insert = write(c, "INSERT INTO adc.supply_receipt");
        var update = write(c, "UPDATE adc.supply_receipt");
        String actor = "SYNTHETIC procurement";
        var grant = new StaffAuthorization.Grant("fixture", Set.of("PROCUREMENT_STAFF"), Set.of("UC15"), Set.of(11L), false);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var service = new SupplyReceiptService(new JdbcTransactions(mock(DataSource.class)),
                new StaffAuthorization(() -> Optional.of(actor), () -> Map.of(actor, grant)),
                (conn, lookup) -> Optional.of(new SupplyReceiptService.Delivery(1001, 11, 901, lookup,
                        reads.getAndIncrement() < 2 ? null : "SYNTHETIC supplier slip")),
                conn -> 501L, Clock.systemUTC());
        var target = new SupplyReceiptService.Target(11, 901);
        var discrepancy = new SupplyReceiptService.Input("SYNTHETIC delivery", false, "SYNTHETIC difference",
                "SYNTHETIC missing quantity", null, null, null);
        var qualified = new SupplyReceiptService.Input("SYNTHETIC delivery", true, "SYNTHETIC accepted", null,
                new BigDecimal("6.000"), new BigDecimal("12.40"), LocalDate.parse("2027-04-05"));
        var before = service.save(c, target, discrepancy);
        assertEquals("差异待处理", before.after().path("status").asText());
        assertTrue(before.after().path("stockId").isNull());
        verify(stock, never()).executeQuery();
        var recorded = service.save(c, target, qualified);
        assertEquals(before.businessReference(), recorded.businessReference());
        assertEquals("已入库", recorded.after().path("status").asText());
        assertEquals("10", recorded.after().path("stockBalanceAfter").asText());
        assertEquals("12.40", recorded.after().path("cost").asText());
        assertTrue(before.after().path("deliveryReference").isNull());
        assertEquals("SYNTHETIC supplier slip", recorded.after().path("deliveryReference").asText());
        assertEquals("差异待处理", recorded.before().path("status").asText());
        assertEquals(recorded.businessReference(), service.save(c, target, qualified).businessReference());
        verify(insert).executeUpdate();
        verify(stock).setBigDecimal(1, new BigDecimal("6.000"));
        verify(stock).setLong(2, 11);
        verify(stock).setLong(3, 101);
        verify(stock).executeQuery();
        verify(update).setLong(11, 1001);
        verify(update).executeUpdate();
    }

    private static ResultSet query(Connection c, String fragment, ResultSet rows) throws Exception {
        var statement = mock(PreparedStatement.class);
        when(c.prepareStatement(contains(fragment))).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        return rows;
    }

    private static PreparedStatement write(Connection c, String fragment) throws Exception {
        var statement = mock(PreparedStatement.class);
        when(c.prepareStatement(contains(fragment))).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        return statement;
    }
}
