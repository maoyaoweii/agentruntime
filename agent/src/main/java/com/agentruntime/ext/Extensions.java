package com.agentruntime.ext;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 扩展：只有目标明确用到时才接，没接上就直说缺什么。
 *
 * <p>主循环（打开应用、点、输入、核对）不依赖这里的任何一项；
 * 关掉全部扩展，主循环仍然能跑完。这是刻意的边界，避免把核心能力做成可选插件。
 */
public final class Extensions {
    public enum Kind { BROWSER, SANDBOX, REMOTE, SCHEDULE, VOICE }

    private final Map<Kind, Extension> registry = new LinkedHashMap<>();

    public Extensions(Context context) {
        registry.put(Kind.BROWSER, new Browser(context));
        registry.put(Kind.SANDBOX, new Sandbox());
        registry.put(Kind.REMOTE, new Remote());
        registry.put(Kind.SCHEDULE, new Schedule());
        registry.put(Kind.VOICE, new Voice());
    }

    /** 全部关闭：用于验证"没有扩展也能跑完主循环"。 */
    public static Extensions disabled() {
        return new Extensions(null);
    }

    public boolean available(Kind kind) {
        Extension extension = registry.get(kind);
        return extension != null && extension.available();
    }

    /** 缺什么就写什么，不要假装有。 */
    public String missing(Kind kind) {
        Extension extension = registry.get(kind);
        if (extension == null) return "未注册扩展：" + kind.name().toLowerCase();
        return extension.available() ? "" : extension.reason();
    }

    public interface Extension {
        boolean available();

        String reason();
    }

    /** 结果在网页里才开。 */
    public static final class Browser implements Extension {
        private final boolean available;

        Browser(Context context) {
            available = context != null;
        }

        public boolean available() { return available; }

        public String reason() { return "未接浏览器扩展：需要读取网页内容时请明确说明，主循环不会假装看过网页。"; }
    }

    /** 用户给文件、要跑 Python / Node / shell 时才用；没有隔离环境就不执行。 */
    public static final class Sandbox implements Extension {
        public boolean available() { return false; }

        public String reason() { return "没有隔离沙箱：不会在主进程里直接 exec，也不会假装跑过脚本。"; }
    }

    /** CLI / Skill / MCP 只是把同一条 GoalCard 交进来。 */
    public static final class Remote implements Extension {
        public boolean available() { return false; }

        public String reason() { return "未接远程入口：跨设备任务请在本机完成，或明确说明需要远程扩展。"; }
    }

    /** 到点或每次触发时起一条新的 GoalCard。 */
    public static final class Schedule implements Extension {
        public boolean available() { return false; }

        public String reason() { return "未接定时触发：只能现在做，不能承诺到点再执行。"; }
    }

    /** 只是输入方式，不改变主循环。 */
    public static final class Voice implements Extension {
        public boolean available() { return false; }

        public String reason() { return "未接语音输入：请用文字描述目标。"; }
    }
}
