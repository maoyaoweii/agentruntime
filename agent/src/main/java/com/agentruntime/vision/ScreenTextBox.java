package com.agentruntime.vision;

/** 识别到的一块文字及其截图像素位置。主循环用它决定"按文字位置点哪里"。 */
public final class ScreenTextBox {
    public final String text;
    public final int left, top, right, bottom;

    public ScreenTextBox(String text, int left, int top, int right, int bottom) {
        this.text = text == null ? "" : text;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int centerX() { return (left + right) / 2; }

    public int centerY() { return (top + bottom) / 2; }
}
