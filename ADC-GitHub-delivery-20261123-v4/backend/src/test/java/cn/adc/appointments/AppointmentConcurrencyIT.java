package cn.adc.appointments;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.adc.appointments.AppointmentFlowIT.CLINIC;
import static cn.adc.appointments.AppointmentFlowIT.count;
import static cn.adc.appointments.AppointmentFlowIT.create;
import static cn.adc.appointments.T028IsolatedPgIT.IsolatedData;
import static org.junit.jupiter.api.Assertions.*;

class AppointmentConcurrencyIT {
    @Test void concurrentBookingsSerializeEvenWhenNoExistingAppointmentRowCanBeLocked() throws Exception {
        var db = new IsolatedData("runtime",0);
        var fixture = new AppointmentFlowIT();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = fixture.booking(db,"11:30:00",true,(c,o)->false,(c,s,coord,ref)->{
            entered.countDown();
            try {
                if (!release.await(5,TimeUnit.SECONDS)) throw new SQLException("test barrier timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
            return "SYNTHETIC reminder";
        });
        var other = fixture.booking(db,"11:30:00",true,(c,o)->false,(c,s,coord,ref)->"SYNTHETIC reminder");
        try (var observer=db.getConnection(); var pool=Executors.newFixedThreadPool(2)) {
            String slot = "starts_at='2026-11-08T11:30:00Z'";
            assertEquals(0,count(observer,"SELECT count(*) FROM adc.appointment WHERE "+slot));
            var winner = pool.submit(() -> first.book(CLINIC,UUID.randomUUID(),create("11:30:00")));
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                var contender = pool.submit(() -> other.book(CLINIC,UUID.randomUUID(),create("11:30:00")));
                waitForResourceLock(observer);
                release.countDown();
                assertEquals("SUCCEEDED",winner.get(8,TimeUnit.SECONDS).businessOutcome());
                assertEquals("APPOINTMENT_CAPACITY_CONFLICT",contender.get(8,TimeUnit.SECONDS).reason());
                assertEquals(1,count(observer,"SELECT count(*) FROM adc.appointment WHERE "+slot));
            } finally { release.countDown(); }
        }
    }

    private static void waitForResourceLock(Connection c) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1600);
        do {
            if (count(c,"SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted AND mode='ExclusiveLock'") > 0) return;
            Thread.sleep(20);
        } while (System.nanoTime()<deadline);
        fail("Expected a competing appointment to wait");
    }
}
