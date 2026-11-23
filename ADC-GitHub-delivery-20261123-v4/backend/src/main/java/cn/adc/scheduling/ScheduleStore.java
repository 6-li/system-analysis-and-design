package cn.adc.scheduling;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

interface ScheduleStore {
    record Ref(long id, int version) {}
    record Window(Instant start, Instant end) {}
    record Issue(long id, String reference, long clinic, Long dentist, Ref basis, Ref result, Long previous,
                 String reason, String context, String actor, Instant at, Instant requestedStart, Instant requestedEnd) {}

    List<ScheduleStateToken.Version> versions(Connection c, long id) throws SQLException;
    List<Ref> list(Connection c, Set<Long> clinics, Long dentist, Instant from, Instant to, Ref after) throws SQLException;
    List<ScheduleStateToken.Version> overlaps(Connection c, long dentist, Window window) throws SQLException;
    long nextId(Connection c, boolean issue) throws SQLException;
    void insertDraft(Connection c, ScheduleStateToken.Version draft) throws SQLException;
    Issue issue(Connection c, long id) throws SQLException;
    void insertIssue(Connection c, Issue issue) throws SQLException;
    boolean linkIssue(Connection c, Issue issue, Ref result) throws SQLException;
    List<String> affectedAppointments(Connection c, long basisClinic, long proposedClinic, long schedule,
                                      long dentist, Window window, boolean preview) throws SQLException;
}
