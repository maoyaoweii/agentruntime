package com.agentruntime.dsh.runtime;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 单轮预算。
 *
 * <p>默认 16 次模型往返 / 24 次工具调用：一条真实链路（打开微信 → 读屏 → 搜索 → 输入 →
 * 读屏 → 点会话 → 输入 → 发送 → 复核）本身就要 8~10 次，留一倍余量给失败换路径。
 * 存量机型或调试时可以调小，设置写在同一份 SharedPreferences 里，不引入新配置面。
 */
public final class TurnLimits {
    private static final String PREFS = "dsh_turn_limits";
    private static final String KEY_STEPS = "max_steps";
    private static final String KEY_TOOLS = "max_tools";
    public static final int DEFAULT_STEPS = 16;
    public static final int DEFAULT_TOOLS = 24;

    private static volatile int steps = DEFAULT_STEPS;
    private static volatile int tools = DEFAULT_TOOLS;

    private TurnLimits() { }

    public static int steps() { return steps; }

    public static int tools() { return tools; }

    /** 进程启动时调用一次；读取失败保持默认值。 */
    public static void load(Context context) {
        if (context == null) return;
        try {
            SharedPreferences preferences = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            steps = clamp(preferences.getInt(KEY_STEPS, DEFAULT_STEPS), 4, 40);
            tools = clamp(preferences.getInt(KEY_TOOLS, DEFAULT_TOOLS), 4, 60);
        } catch (RuntimeException ignored) {
            steps = DEFAULT_STEPS;
            tools = DEFAULT_TOOLS;
        }
    }

    /** 仅测试使用：复位到默认值。 */
    public static void resetForTest(int stepLimit, int toolLimit) {
        steps = clamp(stepLimit, 1, 40);
        tools = clamp(toolLimit, 1, 60);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
