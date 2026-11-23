package cn.adc.shared.config;

import cn.adc.PgITSupport;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.db.P0DriverDataSource;
import cn.adc.shared.resource.ResourceLockCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGConnectionPoolDataSource;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ADC_P0_IT_ENABLED", matches = "true")
class ConfigRoleIsolationIT {
    @Test
    void configurationReadsButCannotWriteEitherOriginalTable() throws Exception {
        var reader = new JdbcReferenceDataReader(PgITSupport.config());
        assertTrue(reader.clinic(1).isPresent());
        assertTrue(reader.dentist(1).isPresent());
        try (var c = PgITSupport.config().getConnection()) {
            assertEquals("p0_config_app:p0_config_app", scalar(c, "SELECT session_user || ':' || current_user"));
            for (String table : List.of("clinic", "dentist")) {
                assertEquals("true", scalar(c, "SELECT has_table_privilege('adc." + table + "','SELECT')::text"));
                for (String action : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                    assertEquals("false", scalar(c, "SELECT has_table_privilege('adc." + table + "','" + action + "')::text"));
                }
                assertEquals("false", scalar(c, "SELECT (has_any_column_privilege('adc." + table
                        + "','INSERT') OR has_any_column_privilege('adc." + table + "','UPDATE'))::text"));
            }
            for (String sql : List.of(
                    "INSERT INTO adc.clinic VALUES (91001,'SYNTHETIC')",
                    "INSERT INTO adc.dentist VALUES (91001,'SYNTHETIC')",
                    "UPDATE adc.clinic SET clinic_name='SYNTHETIC' WHERE clinic_id=1",
                    "UPDATE adc.dentist SET dentist_id=91001 WHERE dentist_id=1",
                    "DELETE FROM adc.clinic WHERE clinic_id=1",
                    "DELETE FROM adc.dentist WHERE dentist_id=1")) {
                denied(c, sql);
            }
        }
    }

    @Test
    void fixtureAndProbeCannotBeAssumedByConfigurationOrViceVersa() throws Exception {
        try (var config = PgITSupport.config().getConnection(); var fixture = PgITSupport.fixture().getConnection()) {
            assertEquals("p0_config_fixture:p0_config_fixture", scalar(fixture, "SELECT session_user || ':' || current_user"));
            for (String role : List.of("p0_config_fixture", "p0_probe_app", "p0_fixture_owner", "adc_p0_validator")) {
                assertEquals("false:false", scalar(config, "SELECT pg_has_role(current_user,'" + role
                        + "','MEMBER')::text || ':' || pg_has_role(current_user,'" + role + "','USAGE')::text"));
                denied(config, "SET ROLE " + role);
            }
            denied(fixture, "SET ROLE p0_config_app");
            denied(fixture, "SELECT project_id FROM adc.project_duration_version");
            fixture.setAutoCommit(false);
            try (var s = fixture.createStatement()) {
                assertEquals(1, s.executeUpdate("INSERT INTO adc.clinic VALUES (91002,'SYNTHETIC')"));
                assertEquals(1, s.executeUpdate("INSERT INTO adc.dentist VALUES (91002,'SYNTHETIC')"));
            }
            fixture.rollback();
            assertEquals("0", scalar(config, "SELECT count(*) FROM adc.clinic WHERE clinic_id=91002"));
            assertEquals("0", scalar(config, "SELECT count(*) FROM adc.dentist WHERE dentist_id=91002"));
        }
    }

    @Test
    void unchangedDentistLockWorksForProbeAndFailsClosedForConfiguration() throws Exception {
        var failure = assertThrows(JdbcTransactions.Failure.class, () ->
                new JdbcTransactions(PgITSupport.config()).execute(c -> {
                    new ResourceLockCoordinator().scope(c).dentists(List.of(1L));
                    return null;
                }));
        assertEquals("42501", failure.sqlState());
        assertFalse(failure.unknown());
        try (var first = PgITSupport.app().getConnection(); var second = PgITSupport.app().getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            new ResourceLockCoordinator().scope(first).dentists(List.of(1L));
            try (var s = second.createStatement()) {
                s.execute("SET LOCAL lock_timeout='150ms'");
            }
            assertEquals("55P03", assertThrows(SQLException.class,
                    () -> new ResourceLockCoordinator().scope(second).dentists(List.of(1L))).getSQLState());
            second.rollback();
            first.rollback();
            new ResourceLockCoordinator().scope(second).dentists(List.of(1L));
            second.rollback();
        }
    }

