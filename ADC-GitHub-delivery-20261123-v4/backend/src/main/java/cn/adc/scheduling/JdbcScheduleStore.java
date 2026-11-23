package cn.adc.scheduling;

import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Explicit original columns. Requires the dedicated UC21 role; isolated PostgreSQL verification is pending. */
final class JdbcScheduleStore implements ScheduleStore {
    private static final String VERSIONS = "SELECT schedule_id,schedule_version_no,clinic_id,dentist_id,starts_at,ends_at,"
            + "activation_status,coordination_result,qualification_check_result,conflict_check_result,incomplete_reason,activated_at "
            + "FROM adc.reception_schedule_version ";

    public List<ScheduleStateToken.Version> versions(Connection c, long id) throws SQLException {
        try (var p = c.prepareStatement(VERSIONS + "WHERE schedule_id=? ORDER BY schedule_version_no")) {
            p.setLong(1, id);
            return rows(p);
        }
    }

    public List<Ref> list(Connection c, Set<Long> clinics, Long dentist, Instant from, Instant to, Ref after) throws SQLException {
        String query = """
                SELECT schedule_id,schedule_version_no FROM adc.reception_schedule_version
                WHERE clinic_id=ANY (?) AND (?::bigint IS NULL OR dentist_id=?)
                  AND (?::timestamptz IS NULL OR ends_at>?) AND (?::timestamptz IS NULL OR starts_at<?)
                  AND (?::bigint IS NULL OR (schedule_id,schedule_version_no)>(?,?))
                ORDER BY schedule_id,schedule_version_no LIMIT 51
                """;
        var scope = c.createArrayOf("int8", clinics.toArray(Long[]::new));
        try (var p = c.prepareStatement(query)) {
            p.setArray(1, scope);
            p.setObject(2, dentist, Types.BIGINT); p.setObject(3, dentist, Types.BIGINT);
            time(p, 4, from); time(p, 5, from); time(p, 6, to); time(p, 7, to);
            Long id = after == null ? null : after.id();
            p.setObject(8, id, Types.BIGINT); p.setObject(9, id, Types.BIGINT);
            p.setObject(10, after == null ? null : after.version(), Types.INTEGER);
            try (var r = p.executeQuery()) {
                var refs = new ArrayList<Ref>();
                while (r.next()) refs.add(new Ref(r.getLong(1), r.getInt(2)));
                return refs;
            }
        } finally {
            scope.free();
        }
    }

    public List<ScheduleStateToken.Version> overlaps(Connection c, long dentist, Window w) throws SQLException {
        try (var p = c.prepareStatement(VERSIONS
                + "WHERE dentist_id=? AND activation_status IN ('DRAFT','EFFECTIVE') AND starts_at<? AND ends_at>? "
                + "ORDER BY schedule_id,schedule_version_no")) {
            p.setLong(1, dentist); time(p, 2, w.end()); time(p, 3, w.start());
            return rows(p);
        }
    }

