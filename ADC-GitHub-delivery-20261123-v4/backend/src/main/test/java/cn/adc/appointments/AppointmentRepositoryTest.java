package cn.adc.appointments;

import cn.adc.appointments.AppointmentRepository.Appointment;
import cn.adc.appointments.AppointmentRepository.ChangeEvidence;
import cn.adc.appointments.AppointmentRepository.Terms;
import cn.adc.appointments.AppointmentRepository.Visit;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.ZoneOffset;

import static cn.adc.appointments.AppointmentEvidenceCodecTest.DOCUMENT;
import static cn.adc.appointments.AppointmentEvidenceCodecTest.END;
import static cn.adc.appointments.AppointmentEvidenceCodecTest.START;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JDBC statement and transaction tests. No database or application context. */
class AppointmentRepositoryTest {
    private static final String LEGACY = " 原始文本\r\n　保留原空格与需求 ";
    private static final Terms OLD = new Terms(new Version(87001, 2), START.minusSeconds(3600), END.minusSeconds(3600),
            LEGACY, "原提醒\r\n");
    private static final Terms NEW = new Terms(new Version(87002, 3), START, END, DOCUMENT, "新提醒");
    private static final String ACCEPTANCE = "SYNTHETIC  实际接受\n";
    private static final String PROXY = "SYNTHETIC 代理核对\n";
    private static final ChangeEvidence CHANGE = new ChangeEvidence(51001, ACCEPTANCE, "SYNTHETIC 改约原因",
            PROXY, "SYNTHETIC-reception", START, null);
    private final AppointmentRepository repository = new AppointmentRepository();
    private final Connection connection = stub(Connection.class);
    private final PreparedStatement statement = stub(PreparedStatement.class);
    private final ResultSet rows = stub(ResultSet.class);

