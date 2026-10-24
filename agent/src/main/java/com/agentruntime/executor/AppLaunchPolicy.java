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
