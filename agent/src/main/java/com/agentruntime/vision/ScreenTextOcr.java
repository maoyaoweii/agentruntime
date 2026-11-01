package com.agentruntime.vision;

import com.agentruntime.state.WorldState;
import java.util.concurrent.atomic.AtomicBoolean;

/** An observation, never an action target. Implementations must not persist screenshots. */
public interface ScreenTextOcr {
    Result read(WorldState state, AtomicBoolean cancelled, long deadlineMs);

    final class Result {
        public final String code;
        public final String text;
        /** 识别到的文字块及其像素位置；为空表示只能拿到整段文字，不能当点击目标。 */
        public final java.util.List<ScreenTextBox> boxes;

        public Result(String code, String text) {
            this(code, text, java.util.Collections.emptyList());
        }

        public Result(String code, String text, java.util.List<ScreenTextBox> boxes) {
            this.code = code;
            this.text = text;
            this.boxes = boxes == null ? java.util.Collections.emptyList() : boxes;
        }
    }
}
