package cn.adc.appointments;

import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.config.DentistEligibilityProvider;
import cn.adc.shared.config.SourceStamp;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static cn.adc.shared.audit.AuditDocuments.JSON;
import static org.junit.jupiter.api.Assertions.*;

public class T028IsolatedPgIT {
    private static final long CLINIC = 81001;
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final SourceStamp STAMP = new SourceStamp("SYNTHETIC", "t028-v1", "isolated", NOW, true);
    private static final String ACTOR = "SYNTHETIC-staff";
    private static final AtomicLong IDS = new AtomicLong(1000);
    private static final AtomicLong AUDIT = new AtomicLong(9000);
    private final StaffAuthorization auth = new StaffAuthorization(() -> Optional.of(ACTOR),
            () -> Map.of(ACTOR, new StaffAuthorization.Grant("t028-v1", Set.of("BOOKING_RECEPTION"),
                    Set.of("UC02"), Set.of(CLINIC), false)));

    @Test void pg01ActualRoleAndScopedHistory() throws Exception {
        var db = new IsolatedData("runtime", 0);
        try (var c = db.getConnection()) {
            var allowed = query(c, """
                    SELECT has_table_privilege('adc.appointment','INSERT')
                       AND has_table_privilege('adc.appointment','SELECT')
                       AND NOT has_table_privilege('adc.appointment','UPDATE,DELETE')
                       AND NOT has_table_privilege('adc.operation_audit_event','UPDATE,DELETE')
                       AND NOT has_column_privilege('adc.dentist','practice_qualification','UPDATE')
                       AND NOT pg_has_role('p0_t028_publisher','MEMBER')
                    """);
            assertEquals("t", allowed);
            var denied = assertThrows(SQLException.class, () -> execute(c, "SET ROLE p0_t028_publisher"));
            assertEquals("42501", denied.getSQLState());
            denied = assertThrows(SQLException.class, () -> execute(c, "DELETE FROM adc.appointment WHERE appointment_id=901"));
            assertEquals("42501", denied.getSQLState());
        }
        var reads = new AppointmentReadService(new JdbcTransactions(db), auth, CLOCK);
        var detail = reads.detail(CLINIC, 901).orElseThrow();
        assertEquals(1, detail.at("/originScheduleRef/versionNo").asInt());
        assertEquals(2, detail.at("/currentSupport/effectiveScheduleRef/versionNo").asInt());
        assertEquals("SUPPORTED", reads.support(CLINIC, 901).orElseThrow().path("result").asText());
        assertFalse(detail.has("durationBasis"));
        assertTrue(reads.detail(CLINIC, 902).isEmpty());
        assertThrows(SecurityException.class, () -> reads.support(81002, 902));
        Files.writeString(Path.of(required("ADC_T028_EVIDENCE"), "pg01-projection.json"), detail.toString());
    }

