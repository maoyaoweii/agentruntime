package com.agentruntime.executor;

import com.agentruntime.dsl.ActionSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Task-scoped package policy for app launch actions. */
public final class AppLaunchPolicy {
    private AppLaunchPolicy() {
    }

    public static boolean allows(List<String> allowedApps, String packageName) {
        if (packageName == null || packageName.trim().isEmpty() || allowedApps == null) {
            return false;
        }
        return allowedApps.contains(packageName);
    }

    /**
     * 任务收尾核验：允许的应用集合非空时，任务结束时实际停留的应用必须落在集合内。
     *
     * <p>允许集合为空表示任务不涉及外部应用（纯 Agent 内部动作），此时不做要求。
     * 之所以单独成一个纯函数：它是"跑错了却报成功"的唯一兜底，必须能脱离 Android 环境被单测覆盖。
     */
    public static boolean targetReached(List<String> allowedApps, String foregroundPackage) {
        if (allowedApps == null || allowedApps.isEmpty()) return true;
        return foregroundPackage != null && allowedApps.contains(foregroundPackage);
    }

    /** Collect only explicit launch_app targets from a user-selected skill's resolved actions. */
    public static List<String> declaredLaunchTargets(List<ActionSpec> actions) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        if (actions != null) {
            for (ActionSpec action : actions) {
                if (action == null || action.type != ActionSpec.ActionType.LAUNCH_APP) continue;
                String packageName = action.param("package");
                if (packageName != null && !packageName.trim().isEmpty()) targets.add(packageName);
            }
        }
        return new ArrayList<>(targets);
    }
}
