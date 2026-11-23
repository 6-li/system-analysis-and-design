package cn.adc.scheduling;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mock JDBC plumbing for the real command journal. Never opens a database connection. */
final class T024MemoryJournal {
    private final List<Map<Integer, Object>> events = new ArrayList<>();
    private final Map<Connection, List<Runnable>> changes = new IdentityHashMap<>();
    boolean failNextSuccessAudit;
    boolean loseNextStartReply;
    boolean loseNextSuccessReply;

    DataSource source() throws SQLException {
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenAnswer(call -> connection());
        return ds;
    }

    void stage(Connection connection, Runnable write) {
        Objects.requireNonNull(changes.get(connection)).add(write);
    }

    long successes() {
        return events.stream().filter(event -> "SUCCESS".equals(event.get(10))).count();
    }

    private Connection connection() throws SQLException {
        var c = mock(Connection.class);
        var pending = new ArrayList<Map<Integer, Object>>();
        var writes = new ArrayList<Runnable>();
        changes.put(c, writes);
        when(c.createStatement()).thenReturn(mock(Statement.class));
        when(c.prepareStatement(anyString())).thenAnswer(call -> statement(call.getArgument(0), pending));
        doAnswer(call -> {
            boolean loseStart = loseNextStartReply && pending.stream().anyMatch(e -> "STARTED".equals(e.get(10)));
            boolean loseSuccess = loseNextSuccessReply && pending.stream().anyMatch(e -> "SUCCESS".equals(e.get(10)));
            writes.forEach(Runnable::run);
            events.addAll(pending);
            writes.clear();
            pending.clear();
            if (loseStart) loseNextStartReply = false;
            if (loseSuccess) loseNextSuccessReply = false;
            if (loseStart || loseSuccess) throw new SQLException("SYNTHETIC commit reply lost", "08006");
            return null;
        }).when(c).commit();
        doAnswer(call -> { writes.clear(); pending.clear(); return null; }).when(c).rollback();
        doAnswer(call -> { changes.remove(c); return null; }).when(c).close();
        return c;
    }

    private PreparedStatement statement(String sql, List<Map<Integer, Object>> pending) throws SQLException {
        var p = mock(PreparedStatement.class);
        if (!sql.contains("adc.operation_audit_event")) {
            // Lock statements are only observed by the real coordinator; no engine executes them.
            if (!sql.startsWith("SELECT pg_advisory_xact_lock") && !sql.startsWith("SELECT adc_uc21_lock.")) {
                throw new AssertionError("Unexpected JDBC path in T024 demo: " + sql);
            }
            return p;
        }
        var values = new HashMap<Integer, Object>();
        doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(p).setString(anyInt(), nullable(String.class));
        doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(p).setInt(anyInt(), anyInt());
        when(p.executeUpdate()).thenAnswer(call -> {
            if (failNextSuccessAudit && "SUCCESS".equals(values.get(10))) {
                failNextSuccessAudit = false;
                throw new SQLException("SYNTHETIC audit failure", "23514");
            }
            pending.add(new HashMap<>(values));
            return 1;
        });
        when(p.executeQuery()).thenAnswer(call -> {
            var matches = events.stream().filter(row -> Objects.equals(row.get(2), values.get(1))).toList();
            var row = mock(ResultSet.class);
            if (sql.contains("COALESCE")) {
                when(row.next()).thenReturn(true, false);
                when(row.getInt(1)).thenReturn(matches.stream().mapToInt(e -> (int) e.get(3)).max().orElse(0));
                return row;
            }
            var i = new AtomicInteger(-1);
            when(row.next()).thenAnswer(next -> i.incrementAndGet() < matches.size());
            when(row.getInt(2)).thenAnswer(col -> matches.get(i.get()).get(3));
            when(row.getString(anyInt())).thenAnswer(col -> {
                int index = switch (col.<Integer>getArgument(0)) {
                    case 1 -> 1;
                    case 3 -> 6;
                    case 4 -> 5;
                    case 5 -> 10;
                    case 6 -> 12;
                    default -> throw new AssertionError("Unexpected audit read column");
                };
                return matches.get(i.get()).get(index);
            });
            return row;
        });
        return p;
    }
}
