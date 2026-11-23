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
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class SupplyConsumptionServiceTest {
    @Test void managerRecordsVerifiedCostAndDeductsOnlyTheActualQuantity() throws Exception {
        var c = mock(Connection.class);
        var lock = mock(PreparedStatement.class);
        var existing = mock(PreparedStatement.class);
        var stock = mock(PreparedStatement.class);
        var insert = mock(PreparedStatement.class);
        var absent = mock(ResultSet.class);
        var balance = mock(ResultSet.class);
        when(c.prepareStatement(contains("pg_advisory_xact_lock"))).thenReturn(lock);
        when(c.prepareStatement(contains("x.consumption_id=? AND s.clinic_id=?"))).thenReturn(existing);
        when(c.prepareStatement(contains("on_hand_quantity>=?"))).thenReturn(stock);
        when(c.prepareStatement(contains("INSERT INTO adc.supply_consumption"))).thenReturn(insert);
        when(existing.executeQuery()).thenReturn(absent);
        when(stock.executeQuery()).thenReturn(balance);
        when(balance.next()).thenReturn(true, false);
        when(balance.getLong(1)).thenReturn(601L);
        when(balance.getBigDecimal(2)).thenReturn(new BigDecimal("7.500"));
        when(insert.executeUpdate()).thenReturn(1);

        var target = new SupplyConsumptionService.Target(11, 101);
        var occurredAt = Instant.parse("2026-11-17T07:30:00Z");
        var quantity = new BigDecimal("2.500");
        var verifiedCost = new BigDecimal("5.75");
        String actor = "SYNTHETIC manager";
        var grant = new StaffAuthorization.Grant("fixture", Set.of("CLINIC_MANAGER"), Set.of("UC16"), Set.of(11L), false);
        var service = new SupplyConsumptionService(new JdbcTransactions(mock(DataSource.class)),
                new StaffAuthorization(() -> Optional.of(actor), () -> Map.of(actor, grant)), (connection, reference) -> {
                    assertEquals("SYNTHETIC cost fact", reference);
                    return Optional.of(new SupplyConsumptionService.Evidence(811, target, quantity, occurredAt, verifiedCost,
                            "SYNTHETIC verified consumption price facts", "SYNTHETIC verifier", null));
                }, conn -> 501L, Clock.systemUTC());
        var result = service.save(c, target, new SupplyConsumptionService.Input("SYNTHETIC cost fact", quantity, occurredAt));
        assertEquals("811", result.after().path("consumptionId").asText());
        assertEquals("7.5", result.after().path("stockBalanceAfter").asText());
        assertEquals("5.75", result.after().path("cost").asText());
        var order = inOrder(lock, existing, stock, insert);
        order.verify(lock).execute();
        order.verify(existing).setLong(1, 811);
        order.verify(existing).executeQuery();
        order.verify(stock).setBigDecimal(1, quantity);
        order.verify(stock).setLong(2, 11);
        order.verify(stock).setLong(3, 101);
        order.verify(stock).setBigDecimal(4, quantity);
        order.verify(stock).executeQuery();
        order.verify(insert).setLong(2, 601);
        order.verify(insert).setBigDecimal(4, verifiedCost);
        order.verify(insert).setString(eq(5), contains("SYNTHETIC verified consumption price facts"));
        order.verify(insert).setString(6, "SYNTHETIC verifier");
        order.verify(insert).setString(10, null);
        order.verify(insert).executeUpdate();
    }
}
