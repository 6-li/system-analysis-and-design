package cn.adc.appointments;

import java.sql.Connection;
import java.sql.SQLException;

/** Proposed course sequence bindings. Inactive until a course writer explicitly wires them. */
final class CourseBookingIds {
    private CourseBookingIds() {}

    static long appointment(Connection connection) throws SQLException {
        return next(connection, "SELECT nextval('adc.course_appointment_id_seq'::regclass)");
    }

    static long audit(Connection connection) throws SQLException {
        return next(connection, "SELECT nextval('adc.course_uc_audit_id_seq'::regclass)");
    }

    static long change(Connection connection) throws SQLException {
        return next(connection, "SELECT nextval('adc.course_change_id_seq'::regclass)");
    }

    static long patient(Connection connection) throws SQLException {
        return next(connection, "SELECT nextval('adc.course_patient_id_seq'::regclass)");
    }

    private static long next(Connection connection, String sql) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("COURSE_ID_REQUIRES_TRANSACTION", "25000");
        try (var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            if (rows.next()) {
                long value = rows.getLong(1);
                if (value > 0 && !rows.next()) return value;
            }
        }
        throw new SQLException("COURSE_ID_UNAVAILABLE", "22003");
    }
}
