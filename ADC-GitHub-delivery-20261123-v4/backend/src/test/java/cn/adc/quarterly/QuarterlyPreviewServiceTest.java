package cn.adc.quarterly;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static cn.adc.quarterly.QuarterlyPreviewService.AppointmentResult.*;
import static cn.adc.quarterly.QuarterlyPreviewService.Metric.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class QuarterlyPreviewServiceTest {
    @Test void freezesSeparateTotalsAndCrossClinicAttributionWithoutCountingUnconfirmedAppointments() throws Exception {
        var c = mock(Connection.class);
        var header = write(c, "INSERT INTO adc.quarterly_operating_statistic");
        var clinics = write(c, "INSERT INTO adc.quarterly_statistic_clinic");
        var performances = write(c, "INSERT INTO adc.quarterly_dentist_performance_stat");
        var issueRows = write(c, "INSERT INTO adc.quarterly_data_issue");
        var at = Instant.parse("2026-11-01T00:00:00Z");
        var target = new QuarterlyPreviewService.Target("SYNTH-Q3", Set.of(11L, 12L));
        var manifest = new QuarterlyPreviewService.Manifest(target, "SYNTHETIC-MANIFEST-v1",
                Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2027-01-02T00:00:00Z"),
                "SYNTHETIC confirmed boundaries and source-specific attribution; expenses exclude consumption",
                EnumSet.allOf(QuarterlyPreviewService.Metric.class), List.of(
                    new QuarterlyPreviewService.Entry("invoice-1", "1", 11, at, RECEIVABLE, new BigDecimal("45.00"), null, null),
                    new QuarterlyPreviewService.Entry("payment-1", "1", 11, at, RECEIVED, new BigDecimal("33.33"), null, null),
                    new QuarterlyPreviewService.Entry("expense-1", "1", 12, at, EXPENSE, new BigDecimal("9.00"), null, null),
                    new QuarterlyPreviewService.Entry("consumption-1", "1", 12, at, CONSUMPTION, new BigDecimal("5.75"), null, null),
                    new QuarterlyPreviewService.Entry("appointment-1", "1", 11, at, APPOINTMENT, null, null, ATTENDED),
                    new QuarterlyPreviewService.Entry("appointment-2", "1", 11, at, APPOINTMENT, null, null, CONFIRMED_NO_SHOW),
                    new QuarterlyPreviewService.Entry("appointment-3", "1", 12, at, APPOINTMENT, null, null, CANCELLED),
                    new QuarterlyPreviewService.Entry("appointment-4", "1", 12, at, APPOINTMENT, null, null, UNCONFIRMED),
                    new QuarterlyPreviewService.Entry("visit-1", "1", 11, at, PERFORMANCE, new BigDecimal("8.00"), 31L, null),
                    new QuarterlyPreviewService.Entry("visit-2", "1", 12, at, PERFORMANCE, new BigDecimal("4.00"), 31L, null)
                ), List.of());
        String actor = "SYNTHETIC headquarters operations";
        var grant = new StaffAuthorization.Grant("fixture", Set.of("HQ_OPERATIONS"), Set.of("UC17"), Set.of(11L,12L), false);
        var service = new QuarterlyPreviewService(new JdbcTransactions(mock(DataSource.class)),
                new StaffAuthorization(() -> Optional.of(actor), () -> Map.of(actor, grant)), (connection, scope) -> {
                    assertSame(c, connection);
                    assertEquals(target, scope);
                    return manifest;
                }, conn -> 801L, conn -> 901L, conn -> 501L,
                Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC));
        var result = service.generate(c, target);
        assertEquals("达到汇总要求", result.after().path("readiness").asText());
        assertEquals("33.33", result.after().path("received").asText());
        assertEquals(2, result.after().path("due").asInt());
        assertEquals("0.500000", result.after().path("noShowRate").asText());
        assertEquals("12.00", result.after().path("performance").get(0).path("amount").asText());
        var order = inOrder(header, clinics, performances);
        order.verify(header).setString(eq(5), contains("payment-1"));
        order.verify(header).setBigDecimal(6, new BigDecimal("45.00"));
        order.verify(header).setBigDecimal(7, new BigDecimal("33.33"));
        order.verify(header).setBigDecimal(8, new BigDecimal("9.00"));
        order.verify(header).setBigDecimal(9, new BigDecimal("5.75"));
        order.verify(header).setObject(15, 1, java.sql.Types.INTEGER);
        order.verify(header).executeUpdate();
        order.verify(clinics).setLong(2, 11);
        order.verify(clinics).executeUpdate();
        order.verify(clinics).setLong(2, 12);
        order.verify(clinics).executeUpdate();
        order.verify(performances).setLong(2, 31);
        order.verify(performances).setBigDecimal(3, new BigDecimal("12.00"));
        order.verify(performances).executeUpdate();
        verify(issueRows, never()).executeUpdate();
    }

    private static PreparedStatement write(Connection c, String fragment) throws Exception {
        var statement = mock(PreparedStatement.class);
        when(c.prepareStatement(contains(fragment))).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        return statement;
    }
}