    @BeforeEach void setUp() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(statement.executeUpdate()).thenReturn(1);
        when(rows.next()).thenReturn(true);
    }

    @Test void t02704MinimalOriginLookupUsesBothHistoricalKeyPartsAndClinic() throws Exception {
        version(1, OLD.schedule());
        assertEquals(OLD.schedule(), repository.findOrigin(connection, 901, 81001).orElseThrow());
        String sql = statementSql();
        assertTrue(sql.startsWith("SELECT schedule_id, schedule_version_no FROM adc.appointment"));
        assertTrue(sql.contains("(v.schedule_id,v.schedule_version_no)=(a.schedule_id,a.schedule_version_no)"));
        assertTrue(sql.contains("v.clinic_id=?"));
        assertFalse(sql.contains("EFFECTIVE"));
        verify(statement).setLong(1, 901);
        verify(statement).setLong(2, 81001);
        when(rows.next()).thenReturn(false);
        assertTrue(repository.findOrigin(connection, 901, 81002).isEmpty());
    }

    @Test void t02705ReadsOldAppointmentWithoutReconstructingBasisOrReplacingNulls() throws Exception {
        when(rows.getLong(1)).thenReturn(901L);
        when(rows.getLong(2)).thenReturn(88001L);
        version(3, OLD.schedule());
        when(rows.getObject(5, java.time.OffsetDateTime.class)).thenReturn(OLD.startsAt().atOffset(ZoneOffset.ofHours(8)));
        when(rows.getObject(6, java.time.OffsetDateTime.class)).thenReturn(OLD.endsAt().atOffset(ZoneOffset.UTC));
        when(rows.getString(7)).thenReturn(LEGACY);
        when(rows.getString(8)).thenReturn(ACCEPTANCE);
        when(rows.getString(9)).thenReturn("待到诊");
        when(rows.getString(11)).thenReturn(OLD.reminder());
        when(rows.getString(13)).thenReturn("SYNTHETIC-reception");
        when(rows.getObject(14, java.time.OffsetDateTime.class)).thenReturn(START.atOffset(ZoneOffset.UTC));
        when(rows.getString(15)).thenReturn(PROXY);
        assertEquals(appointment(901, 88001, OLD, "待到诊"), repository.findAppointment(connection, 901, 81001).orElseThrow());
        when(rows.getObject(4, Integer.class)).thenReturn(null);
        assertEquals("22000", assertThrows(SQLException.class, () -> repository.findAppointment(connection, 901, 81001)).getSQLState());
    }

    @Test void t02706InsertBindsOriginalIdsBothKeyPartsEvidenceAndMicros() throws Exception {
        repository.insertAppointment(connection, appointment(901, 88001, NEW, "待到诊"));
        assertTrue(statementSql().startsWith("INSERT INTO adc.appointment ("));
        verify(statement).setLong(1, 901);
        verify(statement).setLong(2, 88001);
        verify(statement).setLong(3, 87002);
        verify(statement).setInt(4, 3);
        verify(statement).setObject(5, START.atOffset(ZoneOffset.UTC));
        verify(statement).setObject(6, END.atOffset(ZoneOffset.UTC));
        verify(statement).setString(7, DOCUMENT);
        verify(statement).setString(8, ACCEPTANCE);
        verify(statement).setString(9, "待到诊");
        verify(statement).setString(10, null);
        verify(statement).setString(11, NEW.reminder());
        verify(statement).setString(12, null);
        verify(statement).setString(13, "SYNTHETIC-reception");
        verify(statement).setObject(14, START.atOffset(ZoneOffset.UTC));
        verify(statement).setString(15, PROXY);
        verify(statement).setString(16, null);
        verify(connection, never()).commit();
    }

    @Test void t02707UpdateKeepsIdentityAndRetainsOpaqueLegacyOnStatusOnlyWrite() throws Exception {
        var before = appointment(901, 88001, OLD, "待到诊");
        repository.updateAppointment(connection, before, appointment(901, 88001, OLD, "待核实"));
        String sql = statementSql();
        assertTrue(sql.contains("WHERE appointment_id=? AND patient_id=?"));
        assertFalse(sql.substring(0, sql.indexOf("WHERE")).contains("appointment_id"));
        verify(statement).setString(5, LEGACY);
        verify(statement).setLong(15, 901);
        verify(statement).setLong(16, 88001);
    }

    @Test void t02708MalformedNewEvidenceOrMismatchedIdentityNeverIssuesSql() throws Exception {
        var old = appointment(901, 88001, OLD, "待到诊");
        assertThrows(IllegalArgumentException.class, () -> repository.insertAppointment(connection, old));
        for (var invalid : new Appointment[]{appointment(902, 88001, NEW, "待到诊"),
                appointment(901, 88002, NEW, "待到诊")}) {
            assertThrows(IllegalArgumentException.class, () -> repository.updateAppointment(connection, old, invalid));
            assertThrows(IllegalArgumentException.class, () -> repository.appendChange(connection, old, invalid, CHANGE));
        }
        var nanos = new Terms(NEW.schedule(), START.plusNanos(1), END, DOCUMENT, NEW.reminder());
        assertThrows(IllegalArgumentException.class, () -> repository.insertAppointment(connection, appointment(901, 88001, nanos, "待到诊")));
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test void t02709ChangeBindsExactOldAndNewEvidenceWithoutChangingAppointmentId() throws Exception {
        repository.appendChange(connection, appointment(901, 88001, OLD, "待到诊"),
                appointment(901, 88001, NEW, "待到诊"), CHANGE);
        assertTrue(statementSql().startsWith("INSERT INTO adc.appointment_change_history ("));
        verify(statement).setLong(1, 51001);
        verify(statement).setLong(2, 901);
        verify(statement).setLong(3, 87001);
        verify(statement).setInt(4, 2);
        verify(statement).setLong(5, 87002);
        verify(statement).setInt(6, 3);
        verify(statement).setObject(7, OLD.startsAt().atOffset(ZoneOffset.UTC));
        verify(statement).setObject(8, OLD.endsAt().atOffset(ZoneOffset.UTC));
        verify(statement).setObject(9, START.atOffset(ZoneOffset.UTC));
        verify(statement).setObject(10, END.atOffset(ZoneOffset.UTC));
        verify(statement).setString(11, LEGACY);
        verify(statement).setString(12, DOCUMENT);
        verify(statement).setString(13, ACCEPTANCE);
        verify(statement).setString(14, CHANGE.reason());
        verify(statement).setString(15, PROXY);
        verify(statement).setString(16, OLD.reminder());
        verify(statement).setString(17, NEW.reminder());
        verify(statement).setString(18, CHANGE.actor());
        verify(statement).setObject(19, CHANGE.changedAt().atOffset(ZoneOffset.UTC));
        verify(statement).setString(20, null);
    }

    @Test void t02710ChangeReadIsBoundToOriginalAppointmentAndKeepsBothDocuments() throws Exception {
        when(rows.getLong(1)).thenReturn(51001L);
        when(rows.getLong(2)).thenReturn(901L);
        version(3, OLD.schedule());
        version(5, NEW.schedule());
        when(rows.getObject(7, java.time.OffsetDateTime.class)).thenReturn(OLD.startsAt().atOffset(ZoneOffset.UTC));
        when(rows.getObject(8, java.time.OffsetDateTime.class)).thenReturn(OLD.endsAt().atOffset(ZoneOffset.UTC));
        when(rows.getObject(9, java.time.OffsetDateTime.class)).thenReturn(START.atOffset(ZoneOffset.UTC));
        when(rows.getObject(10, java.time.OffsetDateTime.class)).thenReturn(END.atOffset(ZoneOffset.UTC));
        when(rows.getString(11)).thenReturn(LEGACY);
        when(rows.getString(12)).thenReturn(DOCUMENT);
        when(rows.getString(13)).thenReturn(ACCEPTANCE);
        when(rows.getString(14)).thenReturn(CHANGE.reason());
        when(rows.getString(15)).thenReturn(PROXY);
        when(rows.getString(16)).thenReturn(OLD.reminder());
        when(rows.getString(17)).thenReturn(NEW.reminder());
        when(rows.getString(18)).thenReturn(CHANGE.actor());
        when(rows.getObject(19, java.time.OffsetDateTime.class)).thenReturn(START.atOffset(ZoneOffset.UTC));
        var result = repository.findChange(connection, appointment(901, 88001, NEW, "待到诊"), 51001).orElseThrow();
        assertEquals(new AppointmentRepository.Change(901, OLD, NEW, CHANGE), result);
        assertTrue(statementSql().contains("WHERE appointment_change_id=? AND appointment_id=?"));
        verify(statement).setLong(1, 51001);
        verify(statement).setLong(2, 901);
    }

    @Test void t02711VisitKeepsActualFactsAndNullableArrivalTime() throws Exception {
        var appointment = appointment(901, 88001, OLD, "已到诊");
        var visit = new Visit(310901, 901, 81001, 79001, START, "SYNTHETIC核实", "SYNTHETIC-reception", null);
        repository.insertVisit(connection, appointment, visit);
        verify(connection).prepareStatement(contains("INSERT INTO adc.visit"));
        verify(statement).setLong(1, 310901);
        verify(statement).setLong(2, 901);
        verify(statement).setLong(3, 81001);
        verify(statement).setLong(4, 79001);
        verify(statement).setObject(5, START.atOffset(ZoneOffset.UTC));
        verify(statement).setString(6, visit.verification());
        verify(statement).setString(7, visit.actor());
        verify(statement).setNull(8, Types.TIMESTAMP_WITH_TIMEZONE);
        when(rows.getLong(1)).thenReturn(visit.id());
        when(rows.getLong(2)).thenReturn(901L);
        when(rows.getLong(3)).thenReturn(visit.clinicId());
        when(rows.getLong(4)).thenReturn(visit.dentistId());
        when(rows.getObject(5, java.time.OffsetDateTime.class)).thenReturn(START.atOffset(ZoneOffset.UTC));
        when(rows.getString(6)).thenReturn(visit.verification());
        when(rows.getString(7)).thenReturn(visit.actor());
        assertEquals(visit, repository.findVisit(connection, appointment).orElseThrow());
    }

    @Test void t02712PendingOrWrongVisitAndMissingProxyHistoryAreRejectedBeforeSql() throws Exception {
        var visit = new Visit(310901, 901, 81001, 79001, START, "SYNTHETIC", "SYNTHETIC", null);
        assertThrows(IllegalArgumentException.class, () -> repository.insertVisit(connection, appointment(901, 88001, OLD, "待核实"), visit));
        assertThrows(IllegalArgumentException.class, () -> repository.insertVisit(connection, appointment(902, 88001, OLD, "已到诊"), visit));
        var incomplete = new ChangeEvidence(51001, ACCEPTANCE, "SYNTHETIC", null, "SYNTHETIC", START, null);
        assertThrows(IllegalArgumentException.class, () -> repository.appendChange(connection, appointment(901, 88001, OLD, "待到诊"),
                appointment(901, 88001, NEW, "待到诊"), incomplete));
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test void t02713MissingWriteOrDriverFailurePropagatesToOwningTransaction() throws Exception {
        var source = stub(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(stub(Statement.class));
        when(statement.executeUpdate()).thenReturn(1, 0).thenThrow(new SQLException("SYNTHETIC duplicate", "23505"));
        var before = appointment(901, 88001, OLD, "待到诊");
        var after = appointment(901, 88001, NEW, "待到诊");
        var tx = new JdbcTransactions(source);
        var missing = assertThrows(JdbcTransactions.Failure.class, () -> tx.execute(c -> {
            repository.updateAppointment(c, before, after);
            repository.appendChange(c, before, after, CHANGE);
            return null;
        }));
        assertEquals("P0001", missing.sqlState());
        var duplicate = assertThrows(JdbcTransactions.Failure.class,
                () -> tx.execute(c -> { repository.insertAppointment(c, after); return null; }));
        assertEquals("23505", duplicate.sqlState());
        verify(connection, times(2)).rollback();
        verify(connection, never()).commit();
    }

    @Test void t02714WritesRequireCallerTransaction() throws Exception {
        when(connection.getAutoCommit()).thenReturn(true);
        var error = assertThrows(SQLException.class, () -> repository.insertAppointment(connection, appointment(901, 88001, NEW, "待到诊")));
        assertEquals("25000", error.getSQLState());
        verify(connection, never()).prepareStatement(anyString());
    }

    private String statementSql() throws Exception {
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        return sql.getValue().replaceAll("\\s+", " ").strip();
    }

    private void version(int column, Version key) throws Exception {
        when(rows.getObject(column, Long.class)).thenReturn(key.scheduleId());
        when(rows.getObject(column + 1, Integer.class)).thenReturn(key.versionNo());
    }

    private static Appointment appointment(long id, long patient, Terms terms, String status) {
        return new Appointment(id, patient, terms, ACCEPTANCE, status, null, null,
                "SYNTHETIC-reception", START, PROXY, null);
    }

    private static <T> T stub(Class<T> type) {
        return mock(type, withSettings().mockMaker(MockMakers.PROXY));
    }
}
