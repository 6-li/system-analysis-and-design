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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class InvoiceQuestionServiceTest {
    @Test void billingStaffRecalculatesPriceQuestionWithoutChangingInvoiceIdentity() throws Exception {
        var c = mock(Connection.class);
        var scope = query(c, "a.patient_id=?", row(true));
        var lock = mock(PreparedStatement.class);
        when(c.prepareStatement(contains("pg_advisory_xact_lock"))).thenReturn(lock);
        var header = row(true);
        when(header.getLong(1)).thenReturn(801L);
        when(header.getInt(2)).thenReturn(1);
        when(header.getString(3)).thenReturn("待核实");
        when(header.getBigDecimal(4)).thenReturn(new BigDecimal("33.33"));
        query(c, "LEFT JOIN adc.treatment_invoice_version", header);
        var line = row(true, false);
        when(line.getLong(1)).thenReturn(701L);
        when(line.getInt(2)).thenReturn(1);
        when(line.getString(3)).thenReturn("SYNTHETIC treatment");
        when(line.getString(4)).thenReturn("次");
        when(line.getBigDecimal(5)).thenReturn(new BigDecimal("1.250"));
        when(line.getBigDecimal(6)).thenReturn(new BigDecimal("26.66"));
        when(line.getBigDecimal(7)).thenReturn(new BigDecimal("33.33"));
        when(line.getString(8)).thenReturn("SYNTHETIC-PRICE-v1");
        query(c, "FROM adc.treatment_invoice_item", line);
        query(c, "FROM adc.payment_transaction", row(false));
        var history = row(true);
        when(history.getString(1)).thenReturn("SYNTHETIC price question");
        when(history.getString(2)).thenReturn("""
                {"questionReference":"SYNTHETIC-Q1","scope":"PRICE","phase":"PENDING"}
                """);
        query(c, "SELECT billing_question,handling_basis", history);
        var facts = row(true, false);
        when(facts.getLong(1)).thenReturn(701L);
        when(facts.getInt(2)).thenReturn(1);
        when(facts.getString(3)).thenReturn("SYNTHETIC treatment");
        when(facts.getString(4)).thenReturn("次");
        when(facts.getBigDecimal(5)).thenReturn(new BigDecimal("1.250"));
        query(c, "v.is_current AND v.implementation_result='已实施'", facts);
        var retire = write(c, "SET is_current=false");
        var insert = write(c, "INSERT INTO adc.treatment_invoice_version");
        var detail = write(c, "INSERT INTO adc.treatment_invoice_item");
        var status = write(c, "SET invoice_status=?");
        var auth = new StaffAuthorization(() -> Optional.of("SYNTHETIC billing"),
                () -> Map.of("SYNTHETIC billing", new StaffAuthorization.Grant("fixture", Set.of("BILLING_STAFF"),
                        Set.of("UC11"), Set.of(11L), false)));
        var service = new InvoiceQuestionService(new JdbcTransactions(mock(DataSource.class)), auth,
                (actor, clinic) -> { throw new AssertionError("Price-only path must not use dentist identity"); },
                (connection, target, fact) -> Optional.of(new TreatmentInvoiceService.Price(new BigDecimal("24.00"),
                        "SYNTHETIC-PRICE-v2", "SYNTHETIC rate basis")), connection -> 812L,
                connection -> 501L, Clock.systemUTC());
        var change = service.write(c, new InvoiceQuestionService.Target(11, 31, 41, 801, 1),
                InvoiceQuestionService.Operation.INVOICE_QUESTION_RECALCULATE, "SYNTHETIC price verified", false,
                "SYNTHETIC-Q1", "SYNTHETIC-RECALC", List.of(), null);
        assertEquals("801", change.businessReference().path("invoiceId").asText());
        assertEquals(2, change.businessReference().path("invoiceVersionNo").asInt());
        assertEquals("30.00", change.after().path("invoice").path("total").asText());
        assertEquals("待再次确认", change.after().path("status").asText());
        assertEquals("PENDING", change.before().path("handling").path("phase").asText());
        var ordered = inOrder(scope, lock, retire, insert, detail, status);
        ordered.verify(scope).executeQuery();
        ordered.verify(lock).execute();
        ordered.verify(scope).executeQuery();
        ordered.verify(retire).executeUpdate();
        ordered.verify(insert).setInt(2, 2);
        ordered.verify(insert).executeUpdate();
        ordered.verify(detail).setInt(3, 2);
        ordered.verify(detail).executeUpdate();
        ordered.verify(status).setString(1, "待再次确认");
        ordered.verify(status).executeUpdate();
    }

    @Test void dentistAppendsTwoCorrectionsAndLeavesInvoiceWaitingForSeparateRecalculation() throws Exception {
        var c = reviewConnection("待核实", 1, "CLINICAL", "PENDING");
        var header = row(true);
        when(header.getLong(1)).thenReturn(801L);
        when(header.getInt(2)).thenReturn(1);
        when(header.getString(3)).thenReturn("待核实");
        when(header.getBigDecimal(4)).thenReturn(new BigDecimal("66.66"));
        query(c, "LEFT JOIN adc.treatment_invoice_version", header);
        var lines = row(true, true, false);
        when(lines.getLong(1)).thenReturn(701L, 702L);
        when(lines.getInt(2)).thenReturn(1, 3);
        when(lines.getString(3)).thenReturn("SYNTHETIC treatment", "SYNTHETIC second");
        when(lines.getString(4)).thenReturn("次");
        when(lines.getBigDecimal(5)).thenReturn(new BigDecimal("1.250"));
        when(lines.getBigDecimal(6)).thenReturn(new BigDecimal("26.66"));
        when(lines.getBigDecimal(7)).thenReturn(new BigDecimal("33.33"));
        when(lines.getString(8)).thenReturn("SYNTHETIC-PRICE-v1");
        query(c, "FROM adc.treatment_invoice_item", lines);
        query(c, "v.actual_dentist_id=?", row(true));
        query(c, "v.record_version_no=? AND v.is_current", row(true));
        query(c, "proposed_treatment=? AND consent_result='同意'", row(true));
        var retired = write(c, "UPDATE adc.clinical_record_version SET is_current=false");
        var corrected = write(c, "INSERT INTO adc.clinical_record_version");
        var review = write(c, "SET billing_question=?,handling_basis=?");
        var service = reviewService("DENTIST", "UC11_CLINICAL");
        var change = service.write(c, new InvoiceQuestionService.Target(11, 31, 41, 801, 1),
                InvoiceQuestionService.Operation.INVOICE_CLINICAL_CORRECT, "SYNTHETIC corrected quantity", false,
                "SYNTHETIC-Q1", "SYNTHETIC-CORRECT",
                List.of(new InvoiceQuestionService.Correction(702, 401, "SYNTHETIC second", "次", new BigDecimal("2.000")),
                        new InvoiceQuestionService.Correction(701, 400, "SYNTHETIC treatment", "次", new BigDecimal("1.500"))), null);
        assertEquals("待核实", change.after().path("status").asText());
        assertEquals(2, change.after().path("handling").path("clinicalConfirmation").path("clinicalVersions").get(0)
                .path("recordVersionNo").asInt());
        var confirmation = change.after().path("handling").path("clinicalConfirmation");
        assertEquals(4, confirmation.path("clinicalVersions").get(1).path("recordVersionNo").asInt());
        assertEquals(3, confirmation.path("revisions").get(1).path("previousRecordVersionNo").asInt());
        var ordered = inOrder(retired, corrected, review);
        ordered.verify(retired).executeUpdate();
        ordered.verify(corrected).setBigDecimal(6, new BigDecimal("1.500"));
        ordered.verify(corrected).executeUpdate();
        ordered.verify(retired).executeUpdate();
        ordered.verify(corrected).setBigDecimal(6, new BigDecimal("2.000"));
        ordered.verify(corrected).executeUpdate();
        ordered.verify(review).executeUpdate();
    }

    @Test void dentistConfirmsWithoutCreatingClinicalVersion() throws Exception {
        var c = reviewConnection("待核实", 1, "CLINICAL", "PENDING");
        query(c, "v.actual_dentist_id=?", row(true));
        query(c, "v.record_version_no=? AND v.is_current", row(true));
        var review = write(c, "SET billing_question=?,handling_basis=?");
        var change = reviewService("DENTIST", "UC11_CLINICAL").write(c,
                new InvoiceQuestionService.Target(11, 31, 41, 801, 1),
                InvoiceQuestionService.Operation.INVOICE_CLINICAL_CONFIRM, "SYNTHETIC examined source", false,
                "SYNTHETIC-Q1", "SYNTHETIC-CONFIRM", List.of(), null);
        assertEquals("无需修正", change.after().path("handling").path("clinicalConfirmation").path("conclusion").asText());
        verify(review).executeUpdate();
        verify(c, never()).prepareStatement(contains("INSERT INTO adc.clinical_record_version"));
    }

    @Test void billingStaffRecordsApplicantsAcceptanceOfRecalculatedVersionAndAmount() throws Exception {
        var c = reviewConnection("待再次确认", 2, "PRICE", "RECALCULATED");
        query(c, "a.patient_id=?", row(true));
        var confirm = write(c, "SET confirmation_information=?, confirmed_at=?");
        var status = write(c, "SET invoice_status=?");
        var change = reviewService("BILLING_STAFF", "UC11").write(c,
                new InvoiceQuestionService.Target(11, 31, 41, 801, 2),
                InvoiceQuestionService.Operation.INVOICE_APPLICANT_CONFIRM, "SYNTHETIC actual confirmation", false,
                "SYNTHETIC-Q1", "SYNTHETIC-APPLICANT", List.of(), new InvoiceQuestionService.Applicant("SYNTHETIC applicant", "33.33"));
        assertEquals("待结算", change.after().path("status").asText());
        assertEquals("33.33", change.after().path("confirmation").path("acceptedAmount").asText());
        var ordered = inOrder(confirm, status);
        ordered.verify(confirm).setInt(4, 2);
        ordered.verify(confirm).executeUpdate();
        ordered.verify(status).setString(1, "待结算");
        ordered.verify(status).executeUpdate();
    }

    private static InvoiceQuestionService reviewService(String role, String action) {
        var auth = new StaffAuthorization(() -> Optional.of("SYNTHETIC actor"),
                () -> Map.of("SYNTHETIC actor", new StaffAuthorization.Grant("fixture", Set.of(role),
                        Set.of(action), Set.of(11L), false)));
        return new InvoiceQuestionService(new JdbcTransactions(mock(DataSource.class)), auth, (actor, clinic) -> 21L,
                (connection, target, fact) -> Optional.empty(), connection -> 812L, connection -> 501L, Clock.systemUTC());
    }

    private static Connection reviewConnection(String status, int version, String scope, String phase) throws Exception {
        var c = mock(Connection.class);
        when(c.prepareStatement(contains("pg_advisory_xact_lock"))).thenReturn(mock(PreparedStatement.class));
        var header = row(true);
        when(header.getLong(1)).thenReturn(801L);
        when(header.getInt(2)).thenReturn(version);
        when(header.getString(3)).thenReturn(status);
        when(header.getBigDecimal(4)).thenReturn(new BigDecimal("33.33"));
        query(c, "LEFT JOIN adc.treatment_invoice_version", header);
        var line = row(true, false);
        when(line.getLong(1)).thenReturn(701L);
        when(line.getInt(2)).thenReturn(1);
        when(line.getString(3)).thenReturn("SYNTHETIC treatment");
        when(line.getString(4)).thenReturn("次");
        when(line.getBigDecimal(5)).thenReturn(new BigDecimal("1.250"));
        when(line.getBigDecimal(6)).thenReturn(new BigDecimal("26.66"));
        when(line.getBigDecimal(7)).thenReturn(new BigDecimal("33.33"));
        when(line.getString(8)).thenReturn("SYNTHETIC-PRICE-v1");
        query(c, "FROM adc.treatment_invoice_item", line);
        query(c, "FROM adc.payment_transaction", row(false));
        var history = row(true);
        when(history.getString(1)).thenReturn("SYNTHETIC question");
        when(history.getString(2)).thenReturn(cn.adc.shared.audit.AuditDocuments.JSON.createObjectNode()
                .put("questionReference", "SYNTHETIC-Q1").put("scope", scope).put("phase", phase).toString());
        query(c, "SELECT billing_question,handling_basis", history);
        return c;
    }

    private static ResultSet row(boolean... sequence) throws Exception {
        var result = mock(ResultSet.class);
        Boolean[] rest = new Boolean[sequence.length - 1];
        for (int i = 1; i < sequence.length; i++) rest[i - 1] = sequence[i];
        when(result.next()).thenReturn(sequence[0], rest);
        return result;
    }

    private static PreparedStatement query(Connection c, String fragment, ResultSet rows) throws Exception {
        var statement = mock(PreparedStatement.class);
        when(c.prepareStatement(contains(fragment))).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        return statement;
    }

    private static PreparedStatement write(Connection c, String fragment) throws Exception {
        var statement = mock(PreparedStatement.class);
        when(c.prepareStatement(contains(fragment))).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        return statement;
    }
}