    @Test
    void pooledConnectionKeepsIdentityAndEnforcesRevocationAfterRollbackAndReuse() throws Exception {
        var uri = URI.create(System.getenv("ADC_P0_TEST_URL").substring("jdbc:".length()));
        // Reuse the exact target already validated by the isolated data source.
        try (var verified = PgITSupport.config().getConnection()) {
            assertEquals("p0_config_app", scalar(verified, "SELECT current_user"));
        }
        var ds = new PGConnectionPoolDataSource();
        ds.setServerNames(new String[]{uri.getHost()});
        ds.setPortNumbers(new int[]{uri.getPort()});
        ds.setDatabaseName(uri.getPath().substring(1));
        ds.setSslMode("disable");
        var pool = ds.getPooledConnection("p0_config_app", System.getenv("ADC_P0_CONFIG_PASSWORD"));
        try (var owner = PgITSupport.owner().getConnection(); var admin = owner.createStatement()) {
            String pid;
            try (var first = pool.getConnection()) {
                pid = scalar(first, "SELECT pg_backend_pid()");
                first.setAutoCommit(false);
                denied(first, "UPDATE adc.dentist SET dentist_id=91003 WHERE dentist_id=1");
                first.rollback();
                assertEquals("p0_config_app", scalar(first, "SELECT current_user"));
            }
            admin.execute("REVOKE SELECT ON adc.clinic FROM p0_config_app");
            try (var second = pool.getConnection()) {
                assertEquals(pid, scalar(second, "SELECT pg_backend_pid()"));
                assertTrue(second.getAutoCommit());
                assertEquals("p0_config_app", scalar(second, "SELECT current_user"));
                denied(second, "SELECT clinic_id FROM adc.clinic");
            } finally {
                admin.execute("GRANT SELECT ON adc.clinic TO p0_config_app");
            }
            try (var third = pool.getConnection()) {
                assertEquals(pid, scalar(third, "SELECT pg_backend_pid()"));
                assertEquals("1", scalar(third, "SELECT clinic_id FROM adc.clinic WHERE clinic_id=1"));
                denied(third, "DELETE FROM adc.clinic WHERE clinic_id=1");
            }
        } finally {
            pool.close();
        }
    }

    @Test
    void bothRuntimeAndFixtureAreDeniedAtTheOtherIsolatedDatabaseBoundary() throws Exception {
        String migration = System.getenv("ADC_P0_MIGRATION_URL");
        try (var owner = PgITSupport.migration().getConnection()) {
            assertEquals("adc_p0_batch1_migration", scalar(owner, "SELECT current_database()"));
        }
        for (var ds : List.of(PgITSupport.configAt(migration, -1), PgITSupport.fixtureAt(migration))) {
            assertEquals("42501", assertThrows(SQLException.class, () -> {
                try (var ignored = ds.getConnection()) {
                    fail("Unexpected cross-database connection");
                }
            }).getSQLState());
        }
        for (String user : List.of("p0_config_app", "p0_config_fixture")) {
            for (String url : List.of("jdbc:postgresql://127.0.0.1:5432/adc_p0_batch1_test",
                    "jdbc:postgresql://127.0.0.1:59605/adc_test",
                    "jdbc:postgresql://example.invalid:59605/adc_p0_batch1_test")) {
                assertThrows(IllegalArgumentException.class, () -> new P0DriverDataSource(url, user, () -> {
                    fail("Rejected target must not request a credential");
                    return null;
                }));
            }
        }
    }

    private static void denied(Connection c, String sql) {
        assertEquals("42501", assertThrows(SQLException.class, () -> {
            try (var s = c.createStatement()) {
                s.execute(sql);
            }
        }).getSQLState(), sql);
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var result = s.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            return result.getString(1);
        }
    }
}
