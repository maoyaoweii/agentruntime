package com.agentruntime.debug;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class LocalFixtureAccessTest {
    private static void waitIgnoringInterrupts(CountDownLatch latch) {
        boolean done = false;
        while (!done) {
            try { done = latch.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { /* Simulate platform IPC ignoring cancellation. */ }
        }
    }
    private static void await(CountDownLatch latch) throws Exception {
        assertTrue("worker not scheduled", latch.await(3, TimeUnit.SECONDS));
    }
    private static String afterWorkerExits(LocalFixtureAccess gate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            try { return gate.run(1000, lease -> "recovered"); }
            catch (SecurityException e) {
                if (!"FIXTURE_ACCESS_BUSY".equals(e.getMessage()) || System.nanoTime() >= deadline) throw e;
                Thread.sleep(5);
            }
        }
    }

    @Test public void fastOperationReturnsOnDedicatedThread() throws Exception {
        LocalFixtureAccess gate = new LocalFixtureAccess();
        Thread caller = Thread.currentThread();
        String value = gate.run(1000, lease -> {
            assertNotSame(caller, Thread.currentThread());
            lease.stage("TREE");
            return "actual-accessibility-result";
        });
        assertEquals("actual-accessibility-result", value);
        assertEquals("recovered", afterWorkerExits(gate));
    }

    @Test public void stalledReadIsBoundedAndCannotBlockCloseOrDispatchAfterTimeout() throws Exception {
        LocalFixtureAccess gate = new LocalFixtureAccess();
        LocalFixturePolicy policy = new LocalFixturePolicy();
        String session = policy.open(0);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean clicked = new AtomicBoolean();
        long start = System.nanoTime();
        try {
            try {
                gate.run(200, lease -> {
                    try {
                        lease.stage("ROOT");
                        waitIgnoringInterrupts(release);
                        lease.requireActive();
                        clicked.set(true);
                        return "must-not-return";
                    } finally { finished.countDown(); }
                });
                fail("blocked read must time out");
            } catch (SecurityException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith("FIXTURE_ACCESS_TIMEOUT:"));
            }
            assertTrue("caller waited for stuck IPC", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
            // Lifecycle state is not protected by the query's lock, so revoke can proceed immediately.
            policy.close();
            try { policy.require(session, 1); fail("closed session must be invalid"); }
            catch (SecurityException expected) { }
            try { gate.run(1000, lease -> "second"); fail("do not spawn another worker over stuck IPC"); }
            catch (SecurityException expected) { assertEquals("FIXTURE_ACCESS_BUSY", expected.getMessage()); }
        } finally { release.countDown(); }
        await(finished);
        assertFalse("timeout must not permit a later click", clicked.get());
        assertEquals("recovered", afterWorkerExits(gate));
    }

    @Test public void simultaneousAccessIsRejectedWithoutQueueing() throws Exception {
        LocalFixtureAccess gate = new LocalFixtureAccess();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try { gate.run(3000, lease -> { started.countDown(); release.await(); return true; }); }
            catch (Throwable e) { error.set(e); }
        });
        first.start();
        try {
            await(started);
            try { gate.run(1000, lease -> true); fail("must reject simultaneous query"); }
            catch (SecurityException expected) { assertEquals("FIXTURE_ACCESS_BUSY", expected.getMessage()); }
        } finally { release.countDown(); first.join(3000); }
        assertFalse(first.isAlive());
        assertNull(error.get());
    }

    @Test public void securityFailureIsPreservedAndSlotIsReleased() throws Exception {
        LocalFixtureAccess gate = new LocalFixtureAccess();
        try { gate.run(1000, lease -> { throw new SecurityException("WRONG_ACTIVE_PACKAGE"); }); fail(); }
        catch (SecurityException expected) { assertEquals("WRONG_ACTIVE_PACKAGE", expected.getMessage()); }
        assertEquals("recovered", afterWorkerExits(gate));
    }

    @Test public void interruptedCallerCancelsLeaseAndRestoresInterruptFlag() throws Exception {
        LocalFixtureAccess gate = new LocalFixtureAccess();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicReference<String> reason = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean(), lateAction = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                gate.run(5000, lease -> {
                    try {
                        started.countDown(); waitIgnoringInterrupts(release);
                        lease.requireActive(); lateAction.set(true); return true;
                    } finally { finished.countDown(); }
                });
            } catch (SecurityException e) {
                reason.set(e.getMessage()); interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        try {
            await(started); caller.interrupt(); caller.join(2000);
            assertFalse(caller.isAlive());
            assertEquals("FIXTURE_ACCESS_INTERRUPTED", reason.get());
            assertTrue(interruptRestored.get());
        } finally { release.countDown(); }
        await(finished);
        assertFalse(lateAction.get());
        assertEquals("recovered", afterWorkerExits(gate));
    }

    @Test public void cancelledLeaseRejectsEverySubsequentStage() {
        LocalFixtureAccess.Lease lease = new LocalFixtureAccess.Lease(1000);
        lease.cancel();
        try { lease.stage("CLICK_DISPATCH"); fail("cancelled query must not advance to click"); }
        catch (SecurityException expected) { assertEquals("FIXTURE_ACCESS_TIMEOUT:START", expected.getMessage()); }
    }
    @Test public void cancellationBeforeCommitForbidsClickCommit() {
        LocalFixtureAccess.Lease lease = new LocalFixtureAccess.Lease(1000);
        lease.cancel();
        try { lease.commitClick(); fail("cancel must win over a later commit"); }
        catch (SecurityException expected) { assertFalse(expected.getMessage().contains("CLICK_COMMITTED")); }
    }
    @Test public void alreadyCommittedClickIsNeverReportedAsSafeToRetry() {
        LocalFixtureAccess.Lease lease = new LocalFixtureAccess.Lease(1000);
        lease.commitClick();
        try { lease.commitClick(); fail("only one commit"); }
        catch (SecurityException expected) { assertEquals("FIXTURE_CLICK_ALREADY_COMMITTED", expected.getMessage()); }
        lease.cancel();
        try { lease.requireActive(); fail("must reject further work"); }
        catch (SecurityException expected) { assertTrue(expected.getMessage().endsWith(":CLICK_COMMITTED_NO_RETRY")); }
    }
}
