package com.agentruntime.dsh.runtime;

import android.os.Handler;
import android.os.Looper;
import com.agentruntime.AgentApp;
import com.agentruntime.dsh.model.Cancellation;
import com.agentruntime.dsh.model.ProviderConfig;
import com.agentruntime.dsh.plugin.PluginRegistry;
import com.agentruntime.dsh.store.SessionStore;
import com.agentruntime.ui.TaskRunner;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Process-owned state outlives Activity recreation. The foreground service owns the worker lifetime. */
public final class DshController {
    public static final class View {
        public final String id,session,status,live;
        View(Job job) { id=job.id; session=job.session; status=job.status; live=job.live.toString(); }
    }
    private static final class Job {
        final String id=UUID.randomUUID().toString(),session,text,turn;
        final boolean tools;
        final ProviderConfig.Snapshot provider;
        final PluginRegistry.Snapshot plugins;
        final Cancellation cancellation=new Cancellation(180000);
        final StringBuilder live=new StringBuilder();
        String status="准备开始…"; boolean started;
        Job(String session,String text,boolean tools,ProviderConfig.Snapshot provider,PluginRegistry.Snapshot plugins,String turn) {
            this.session=session; this.text=text; this.tools=tools; this.provider=provider; this.plugins=plugins; this.turn=turn;
        }
    }
    private final AgentApp app;
    private final SessionStore store;
    private final ProviderConfig provider;
    private final PluginRegistry plugins;
    private final DshRuntime runtime;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Runnable> listeners=new CopyOnWriteArraySet<>();
    private final java.util.concurrent.ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"dsh-deadline"); t.setDaemon(true); return t;});
    private Job active;
    private boolean updateQueued;
    private String lastError="";

    public DshController(AgentApp app,SessionStore store,ProviderConfig provider,PluginRegistry plugins,DshRuntime runtime) {
        this.app=app; this.store=store; this.provider=provider; this.plugins=plugins; this.runtime=runtime;
    }
    public synchronized View active() { return active==null?null:new View(active); }
    public synchronized String lastError() { return lastError; }
    public void observe(Runnable listener) { listeners.add(listener); }
    public void remove(Runnable listener) { listeners.remove(listener); }

    public synchronized String reserve(String session,String text,boolean tools) throws Exception {
        if(active!=null) throw new IllegalStateException("请先停止或等待当前回复完成");
        if(store.session(session)==null) throw new IllegalArgumentException("对话不存在");
        if(text==null||text.trim().isEmpty()||text.length()>SessionStore.MAX_INPUT) throw new IllegalArgumentException("消息需要 1–16000 个字符");
        ProviderConfig.Snapshot settings=provider.snapshot(); PluginRegistry.Snapshot admitted=plugins.snapshot(tools);
        // Persist the user's message before the UI clears its draft or asks Android to start a service.
        String turn=store.beginTurn(session,text.trim(),tools?"tools":"chat",settings.model);
        active=new Job(session,text.trim(),tools,settings,admitted,turn); lastError="";
        changed(); return active.id;
    }
    public synchronized void abandon(String id) {
        if(active!=null&&active.id.equals(id)&&!active.started) {
            try { store.finish(active.session,active.turn,"failed","SERVICE_START_FAILED"); } catch(Exception ignored) { /* Recovery closes an unsettled turn on the next process start. */ }
            active=null; lastError="无法启动后台回复，消息已保存在会话中"; changed();
        }
    }
    public boolean launch(String id,Runnable finished) {
        final Job job;
        synchronized(this) {
            if(active==null||!active.id.equals(id)||active.started) return false;
            job=active; job.started=true;
        }
        ScheduledFuture<?> deadline=timer.schedule(()->cancel(job.id,"TURN_TIMEOUT"),180,TimeUnit.SECONDS);
        Thread worker=new Thread(()->{
            try {
                runtime.runAdmitted(job.session,job.turn,job.text,job.provider,job.plugins,job.cancellation,new DshRuntime.Listener() {
                    @Override public void status(String text) { synchronized(DshController.this) { job.status=text; changed(); } }
                    @Override public void delta(String text) { synchronized(DshController.this) { if(job.live.length()<65536) job.live.append(text); changed(); } }
                    @Override public void settled() { synchronized(DshController.this) { job.live.setLength(0); changed(); } }
                });
            } catch(Exception e) {
                synchronized(DshController.this) { lastError=job.cancellation.flag.get()?"操作已停止":DshRuntime.errorCode(e); }
            } finally {
                deadline.cancel(false); Thread.interrupted();
                // Release admission together with service cleanup on the UI thread. A new start must
                // never arrive between clearing active and the previous service's completion callback.
                main.post(()->{
                    synchronized(DshController.this) { if(active==job) active=null; changed(); }
                    finished.run();
                });
            }
        },"dsh-turn"); worker.start(); return true;
    }
    public void cancel(String id,String reason) {
        Job job;
        synchronized(this) {
            if(active==null||!active.id.equals(id)) return;
            job=active; job.status="正在停止…"; job.cancellation.cancel(reason); changed();
        }
        TaskRunner.cancel(job.cancellation.flag);
        app.confirmations().cancelPending(job.cancellation.flag);
    }
    private synchronized void changed() {
        if(updateQueued) return; updateQueued=true;
        main.postDelayed(()->{
            synchronized(DshController.this) { updateQueued=false; }
            for(Runnable listener:listeners) { try { listener.run(); } catch(RuntimeException ignored) { /* One detached view must not stop a turn. */ } }
        },80);
    }
}
