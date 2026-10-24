package com.agentruntime.debug;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Debug-only, one in-flight fixture query; no lifecycle lock is held across Android IPC. */
final class LocalFixtureAccess {
    private final AtomicBoolean busy = new AtomicBoolean();
    interface Work<T> { T run(Lease lease) throws Exception; }

    static final class Lease {
        private final long deadline;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile String stage = "START";
        private volatile boolean clickCommitted;
        Lease(long timeoutMs) {
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        }
        void requireActive() {
            if (cancelled.get() || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted())
                throw new SecurityException(timeoutReason());
        }
        void stage(String value) { requireActive(); stage = value; }
        synchronized void commitClick() {
            requireActive();
            if (clickCommitted) throw new SecurityException("FIXTURE_CLICK_ALREADY_COMMITTED");
            clickCommitted = true;
        }
        synchronized void cancel() { cancelled.set(true); }
        String timeoutReason() {
            return "FIXTURE_ACCESS_TIMEOUT:" + stage + (clickCommitted ? ":CLICK_COMMITTED_NO_RETRY" : "");
        }
    }

    <T> T run(long timeoutMs, Work<T> operation) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs");
        if (!busy.compareAndSet(false, true)) throw new SecurityException("FIXTURE_ACCESS_BUSY");
        Lease lease = new Lease(timeoutMs);
        FutureTask<T> task = new FutureTask<>(() -> {
            lease.requireActive();
            T value = operation.run(lease);
            lease.requireActive();
            return value;
        });
        // A stuck platform IPC may ignore interrupts. Keep its slot occupied until it really exits.
        // This caps workers at one rather than creating an unbounded thread on every timeout.
        Thread worker = new Thread(() -> {
            try { task.run(); }
            finally { busy.set(false); }
        }, "CueFixedFixtureAccess");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException | Error e) {
            busy.set(false);
            throw e;
        }
        try {
            long remaining = lease.deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return task.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            lease.cancel();
            task.cancel(true);
            throw new SecurityException(lease.timeoutReason());
        } catch (InterruptedException e) {
            lease.cancel();
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new SecurityException("FIXTURE_ACCESS_INTERRUPTED");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException) throw (SecurityException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("FIXTURE_ACCESS_FAILED", cause);
        }
    }
}
