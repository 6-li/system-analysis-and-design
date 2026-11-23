package cn.adc.appointments;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class InvoiceSettlementServiceTest {
    @Test void initialConfirmationOpensPaymentAndNextTransactionVerifiesSamePayment() throws Exception {
        var auth = new StaffAuthorization(() -> Optional.of("SYNTHETIC cashier"), () ->
                Map.of("SYNTHETIC cashier", new StaffAuthorization.Grant("fixture", Set.of("BILLING_STAFF"),
                        Set.of("UC12"), Set.of(11L), false)));
        var service = new InvoiceSettlementService(new JdbcTransactions(mock(DataSource.class)), auth,
                c -> 901L, c -> 501L, Clock.systemUTC());
        var target = new InvoiceSettlementService.Target(11, 41, 801, 1);
        var start = invoice("待核对确认");
        query(start, "FROM adc.payment_transaction", false);
        var confirmation = write(start, "SET confirmation_information=?,confirmed_at=?");
        write(start, "SET invoice_status='待结算'");
        var insert = write(start, "INSERT INTO adc.payment_transaction");
        var pending = service.transact(start, target, InvoiceSettlementService.START, null,
                new InvoiceSettlementService.Confirmation("SYNTHETIC applicant", "33.33", "SYNTHETIC accepted current amount"));
        assertEquals("待核验", pending.after().path("status").asText());
        assertEquals("901", pending.after().path("paymentId").asText());
        var writes = inOrder(confirmation, insert);
        writes.verify(confirmation).setString(eq(1), contains("acceptedAmount"));
        writes.verify(confirmation).executeUpdate();
        writes.verify(insert).setLong(1, 901);
        writes.verify(insert).setBigDecimal(5, new BigDecimal("33.33"));
        writes.verify(insert).executeUpdate();

        var verify = invoice("待结算");
        var payment = query(verify, "FROM adc.payment_transaction", true, false);
        when(payment.getLong(1)).thenReturn(901L);
        when(payment.getInt(2)).thenReturn(1);
        when(payment.getString(3)).thenReturn("模拟支付");
        when(payment.getBigDecimal(4)).thenReturn(new BigDecimal("33.33"));
        when(payment.getString(5)).thenReturn("待核验");
        var persistedConfirmation = query(verify, "SELECT confirmation_information", true);
        when(persistedConfirmation.getString(1)).thenReturn("""
                {"invoiceId":801,"invoiceVersionNo":1,"acceptedAmount":"33.33"}
                """);
        var updated = write(verify, "UPDATE adc.payment_transaction");
        var invoice = write(verify, "SET invoice_status='已结算'");
        var paid = service.transact(verify, target, InvoiceSettlementService.VERIFY, 901L, null);
        assertEquals("已结算", paid.after().path("status").asText());
        assertEquals("SYNTHETIC-UC12-901", paid.after().path("receipt").asText());
        var finalWrites = inOrder(updated, invoice);
        finalWrites.verify(updated).setBigDecimal(1, new BigDecimal("33.33"));
        finalWrites.verify(updated).setLong(6, 901L);
        finalWrites.verify(updated).executeUpdate();
        finalWrites.verify(invoice).executeUpdate();
        verify(verify, never()).prepareStatement(contains("INSERT INTO adc.payment_transaction"));
    }

    private static Connection invoice(String status) throws Exception {
        var c = mock(Connection.class);
        query(c, "SELECT 1 FROM adc.visit", true);
        when(c.prepareStatement(contains("pg_advisory_xact_lock"))).thenReturn(mock(PreparedStatement.class));
        var bill = query(c, "LEFT JOIN adc.treatment_invoice_version", true);
        when(bill.getLong(1)).thenReturn(801L);
        when(bill.getInt(2)).thenReturn(1);
        when(bill.getString(3)).thenReturn(status);
        when(bill.getBigDecimal(4)).thenReturn(new BigDecimal("33.33"));
        var line = query(c, "FROM adc.treatment_invoice_item", true, false);
        when(line.getLong(1)).thenReturn(701L);
        when(line.getInt(2)).thenReturn(1);
        when(line.getString(3)).thenReturn("SYNTHETIC treatment");
        when(line.getString(4)).thenReturn("次");
        when(line.getBigDecimal(5)).thenReturn(new BigDecimal("1.250"));
        when(line.getBigDecimal(6)).thenReturn(new BigDecimal("26.66"));
        when(line.getBigDecimal(7)).thenReturn(new BigDecimal("33.33"));
        when(line.getString(8)).thenReturn("SYNTHETIC price");
        return c;
    }

    private static ResultSet query(Connection c, String match, boolean first, Boolean... remaining) throws Exception {
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(c.prepareStatement(contains(match))).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(first, remaining);
        return rows;
    }

    private static PreparedStatement write(Connection c, String match) throws Exception {
        var statement = mock(PreparedStatement.class);
        when(c.prepareStatement(contains(match))).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        return statement;
    }
}
