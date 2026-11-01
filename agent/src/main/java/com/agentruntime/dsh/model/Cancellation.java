package com.agentruntime.dsh.model;

import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class Cancellation {
    public final AtomicBoolean flag=new AtomicBoolean();
    private final AtomicReference<HttpURLConnection> connection=new AtomicReference<>();
    private final long deadline;
    private volatile String reason="CANCELLED";
    private volatile Thread worker;
    public Cancellation(long timeoutMs) { deadline=System.nanoTime()+timeoutMs*1000000L; }
    public void bind() throws InterruptedIOException { worker=Thread.currentThread(); check(); }
    public void attach(HttpURLConnection value) throws InterruptedIOException { connection.set(value); check(); }
    public void detach(HttpURLConnection value) { connection.compareAndSet(value,null); }
    public String reason() { return reason; }
    public synchronized void cancel(String code) {
        if(flag.get()) {
            // A user stop or revocation must win even if the deadline fired just before it.
            if("TURN_TIMEOUT".equals(reason)&&!"TURN_TIMEOUT".equals(code)) reason=code;
            return;
        }
        reason=code; flag.set(true);
        Thread thread=worker; if(thread!=null) thread.interrupt();
        HttpURLConnection current=connection.get();
        if(current!=null) { Thread close=new Thread(current::disconnect,"dsh-cancel-http"); close.setDaemon(true); close.start(); }
    }
    public void check() throws InterruptedIOException {
        if(System.nanoTime()>=deadline) cancel("TURN_TIMEOUT");
        if(flag.get()||Thread.currentThread().isInterrupted()) throw new InterruptedIOException(reason);
    }
}
