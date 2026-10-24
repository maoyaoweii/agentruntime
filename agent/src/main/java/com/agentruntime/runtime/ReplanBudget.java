package com.agentruntime.runtime;

/**
 * Task-scoped allowance for failure recovery retries.
 * Package-private so it cannot become an app-facing control surface.
 */
final class ReplanBudget {
    private int remaining;

    ReplanBudget(int allowance) {
        remaining = Math.max(0, allowance);
    }

    boolean tryConsume() {
        if (remaining <= 0) return false;
        remaining--;
        return true;
    }
}
