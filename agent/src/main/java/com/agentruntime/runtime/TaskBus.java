package com.agentruntime.runtime;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务状态总线：把运行时发生的事，转成 UI 与悬浮窗都能订阅的日志流。
 *
 * <p>为什么要这一层：V1.0 的界面只能在任务结束后一次性打印结果，用户在长达几十秒的执行期间
 * 完全看不到进展——这正是"用起来烧心"的根源之一。有了事件流，界面才能显示「第 2/4 步 ·
 * 正在点击 search_input」这种实时反馈。
 *
 * <p>线程模型：发布方可以是任意线程（任务跑在后台），分发统一 post 到主线程，
 * 订阅方不用自己处理线程切换。
 */
public final class TaskBus {

    /** 日志级别，决定 UI 着色。 */
    public enum Level { INFO, OK, WARN, ERROR }

    /** 一条可显示的事件。 */
    public static final class Event {
        public final long timestamp;
        public final Level level;
        public final String tag;
        public final String message;

        Event(Level level, String tag, String message) {
            this.timestamp = System.currentTimeMillis();
            this.level = level;
            this.tag = tag;
            this.message = message;
        }

        /** 控制台单行格式，时间只保留 分:秒。 */
        public String toLine() {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(timestamp);
            String hh = pad(c.get(java.util.Calendar.HOUR_OF_DAY));
            String mm = pad(c.get(java.util.Calendar.MINUTE));
            String ss = pad(c.get(java.util.Calendar.SECOND));
            return hh + ":" + mm + ":" + ss + "  " + message;
        }

        private static String pad(int v) {
            return v < 10 ? "0" + v : String.valueOf(v);
        }
    }

    /** 订阅者回调（保证在主线程）。 */
    public interface Listener {
        void onEvent(Event event);
    }

    /** 任务整体状态。 */
    public enum State { IDLE, RUNNING, SUCCESS, FAILED, CANCELLED }

    private static final int MAX_RECENT = 300;
    private static final TaskBus INSTANCE = new TaskBus();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LinkedList<Event> recent = new LinkedList<>();

    private volatile State state = State.IDLE;
    private volatile String currentTaskId = "";
    private volatile int stepIndex = 0;
    private volatile int stepTotal = 0;
    private volatile String currentStep = "";

    private TaskBus() {
    }

    public static TaskBus get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------ 发布

    public void log(Level level, String tag, String message) {
        Event e = new Event(level, tag, message == null ? "" : message);
        synchronized (recent) {
            recent.addLast(e);
            while (recent.size() > MAX_RECENT) recent.removeFirst();
        }
        for (Listener l : listeners) {
            main.post(() -> l.onEvent(e));
        }
    }

    public void info(String tag, String message) {
        log(Level.INFO, tag, message);
    }

    public void ok(String tag, String message) {
        log(Level.OK, tag, message);
    }

    public void warn(String tag, String message) {
        log(Level.WARN, tag, message);
    }

    public void error(String tag, String message) {
        log(Level.ERROR, tag, message);
    }

    // ------------------------------------------------------------ 订阅

    public void subscribe(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void unsubscribe(Listener l) {
        listeners.remove(l);
    }

    /** 最近日志快照（新订阅者用于回填历史）。 */
    public List<Event> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    public void clearLog() {
        synchronized (recent) {
            recent.clear();
        }
    }

    // ------------------------------------------------------------ 状态

    public State state() {
        return state;
    }

    public String currentTaskId() {
        return currentTaskId;
    }

    public int stepIndex() {
        return stepIndex;
    }

    public int stepTotal() {
        return stepTotal;
    }

    public String currentStep() {
        return currentStep;
    }

    public boolean running() {
        return state == State.RUNNING;
    }

    /** 进度描述，给悬浮窗与通知用。 */
    public String progressText() {
        if (state == State.RUNNING) {
            return "执行中 " + stepIndex + "/" + stepTotal
                    + (currentStep.isEmpty() ? "" : " · " + currentStep);
        }
        if (state == State.IDLE) return "空闲";
        return state == State.CANCELLED ? "已取消" : state.name();
    }

    // ------------------------------------------------------ 运行时适配

    /**
     * 把运行时事件转成日志。
     *
     * <p>返回的实例是无状态的，可安全复用于多次任务。
     */
    public AgentRuntime.Listener runtimeListener() {
        return new AgentRuntime.Listener() {
            @Override
            public void onTaskStart(com.agentruntime.dsl.TaskSpec task) {
                state = State.RUNNING;
                currentTaskId = task.taskId;
                stepTotal = task.steps.size();
                stepIndex = 0;
                currentStep = "";
                clearLog();
                ok("task", "开始任务 " + task.taskId + "（" + task.steps.size() + " 步）");
                if (!task.goal.isEmpty()) info("task", "目标已载入");
            }

            @Override
            public void onStepStart(int index, int total, com.agentruntime.dsl.TaskSpec.TaskStep step) {
                stepIndex = index;
                stepTotal = total;
                currentStep = step.skillId;
                info("step", "[" + index + "/" + total + "] " + step.skillId);
            }

            @Override
            public void onStepEnd(int index, com.agentruntime.dsl.TaskSpec.TaskStep step,
                                  boolean ok, String code, long durationMs) {
                String outcome = "RISK_REJECTED".equals(code)
                        ? "确认被拒，操作未执行（RISK_REJECTED）" : code;
                String line = "     " + (ok ? "✓ " : "✗ ") + outcome + "  " + durationMs + "ms";
                if (ok) {
                    TaskBus.this.ok("step", line);
                } else {
                    error("step", line);
                }
            }

            @Override
            public void onReplan(String reason) {
                warn("task", "触发重规划：" + reason);
            }

            @Override
            public void onFinish(AgentRuntime.TaskResult result) {
                if ("SUCCESS".equals(result.status)) state = State.SUCCESS;
                else if ("CANCELLED".equals(result.status)) state = State.CANCELLED;
                else state = State.FAILED;
                currentStep = "";
                if (state == State.SUCCESS) {
                    ok("task", "任务完成：" + result.status);
                } else if (state == State.CANCELLED) {
                    warn("task", "任务已取消");
                } else {
                    error("task", "任务结束：" + result.status);
                }
            }
        };
    }
}
