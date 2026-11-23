package cn.adc.quarterly;

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

class QuarterlyCalculationServiceTest {
    @Test void usesOneFrozenPreviewAndSeparatesIncentiveFromFinancialApproval() throws Exception {
        var c = mock(Connection.class);
        var source = query(c, "SELECT quarter_code,aggregation_readiness", true);
        when(source.getString(1)).thenReturn("SYNTH-Q3");
        when(source.getString(2)).thenReturn("达到汇总要求");
        when(source.getString(3)).thenReturn("{\"rules\":\"SYNTHETIC verified basis\"}");
        when(source.getString(4)).thenReturn("SYNTHETIC-v1");
        when(source.getString(5)).thenReturn("{\"facts\":[]}");
        when(source.getString(6)).thenReturn("quarterly_operating_statistic/801/source_basis");
        when(source.getBigDecimal(7)).thenReturn(new BigDecimal("45.00"));
        when(source.getBigDecimal(8)).thenReturn(new BigDecimal("33.33"));
        when(source.getBigDecimal(9)).thenReturn(new BigDecimal("9.00"));
        when(source.getBigDecimal(10)).thenReturn(new BigDecimal("5.75"));
        var clinic = query(c, "SELECT clinic_id FROM adc.quarterly_statistic_clinic", true, false);
        when(clinic.getLong(1)).thenReturn(11L);
        var header = write(c, "INSERT INTO adc.quarterly_operating_calculation_version");
        var performance = write(c, "INSERT INTO adc.quarterly_dentist_performance_calc");
        var details=query(c,"SELECT dentist_id,performance_amount FROM adc.quarterly_dentist_performance_calc",true,false);
        when(details.getLong(1)).thenReturn(79001L);
        when(details.getBigDecimal(2)).thenReturn(new BigDecimal("45.00"));
        var actor = "SYNTHETIC operations";
        var auth = new StaffAuthorization(() -> Optional.of(actor), () -> Map.of(actor,
                new StaffAuthorization.Grant("fixture", Set.of("HQ_OPERATIONS"), Set.of("UC19"), Set.of(11L), false)));
        var service = new QuarterlyCalculationService(new JdbcTransactions(mock(DataSource.class)), auth,
                (conn, target, frozen) -> {
                    assertEquals(801, target.statistic());
                    assertEquals("SYNTHETIC-v1", frozen.version());
                    return Optional.of(new QuarterlyCalculationService.Policy("SYNTHETIC accrual policy",
                            QuarterlyCalculationService.Revenue.RECEIVABLE, false, true));
                }, conn -> 1001L, conn -> 501L, Clock.systemUTC());
        var result = service.calculate(c, new QuarterlyCalculationService.Target(801, null, null, Set.of(11L)), null);
        assertEquals("1001", result.after().path("calculationId").asText());
        assertEquals("30.25", result.after().path("netProfit").asText());
        assertEquals("3.03", result.after().path("incentivePool").asText());
        assertEquals("待复核", result.after().path("status").asText());
        assertEquals("79001",result.after().path("performance").get(0).path("dentistId").asText());
        assertEquals("45.00",result.after().path("performance").get(0).path("amount").asText());
        var order = inOrder(header, performance);
        order.verify(header).setLong(3, 801);
        order.verify(header).setBigDecimal(5, new BigDecimal("30.25"));
        order.verify(header).setBigDecimal(6, new BigDecimal("3.03"));
        order.verify(header).executeUpdate();
        order.verify(performance).setLong(3, 801L);
        order.verify(performance).executeUpdate();
        verify(c, never()).prepareStatement(contains("INSERT INTO adc.financial_review"));
    }

    private static ResultSet query(Connection c, String fragment, boolean first, Boolean... next) throws Exception {
        var stmt = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(c.prepareStatement(contains(fragment))).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(first, next);
        return rows;
    }

    private static PreparedStatement write(Connection c, String fragment) throws Exception {
        var stmt = mock(PreparedStatement.class);
        when(c.prepareStatement(contains(fragment))).thenReturn(stmt);
        when(stmt.executeUpdate()).thenReturn(1);
        return stmt;
    }
}
