package com.agentruntime.vision;

import com.agentruntime.state.WorldState;
import java.util.concurrent.atomic.AtomicBoolean;

/** An observation, never an action target. Implementations must not persist screenshots. */
public interface ScreenTextOcr {
    Result read(WorldState state, AtomicBoolean cancelled, long deadlineMs);

    final class Result {
        public final String code;
        public final String text;

        public Result(String code, String text) {
            this.code = code;
            this.text = text;
        }
    }
}
