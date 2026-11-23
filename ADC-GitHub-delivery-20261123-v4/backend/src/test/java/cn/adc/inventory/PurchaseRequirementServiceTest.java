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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class PurchaseRequirementServiceTest {
    @Test void confirmedDemandCanBeRegisteredAndSupplierOutcomeKeepsTheSameIdentity() throws Exception {
        var c = mock(Connection.class);
        var scope = mock(PreparedStatement.class);
        var insert = mock(PreparedStatement.class);
        var existing = mock(PreparedStatement.class);
        var update = mock(PreparedStatement.class);
        var present = mock(ResultSet.class);
        var prior = mock(ResultSet.class);
        var quantity = new BigDecimal("8.000");
        when(c.prepareStatement(contains("CROSS JOIN adc.medical_supply"))).thenReturn(scope);
        when(c.prepareStatement(contains("INSERT INTO adc.purchase_requirement"))).thenReturn(insert);
        when(c.prepareStatement(contains("SELECT supply_id,required_quantity"))).thenReturn(existing);
        when(c.prepareStatement(contains("UPDATE adc.purchase_requirement"))).thenReturn(update);
        when(scope.executeQuery()).thenReturn(present);
        when(present.next()).thenReturn(true);
        when(existing.executeQuery()).thenReturn(prior);
        when(prior.next()).thenReturn(true);
        when(prior.getLong(1)).thenReturn(101L);
        when(prior.getBigDecimal(2)).thenReturn(quantity);
        when(prior.getString(3)).thenReturn("SYNTHETIC persisted confirmation document");
        when(prior.getString(4)).thenReturn("待联系供应商");
        when(insert.executeUpdate()).thenReturn(1);
        when(update.executeUpdate()).thenReturn(1);
        String actor = "SYNTHETIC procurement";
        var grant = new StaffAuthorization.Grant("fixture", Set.of("PROCUREMENT_STAFF"), Set.of("UC14"), Set.of(11L), false);
        var service = new PurchaseRequirementService(new JdbcTransactions(mock(DataSource.class)),
                new StaffAuthorization(() -> Optional.of(actor), () -> Map.of(actor, grant)), conn -> 901L,
                (conn, reference) -> {
                    assertSame(c, conn);
                    assertEquals("SYNTHETIC-CONF-1", reference);
                    return Optional.of(new PurchaseRequirementService.Confirmation(11, 101, quantity, reference,
                            "SYNTHETIC manager", "SYNTHETIC approved replenishment"));
                }, conn -> 501L, Clock.systemUTC());
        var created = service.register(c, 11, new PurchaseRequirementService.Input(101, quantity, "SYNTHETIC-CONF-1"));
        assertEquals("901", created.after().path("requirementId").asText());
        assertEquals("待联系供应商", created.after().path("status").asText());
        var reported = service.unavailable(c, 11, 901, "SYNTHETIC supplier cannot deliver");
        assertEquals(created.businessReference(), reported.businessReference());
        assertEquals("待联系供应商", reported.before().path("status").asText());
        assertEquals("无法供货待调整", reported.after().path("status").asText());
        var order = inOrder(scope, insert, existing, update);
        order.verify(scope).setLong(1, 11);
        order.verify(scope).setLong(2, 101);
        order.verify(scope).executeQuery();
        order.verify(insert).setBigDecimal(4, quantity);
        order.verify(insert).setString(eq(5), contains("SYNTHETIC-CONF-1"));
        order.verify(insert).setString(7, actor);
        order.verify(insert).executeUpdate();
        order.verify(existing).executeQuery();
        order.verify(update).setLong(3, 901);
        order.verify(update).setLong(4, 11);
        order.verify(update).setString(5, "待联系供应商");
        order.verify(update).executeUpdate();
        verify(c, never()).prepareStatement(contains("medical_supply_stock"));
    }
}
