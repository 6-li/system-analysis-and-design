package cn.adc.quarterly;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuarterlyFinancialReviewServiceTest {
    @Test void reviewsFrozenVersionAndExportsThatExactVersionWithAudit() throws Exception {
        var c = mock(Connection.class);
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.createStatement()).thenReturn(mock(Statement.class));
        statement(c, "pg_advisory_xact_lock");
        var latest = statement(c, "ORDER BY calculation_version_no DESC");
        rows(latest, new Object[]{1, 801L});
        rows(statement(c, "SELECT clinic_id FROM"), new Object[]{11L});
        var manifest = AuditDocuments.parse("{\"version\":\"SYNTHETIC-v1\",\"facts\":[{\"clinic\":11}]}");
        var basis = AuditDocuments.JSON.createObjectNode().put("statisticId", "801")
                .put("quarter", "SYNTH-Q3").put("sourceVersion", "SYNTHETIC-v1").put("manifestReference", "TEST-801")
                .put("manifestHash", AuditDocuments.hash(manifest));
        rows(statement(c, "SELECT quarter_code,aggregation_readiness"), new Object[]{
                "SYNTH-Q3", "达到汇总要求", "{}", "SYNTHETIC-v1", manifest.toString(), "TEST-801", new BigDecimal("45.00"), new BigDecimal("33.33"), new BigDecimal("9.00"), new BigDecimal("5.75")});
        var profit = new BigDecimal("30.25");
        var pool = new BigDecimal("3.03");
        rows(statement(c, "generated_by,report_status"), new Object[]{basis.toString(), profit, pool, "SYNTHETIC-OPS", "待复核"});
        rows(statement(c, "SELECT dentist_id,performance_amount"), new Object[]{71L, new BigDecimal("19.00"), "SYNTHETIC basis"});
        var journal = new HashMap<String,List<Object[]>>();
        var auditRead = statement(c, "SELECT audit_event_id::text");
        var auditSeq = statement(c, "SELECT COALESCE(max(event_sequence_no)");
        var auditWrite = statement(c, "INSERT INTO adc.operation_audit_event");
        var inputs = parameters(auditWrite);
        var readInputs = parameters(auditRead);
        var sequenceInputs = parameters(auditSeq);
        when(auditSeq.executeQuery()).thenAnswer(call -> result(List.<Object[]>of(new Object[]{
                journal.getOrDefault(sequenceInputs.get(1), List.of()).size()})));
        when(auditRead.executeQuery()).thenAnswer(call -> result(journal.getOrDefault(readInputs.get(1), List.of())));
        when(auditWrite.executeUpdate()).thenAnswer(call -> {
            journal.computeIfAbsent((String) inputs.get(2), key -> new ArrayList<>()).add(new Object[]{
                    inputs.get(1), inputs.get(3), inputs.get(6), inputs.get(5), inputs.get(10), inputs.get(12)});
            return 1;
        });
        var reviewWrite = statement(c, "INSERT INTO adc.financial_review");
        var saved = parameters(reviewWrite);
        when(reviewWrite.executeUpdate()).thenReturn(1);
        var exportRead = statement(c, "JOIN adc.financial_review");
        when(exportRead.executeQuery()).thenAnswer(call -> result(List.<Object[]>of(new Object[]{
                basis.toString(), profit, pool, saved.get(1), saved.get(5), saved.get(4), saved.get(6), saved.get(7).toString()
        })));
        var scope = Set.of(11L);
        var tx = new JdbcTransactions(ds);
        var ids = new AtomicLong(6000);
        var clock = Clock.systemUTC();
        var finance = new QuarterlyFinancialReviewService(tx, grant("SYNTHETIC-FIN", "HQ_FINANCE", "UC20"),
                conn -> 505L, conn -> ids.incrementAndGet(), clock);
        var view = finance.read(1001, 1, scope);
        var approved = finance.approve(1001, 1, scope, view.path("basisHash").asText(), "SYNTHETIC checked", UUID.randomUUID());
        assertEquals("SUCCEEDED", approved.businessOutcome());
        assertEquals("505", approved.result().path("reviewId").asText());
        assertEquals(1, saved.get(3));
        var operations = new QuarterlyCalculationService(tx, grant("SYNTHETIC-OPS", "HQ_OPERATIONS", "UC19"),
                (conn, target, frozen) -> Optional.empty(), conn -> 1001L, conn -> ids.incrementAndGet(), clock);
        var exported = operations.export(1001, 1, scope, UUID.randomUUID());
        assertEquals("SUCCEEDED", exported.businessOutcome());
        assertEquals("1001", exported.result().path("calculationId").asText());
        assertEquals(1, exported.result().path("version").asInt());
        assertEquals("505", exported.result().path("reviewId").asText());
        assertEquals("19.00", exported.result().path("performance").get(0).path("amount").asText());
        assertEquals(2, journal.size());
        assertTrue(journal.values().stream().allMatch(events -> events.size() == 2 && "SUCCESS".equals(events.getLast()[4])));
        var order = inOrder(reviewWrite, auditWrite, c);
        order.verify(reviewWrite).executeUpdate();
        order.verify(auditWrite).executeUpdate();
        order.verify(c).commit();
    }

    private static StaffAuthorization grant(String actor, String role, String action) {
        return new StaffAuthorization(() -> Optional.of(actor), () -> Map.of(actor,
                new StaffAuthorization.Grant("SYNTHETIC", Set.of(role), Set.of(action), Set.of(11L), false)));
    }

    private static PreparedStatement statement(Connection c, String fragment) throws Exception {
        var statement = mock(PreparedStatement.class);
        when(c.prepareStatement(contains(fragment))).thenReturn(statement);
        return statement;
    }

    private static Map<Integer,Object> parameters(PreparedStatement sql) throws Exception {
        var values = new HashMap<Integer,Object>();
        doAnswer(call -> { values.put(call.getArgument(0),call.getArgument(1)); return null; }).when(sql).setString(anyInt(),nullable(String.class));
        doAnswer(call -> { values.put(call.getArgument(0),call.getArgument(1)); return null; }).when(sql).setLong(anyInt(),anyLong());
        doAnswer(call -> { values.put(call.getArgument(0),call.getArgument(1)); return null; }).when(sql).setInt(anyInt(),anyInt());
        doAnswer(call -> { values.put(call.getArgument(0),call.getArgument(1)); return null; }).when(sql).setObject(anyInt(),any());
        return values;
    }

    private static void rows(PreparedStatement sql, Object[]... values) throws Exception {
        when(sql.executeQuery()).thenAnswer(call -> result(Arrays.asList(values)));
    }

    private static ResultSet result(List<Object[]> values) throws Exception {
        int[] row = {-1};
        var r = mock(ResultSet.class);
        when(r.next()).thenAnswer(call -> ++row[0] < values.size());
        when(r.getString(anyInt())).thenAnswer(call -> (String) cell(values, row[0], call.getArgument(0)));
        when(r.getInt(anyInt())).thenAnswer(call -> ((Number) cell(values, row[0], call.getArgument(0))).intValue());
        when(r.getLong(anyInt())).thenAnswer(call -> ((Number) cell(values, row[0], call.getArgument(0))).longValue());
        when(r.getBigDecimal(anyInt())).thenAnswer(call -> cell(values, row[0], call.getArgument(0)));
        return r;
    }

    private static Object cell(List<Object[]> values, int row, int col) { return values.get(row)[col - 1]; }
}