    @Test void pg03CapacityRejectsOverlapButAllowsExactAdjacency() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var overlap = service(db, 85001, "09:26:59.999999", false)
                .book(CLINIC, UUID.randomUUID(), input(85001, "09:26:59.999999"));
        assertEquals("APPOINTMENT_CAPACITY_CONFLICT", overlap.reason());
        var adjacent = service(db, 85001, "09:27:00", false)
                .book(CLINIC, UUID.randomUUID(), input(85001, "09:27:00"));
        assertEquals("SUCCEEDED", adjacent.businessOutcome());
        assertEquals("1", count(db, "SELECT count(*) FROM adc.appointment WHERE starts_at='2026-11-08 09:27Z'"));
        assertEquals("0", count(db, "SELECT count(*) FROM adc.appointment WHERE starts_at='2026-11-08 09:26:59.999999Z'"));
    }

    @Test void pg05SuccessAuditCollisionRollsBackBooking() throws Exception {
        var db = new IsolatedData("runtime", 0);
        var service = service(db, 85001, "10:00:00", true);
        var receipt = service.book(CLINIC, UUID.randomUUID(), input(85001, "10:00:00"));
        assertEquals("FAILED", receipt.businessOutcome());
        assertEquals("CONFIRMED", receipt.auditPersistence().name());
        assertEquals("0", count(db, "SELECT count(*) FROM adc.appointment WHERE starts_at='2026-11-08 10:00Z'"));
        assertEquals("FAILED,STARTED", outcomes(db, receipt.requestReference()));
    }

    @Test void pg06ActualCommitWithInjectedLostAcknowledgmentRecoversWithoutReplay() throws Exception {
        var db = new IsolatedData("runtime", 2);
        var service = service(db, 85001, "10:30:00", false);
        var key = UUID.randomUUID();
        var payload = input(85001, "10:30:00");
        var receipt = service.book(CLINIC, key, payload);
        assertEquals("UNKNOWN", receipt.businessOutcome());
        assertNull(receipt.result());
        assertEquals("STARTED,SUCCESS", outcomes(db, receipt.requestReference()));
        var recovered = service.recover(CLINIC, key, payload);
        assertEquals("SUCCEEDED", recovered.businessOutcome());
        assertEquals(recovered.result(), service.book(CLINIC, key, payload).result());
        assertEquals("REQUEST_PAYLOAD_CONFLICT", service.book(CLINIC, key, payload.deepCopy().put("manualCoordinationBasis","SYNTHETIC")).reason());
        assertEquals("1", count(db, "SELECT count(*) FROM adc.appointment WHERE starts_at='2026-11-08 10:30Z'"));
    }

    @Test void pg06StartCommitUnknownDoesNotReplay() throws Exception {
        var db = new IsolatedData("runtime", 1);
        var service = service(db, 85001, "11:00:00", false);
        var key = UUID.randomUUID();
        var payload = input(85001, "11:00:00");
        var receipt = service.book(CLINIC, key, payload);
        assertEquals("UNKNOWN", receipt.businessOutcome());
        assertEquals("STARTED", outcomes(db, receipt.requestReference()));
        assertEquals("UNKNOWN", service.recover(CLINIC, key, payload).businessOutcome());
        assertEquals("0", count(db, "SELECT count(*) FROM adc.appointment WHERE starts_at='2026-11-08 11:00Z'"));
    }

    @Test void pg02PublisherFirstMakesSameMinutesStaleAfterWait() throws Exception {
        var runtime = new IsolatedData("runtime", 0);
        var service = service(runtime, 85002, "11:05:00", false);
        try (var publisher = new IsolatedData("publisher",0).getConnection();
             var pool = Executors.newSingleThreadExecutor()) {
            publisher.setAutoCommit(false);
            publish(publisher, 85002);
            var booking = pool.submit(() -> service.book(CLINIC, UUID.randomUUID(), input(85002, "11:05:00")));
            awaitDirectoryWait(publisher, "ShareLock");
            publisher.commit();
            assertEquals("STALE_DURATION_BASIS", booking.get(10, TimeUnit.SECONDS).reason());
        }
        assertEquals("0", count(runtime, "SELECT count(*) FROM adc.appointment WHERE starts_at='2026-11-08 11:05Z'"));
    }

    @Test void pg02BookingFirstFreezesOldVersionBeforePublisher() throws Exception {
        var runtime = new IsolatedData("runtime", 0);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var service = service(runtime, 85003, "11:30:00", false, (c, scheme, coordination, source) -> {
            entered.countDown();
            try {
                if (!release.await(8, TimeUnit.SECONDS)) throw new SQLException("test release timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
            return "SYNTHETIC reminder";
        });
        try (var observer = runtime.getConnection(); var pool = Executors.newFixedThreadPool(2)) {
            var booking = pool.submit(() -> service.book(CLINIC, UUID.randomUUID(), input(85003, "11:30:00")));
            try {
                assertTrue(entered.await(6, TimeUnit.SECONDS));
                var publication = pool.submit(() -> {
                    try (var c = new IsolatedData("publisher",0).getConnection()) {
                        c.setAutoCommit(false);
                        publish(c, 85003);
                        c.commit();
                    }
                    return null;
                });
                awaitDirectoryWait(observer, "ExclusiveLock");
                release.countDown();
                var receipt = booking.get(10, TimeUnit.SECONDS);
                assertEquals("SUCCEEDED", receipt.businessOutcome());
                publication.get(10, TimeUnit.SECONDS);
                var detail = new AppointmentReadService(new JdbcTransactions(runtime), auth, CLOCK)
                        .detail(CLINIC, receipt.result().path("appointmentId").asLong()).orElseThrow();
                assertEquals(1, detail.at("/durationBasis/projectVersion").asInt());
            } finally { release.countDown(); }
        }
    }

    private static void awaitDirectoryWait(Connection observer, String mode) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            if ("t".equals(query(observer, "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype='advisory' "
                    + "AND classid=41002 AND objid=1 AND NOT granted AND mode='" + mode + "')"))) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("no waiting directory " + mode);
    }

    private static void publish(Connection c, int project) throws SQLException {
        execute(c, "SELECT pg_advisory_xact_lock(41002,1)");
        execute(c, "UPDATE adc.project_duration_version SET publication_status='SUPERSEDED' WHERE project_id="+project+" AND duration_version_no=1");
        execute(c, "UPDATE adc.project_duration_version SET publication_status='CURRENT',published_at=now(),"
                + "published_by='SYNTHETIC publisher',publication_evidence=jsonb_build_object('schemaVersion',1,"
                + "'requestReference','SYNTHETIC/v2/" + project + "','canonicalPayloadHash',repeat('a',64)) "
                + "WHERE project_id=" + project + " AND duration_version_no=2");
    }

    private FormalBookingService service(IsolatedData db, long project, String start, boolean failAudit) {
        return service(db, project, start, failAudit, (c, scheme, coord, source) -> "SYNTHETIC reminder");
    }

    private FormalBookingService service(IsolatedData db, long project, String start, boolean failAudit,
                                        FormalBookingService.BookingPreparation preparation) {
        var tx = new JdbcTransactions(db);
        var scheme = new InitialBookingReview.AcceptedScheme(88001, new Version(87001, 2), CLINIC, 79001,
                time(start), time(start).plusSeconds(1620), "SYNTHETIC treatment", new Basis(project,1,27));
        var acceptance = new BookingAcceptanceVerifier((c,ref) -> Optional.of(new BookingAcceptanceVerifier.Confirmation(
                "SYNTHETIC-acceptance", STAMP, "SYNTHETIC-patient", null, NOW, null, scheme)),
                new BookingAcceptanceVerifier.ApplicantAuthority() {
                    public boolean isPatient(Connection c,String subject,long id) { return id==88001 && subject.equals("SYNTHETIC-patient"); }
                    public boolean mayAcceptBooking(Connection c,String subject,InitialBookingReview.AcceptedScheme s,String grant) { return false; }
                },CLOCK);
        var review = new InitialBookingReview(tx, auth, id -> new ClinicDirectoryProvider.Clinic(
                id, "SYNTHETIC", ZoneId.of("Etc/UTC"), STAMP), acceptance);
        var events = new AtomicInteger();
        var startId = AUDIT.incrementAndGet();
        return new FormalBookingService(tx,auth,review,(dentist,clinic)->new DentistEligibilityProvider.Eligibility(
                dentist,clinic,"SYNTHETIC qualification",STAMP),c->IDS.incrementAndGet(),
                c->switch(events.getAndIncrement()) {case 0 -> startId; case 1 -> failAudit ? startId : AUDIT.incrementAndGet(); default -> AUDIT.incrementAndGet();},
                preparation,(c,candidate)->false,CLOCK);
    }

    private static ObjectNode input(long project, String start) {
        var n = JSON.createObjectNode().put("patientId","88001").put("acceptanceEvidenceReference","SYNTHETIC-acceptance");
        var s = n.putObject("scheme").put("treatmentRequest","SYNTHETIC treatment")
                .put("startsAt",time(start).toString()).put("endsAt",time(start).plusSeconds(1620).toString());
        s.putObject("effectiveScheduleRef").put("scheduleId","87001").put("versionNo",2);
        s.putObject("durationBasis").put("schemaVersion",1).put("basisMode","INITIAL_CURRENT").put("primaryProjectId",Long.toString(project))
                .put("projectVersion",1).put("adoptedDurationMinutes",27);
        return n;
    }

    private static Instant time(String clock) { return Instant.parse("2026-11-08T"+clock+"Z"); }
    private static String outcomes(IsolatedData db,String ref) throws SQLException {
        try (var c=db.getConnection(); var p=c.prepareStatement(
                "SELECT string_agg(outcome,',' ORDER BY outcome) FROM adc.operation_audit_event WHERE request_reference=?")) {
            p.setString(1,ref);
            try (var r=p.executeQuery()) { assertTrue(r.next()); return r.getString(1); }
        }
    }
    private static String count(IsolatedData db,String sql) throws SQLException {
        try (var c=db.getConnection()) { return query(c,sql); }
    }
    private static String query(Connection c,String sql) throws SQLException {
        try (var p=c.createStatement(); var r=p.executeQuery(sql)) { assertTrue(r.next()); return r.getString(1); }
    }
    private static void execute(Connection c,String sql) throws SQLException {
        try (var p=c.createStatement()) { p.execute(sql); }
    }
    private static String required(String key) {
        return Objects.requireNonNull(System.getenv(key), "Isolated runner required: " + key);
    }

    public static final class IsolatedData extends PGSimpleDataSource {
        private final String role;
        private final int lose;
        private final AtomicInteger commits = new AtomicInteger();
        public IsolatedData(String type, int lose) {
            this.lose=lose;
            role="p0_t028_"+type;
            int port=Integer.parseInt(required("ADC_T028_PORT"));
            if(port<=1024||port==5432||port>65535) throw new IllegalArgumentException("invalid isolated port");
            setServerNames(new String[]{"127.0.0.1"});
            setPortNumbers(new int[]{port});
            setDatabaseName("adc_p0_uc02_validation");
            setUser(role);
            setPassword(required("ADC_T028_"+type.toUpperCase(Locale.ROOT)));
            setSslMode("disable");
            setConnectTimeout(3);
            setSocketTimeout(12);
        }
        @Override public Connection getConnection() throws SQLException {
            var c=super.getConnection();
            try {
                var actual=query(c,"SELECT current_database()||'|'||session_user||'|'||current_user||'|'||host(inet_server_addr())||'|'"
                        + "||inet_server_port()||'|'||current_setting('server_version_num')||'|'||extract(epoch FROM pg_postmaster_start_time())::text");
                if (!actual.equals("adc_p0_uc02_validation|"+role+"|"+role+"|127.0.0.1|"+getPortNumbers()[0]
                        +"|180006|"+required("ADC_T028_START"))) throw new SQLException("isolated target mismatch: " + actual);
                if (lose==0) return c;
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},
                        (proxy,method,args)->{
                            try {
                                var result=method.invoke(c,args);
                                if (method.getName().equals("commit") && commits.incrementAndGet()==lose)
                                    throw new SQLException("SYNTHETIC lost acknowledgment after real COMMIT","08006");
                                return result;
                            } catch (InvocationTargetException e) { throw e.getCause(); }
                        });
            } catch (SQLException | RuntimeException e) {
                c.close();
                throw e;
            }
        }
    }
}
