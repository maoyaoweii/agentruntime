package com.agentruntime.dsh.runtime;

import android.os.Handler;
import android.os.Looper;
import com.agentruntime.AgentApp;
import com.agentruntime.dsh.model.Cancellation;
import com.agentruntime.dsh.model.ProviderConfig;
import com.agentruntime.dsh.plugin.PluginRegistry;
import com.agentruntime.dsh.store.SessionStore;
import com.agentruntime.ui.TaskRunner;
import org.json.JSONObject;
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
    /** 一轮工具预算用完时自动续跑的最大轮数；用户主动停止永远优先。 */
    public static final int MAX_ROUNDS=6;
    /** 空闲多久算卡死。工具在跑时不计时：技能内部超时本身可以到 300 秒。 */
    static final long IDLE_LIMIT_MS=180000;

    private static final class Job {
        final String id=UUID.randomUUID().toString(),session,text,turn,goal,rootId;
        final boolean tools;
        final int round;
        final ProviderConfig.Snapshot provider;
        final PluginRegistry.Snapshot plugins;
        final Cancellation cancellation=new Cancellation(300000);
        final StringBuilder live=new StringBuilder();
        String status="准备开始…"; boolean started,stopRequested;
        volatile boolean inTool;
        volatile long lastActivity=System.currentTimeMillis();
        Job(String session,String text,boolean tools,ProviderConfig.Snapshot provider,PluginRegistry.Snapshot plugins,String turn,int round,String goal,String rootId) {
            this.session=session; this.text=text; this.tools=tools; this.provider=provider; this.plugins=plugins; this.turn=turn;
            this.round=round; this.goal=goal; this.rootId=rootId==null?id:rootId;
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
    private Job lastJob;
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
        active=new Job(session,text.trim(),tools,settings,admitted,turn,0,text.trim(),null); lastJob=active; lastError="";
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
        // 墙钟只用来兜"空闲无进展"，不是整单死刑：工具在执行时一律不计时。
        ScheduledFuture<?> deadline=timer.scheduleWithFixedDelay(()->{
            if(job.inTool) return;
            if(System.currentTimeMillis()-job.lastActivity<IDLE_LIMIT_MS) return;
            cancel(job.id,"TURN_TIMEOUT");
        },15,15,TimeUnit.SECONDS);
        Thread worker=new Thread(()->{
            try {
                runtime.runAdmitted(job.session,job.turn,job.text,job.provider,job.plugins,job.cancellation,new DshRuntime.Listener() {
                    @Override public void status(String text) { job.lastActivity=System.currentTimeMillis(); synchronized(DshController.this) { job.status=text; changed(); } }
                    @Override public void delta(String text) { job.lastActivity=System.currentTimeMillis(); synchronized(DshController.this) { if(job.live.length()<65536) job.live.append(text); changed(); } }
                    @Override public void settled() { job.lastActivity=System.currentTimeMillis(); synchronized(DshController.this) { job.live.setLength(0); changed(); } }
                    @Override public void tool(String name,boolean running) {
                        job.inTool=running; job.lastActivity=System.currentTimeMillis();
                    }
                },job.tools);
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
    /**
     * 上一轮工具预算用完时，自动开下一轮接着做。
     *
     * <p>参考 DSH 的做法：长任务不该被"单轮预算"判死刑。这里只在
     * 预算是唯一阻碍（MODEL_STEP_LIMIT / TOOL_LIMIT）、不是用户主动停止、
     * 也没超过续跑轮数时续一轮；续跑指令以 [DSH Runtime] 开头，聊天里不显示成用户消息。
     */
    public synchronized String continuationFor(String finishedId) {
        Job prev=lastJob;
        if(prev==null||!prev.id.equals(finishedId)) return null;
        if(prev.stopRequested) return null;
        if(prev.round>=MAX_ROUNDS) return null;
        // 用户主动停止（或插件被停用、服务被杀）一律不再续；只有"预算用尽"才续。
        boolean timeout="TURN_TIMEOUT".equals(prev.cancellation.reason());
        if(prev.cancellation.flag.get()&&!timeout) return null;
        String state=store.turnState(prev.turn);
        String code=store.turnCode(prev.turn);
        boolean budget=("failed".equals(state)&&("MODEL_STEP_LIMIT".equals(code)||"TOOL_LIMIT".equals(code)))
                ||("cancelled".equals(state)&&"TURN_TIMEOUT".equals(code));
        if(!budget||store.hasUnsafeToolOutcome(prev.session,prev.turn)) return null;
        try {
            ProviderConfig.Snapshot settings=provider.snapshot();
            PluginRegistry.Snapshot admitted=plugins.snapshot(prev.tools);
            String prefix="[DSH Runtime] 上一轮因为预算中断，目标还没完成。这是续跑，不是新任务。\n目标：";
            String suffix="\n以工具结果为准。history_compacted 的正文可能被截断，不要凭印象补全。\n"
                    +"先 sys.read_screen 一次。\n"
                    +"不要重放已成功或 outcome_uncertain 或 RESULT_UNAVAILABLE 的动作。\n"
                    +"这次回复仍然只调用一个依赖屏幕的工具。\n"
                    +"两条不同路径都失败后就停止，说明缺什么。\n"
                    +"读屏证据对上目标再结束；对不上就说明卡在哪一屏。";
            String goal=prev.goal,marker="…（完整目标见上一轮消息）";
            int maxGoal=SessionStore.MAX_INPUT-prefix.length()-suffix.length();
            if(goal.length()>maxGoal) {
                int end=maxGoal-marker.length();
                if(end>0&&Character.isHighSurrogate(goal.charAt(end-1))) end--;
                goal=goal.substring(0,Math.max(0,end))+marker;
            }
            String text=prefix+goal+suffix;
            String turn=store.beginTurn(prev.session,text,prev.tools?"tools":"chat",settings.model);
            Job next=new Job(prev.session,text,prev.tools,settings,admitted,turn,prev.round+1,prev.goal,prev.rootId);
            active=next; lastJob=next;
            store.markContinued(prev.session,prev.turn,next.id);
            changed();
            return next.id;
        } catch(Exception e) {
            lastError="无法自动继续上一轮任务，请检查模型设置和会话后重试。"; changed();
            return null;
        }
    }

    public void cancel(String id,String reason) {
        Job job;
        synchronized(this) {
            job=active!=null?active:lastJob;
            if(job==null||(!job.id.equals(id)&&("TURN_TIMEOUT".equals(reason)||!job.rootId.equals(id)))) return;
            if(!"TURN_TIMEOUT".equals(reason)) job.stopRequested=true;
            job.status="正在停止…"; job.cancellation.cancel(reason); changed();
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
