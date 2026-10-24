package com.agentruntime.runtime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ReplanBudgetTest {

    @Test
    public void failedStepsShareOneTaskWideRetryAllowance() {
        ReplanBudget budget = new ReplanBudget(2);
        int retriesGranted = 0;

        // Simulate repeated failed steps in one long task; only the shared two retries are granted.
        for (int failedStep = 0; failedStep < 5; failedStep++) {
            if (budget.tryConsume()) retriesGranted++;
        }

        assertEquals(2, retriesGranted);
        assertFalse(budget.tryConsume());
    }

    @Test
    public void zeroOrNegativeAllowanceNeverGrantsRetry() {
        assertFalse(new ReplanBudget(0).tryConsume());
        assertFalse(new ReplanBudget(-1).tryConsume());
    }
}