    public long nextId(Connection c, boolean issue) throws SQLException {
        String sql = issue ? "SELECT nextval('adc.uc21_issue_id_alloc')" : "SELECT nextval('adc.uc21_schedule_id_alloc')";
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            if (!r.next() || r.getLong(1) <= 0) throw new SQLException("IDENTITY_UNAVAILABLE", "22003");
            return r.getLong(1);
        }
    }

    public void insertDraft(Connection c, ScheduleStateToken.Version v) throws SQLException {
        try (var p = c.prepareStatement("""
                INSERT INTO adc.reception_schedule_version
                (schedule_id,schedule_version_no,clinic_id,dentist_id,starts_at,ends_at,
                 activation_status,coordination_result,qualification_check_result,conflict_check_result)
                VALUES (?,?,?,?,?,?,'DRAFT',?,?,?)
                """)) {
            p.setLong(1,v.scheduleId()); p.setInt(2,v.versionNo()); p.setLong(3,v.clinicId()); p.setLong(4,v.dentistId());
            time(p,5,v.startsAt()); time(p,6,v.endsAt());
            p.setString(7,v.coordination()); p.setString(8,v.qualification()); p.setString(9,v.conflict());
            inserted(p);
        }
    }

    public Issue issue(Connection c, long id) throws SQLException {
        try (var p = c.prepareStatement("""
                SELECT maintenance_issue_id,request_reference,clinic_id,dentist_id,
                       related_schedule_id,related_schedule_version_no,resulting_schedule_id,resulting_schedule_version_no,
                       previous_issue_id,failure_reason,request_context,recorded_by,recorded_at,requested_starts_at,requested_ends_at
                FROM adc.schedule_maintenance_issue WHERE maintenance_issue_id=?
                """)) {
            p.setLong(1,id);
            try (var r = p.executeQuery()) {
                if (!r.next()) return null;
                return new Issue(id,r.getString(2),r.getLong(3),r.getObject(4,Long.class),ref(r,5),ref(r,7),
                        r.getObject(9,Long.class),r.getString(10),r.getString(11),r.getString(12),instant(r,13),instant(r,14),instant(r,15));
            }
        }
    }

    public void insertIssue(Connection c, Issue i) throws SQLException {
        try (var p = c.prepareStatement("""
                INSERT INTO adc.schedule_maintenance_issue
                (maintenance_issue_id,request_reference,clinic_id,dentist_id,related_schedule_id,related_schedule_version_no,
                 previous_issue_id,failure_reason,request_context,recorded_by,recorded_at,requested_starts_at,requested_ends_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            p.setLong(1,i.id()); p.setString(2,i.reference()); p.setLong(3,i.clinic());
            p.setObject(4,i.dentist(),Types.BIGINT); ref(p,5,i.basis());
            p.setObject(7,i.previous(),Types.BIGINT); p.setString(8,i.reason()); p.setString(9,i.context());
            p.setString(10,i.actor()); time(p,11,i.at()); time(p,12,i.requestedStart()); time(p,13,i.requestedEnd());
            inserted(p);
        }
    }

    public boolean linkIssue(Connection c, Issue issue, Ref result) throws SQLException {
        try (var p = c.prepareStatement("""
                UPDATE adc.schedule_maintenance_issue SET resulting_schedule_id=?,resulting_schedule_version_no=?
                WHERE maintenance_issue_id=? AND clinic_id=?
                  AND related_schedule_id IS NOT DISTINCT FROM ? AND related_schedule_version_no IS NOT DISTINCT FROM ?
                  AND resulting_schedule_id IS NULL AND resulting_schedule_version_no IS NULL
                """)) {
            ref(p,1,result); p.setLong(3,issue.id()); p.setLong(4,issue.clinic()); ref(p,5,issue.basis());
            return p.executeUpdate()==1;
        }
    }

    public List<String> affectedAppointments(Connection c,long basisClinic,long proposedClinic,long schedule,long dentist,Window window,boolean preview) throws SQLException {
        // Project only IDs from the authorized basis clinic. UC22 independently verifies all actual impact.
        try (var p = c.prepareStatement("""
                SELECT a.appointment_id FROM adc.appointment a
                JOIN adc.reception_schedule_version s ON (s.schedule_id,s.schedule_version_no)=(a.schedule_id,a.schedule_version_no)
                WHERE s.clinic_id=? AND a.schedule_id=?
                  AND (s.clinic_id<>? OR s.dentist_id<>? OR a.starts_at<? OR a.ends_at>?)
                ORDER BY a.appointment_id
                """ + (preview ? " LIMIT 101" : ""))) {
            p.setLong(1,basisClinic); p.setLong(2,schedule); p.setLong(3,proposedClinic); p.setLong(4,dentist);
            time(p,5,window.start()); time(p,6,window.end());
            try (var r=p.executeQuery()){
                var rows=new ArrayList<String>();
                while(r.next())rows.add(Long.toString(r.getLong(1)));
                return rows;
            }
        }
    }

    private static List<ScheduleStateToken.Version> rows(PreparedStatement p) throws SQLException {
        try (var r=p.executeQuery()){
            var rows=new ArrayList<ScheduleStateToken.Version>();
            while(r.next())rows.add(new ScheduleStateToken.Version(r.getLong(1),r.getInt(2),r.getLong(3),r.getLong(4),
                    instant(r,5),instant(r,6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),instant(r,12)));
            return rows;
        }
    }
    private static void inserted(PreparedStatement p) throws SQLException {
        if(p.executeUpdate()!=1)throw new SQLException("WRITE_NOT_CONFIRMED","P0001");
    }
    private static Instant instant(ResultSet r,int n)throws SQLException {
        var v=r.getObject(n,OffsetDateTime.class); return v==null?null:v.toInstant();
    }
    private static void time(PreparedStatement p,int n,Instant value)throws SQLException {
        if(value==null)p.setNull(n,Types.TIMESTAMP_WITH_TIMEZONE);
        else p.setObject(n,value.atOffset(ZoneOffset.UTC));
    }
    private static Ref ref(ResultSet r,int n)throws SQLException {
        var id=r.getObject(n,Long.class); var version=r.getObject(n+1,Integer.class);
        if ((id==null)!=(version==null))throw new SQLException("INCOMPLETE_VERSION_REF","22000");
        return id==null?null:new Ref(id,version);
    }
    private static void ref(PreparedStatement p,int n,Ref ref)throws SQLException {
        p.setObject(n,ref==null?null:ref.id(),Types.BIGINT);
        p.setObject(n+1,ref==null?null:ref.version(),Types.INTEGER);
    }
}
