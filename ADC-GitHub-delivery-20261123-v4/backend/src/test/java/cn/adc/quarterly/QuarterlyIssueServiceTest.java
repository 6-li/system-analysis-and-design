package cn.adc.quarterly;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
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

class QuarterlyIssueServiceTest {
    @Test void clinicCorrectionAndHeadquartersConsistencyCheckKeepTheSameHistory() throws Exception {
        var clinicTarget = new QuarterlyIssueService.Target(801, 901, Set.of(11L));
        var headquartersTarget = new QuarterlyIssueService.Target(801, 901, Set.of(11L, 12L));
        var source = mock(QuarterlyIssueService.Sources.class);
        var clinic = connection(null, null, "待核实");
        var append = write(clinic, "INSERT INTO adc.quarterly_issue_correction_history");
        var issue = write(clinic, "SET corrected_value=");
        when(source.verify(eq(clinic), eq(clinicTarget), any(), eq("SYNTHETIC proof"), eq("SYNTHETIC verified value")))
                .thenReturn(new QuarterlyIssueService.Correction("SYNTHETIC verified value", "SYNTHETIC proof",
                        null, null));
        var corrected = service("CLINIC_MANAGER", "UC18_CORRECTION", source).write(clinic, clinicTarget, true,
                null, "SYNTHETIC proof", "SYNTHETIC verified value", "SYNTHETIC UC18 first request");
        assertEquals("1001", corrected.after().path("historyId").asText());
        assertEquals("待复核", corrected.after().path("status").asText());
        var correctionOrder = inOrder(append, issue);
        correctionOrder.verify(append).setString(3, null);
        correctionOrder.verify(append).setString(4, "SYNTHETIC verified value");
        correctionOrder.verify(append).executeUpdate();
        correctionOrder.verify(issue).executeUpdate();

        var hq = connection(1001L, "SYNTHETIC verified value", "待复核");
        var check = write(hq, "SET consistency_check_result=");
        var mark = write(hq, "SET correction_status=?,consistency_check_result=?");
        when(source.recheck(eq(hq), eq(headquartersTarget), any(), eq(1001L)))
                .thenReturn(new QuarterlyIssueService.Check(true, "SYNTHETIC consistency passed", "SYNTHETIC source v1 replay"));
        var reviewed = service("HQ_OPERATIONS", "UC18", source).write(hq, headquartersTarget, false,
                1001L, null, null, "SYNTHETIC UC18 check request");
        assertEquals(corrected.businessReference(), reviewed.businessReference());
        assertEquals("复核通过", reviewed.after().path("status").asText());
        assertEquals("SYNTHETIC consistency passed", reviewed.after().path("consistencyResult").asText());
        var reviewOrder = inOrder(check, mark);
        reviewOrder.verify(check).setString(2, "SYNTHETIC source v1 replay");
        reviewOrder.verify(check).setLong(5, 1001);
        reviewOrder.verify(check).executeUpdate();
        reviewOrder.verify(mark).executeUpdate();
        verify(hq, never()).prepareStatement(contains("UPDATE adc.quarterly_operating_statistic"));
    }

    private static QuarterlyIssueService service(String role, String action, QuarterlyIssueService.Sources source) {
        String actor = "SYNTHETIC " + role;
        var grant = new StaffAuthorization.Grant("fixture", Set.of(role), Set.of(action), Set.of(11L,12L), false);
        return new QuarterlyIssueService(new JdbcTransactions(mock(DataSource.class)),
                new StaffAuthorization(() -> Optional.of(actor), () -> Map.of(actor, grant)),
                source, c -> 1001L, c -> 501L, Clock.systemUTC());
    }

    private static Connection connection(Long history, String current, String status) throws Exception {
        var c = mock(Connection.class);
        query(c, "FOR UPDATE", true);
        var row = query(c, "SELECT i.clinic_id,i.original_data", true);
        when(row.getObject(1, Long.class)).thenReturn(11L);
        when(row.getString(2)).thenReturn("SYNTHETIC source amount missing");
        when(row.getString(3)).thenReturn(current);
        when(row.getString(4)).thenReturn(status);
        when(row.getString(5)).thenReturn("SYNTHETIC version 1");
        when(row.getObject(6, Long.class)).thenReturn(history);
        var members = query(c, "SELECT clinic_id FROM adc.quarterly_statistic_clinic", true, true, false);
        when(members.getLong(1)).thenReturn(11L, 12L);
        return c;
    }

    private static ResultSet query(Connection c, String fragment, boolean first, Boolean... rest) throws Exception {
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(c.prepareStatement(contains(fragment))).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(first, rest);
        return rows;
    }

    private static PreparedStatement write(Connection c, String fragment) throws Exception {
        var statement = mock(PreparedStatement.class);
        when(c.prepareStatement(contains(fragment))).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        return statement;
    }
}
