package com.agentruntime.vision;

/**
 * 坐标映射与合法性校验。
 *
 * <p>纯逻辑，无 Android 依赖，是视觉点击链路上最该被单测覆盖的一段：
 * 模型给的坐标经过这里才变成真实屏幕上的触点，一旦算错就是"点歪"。
 */
public final class CoordinateMapper {

    /** 映射结果。 */
    public static final class Point {
        public final int x;
        public final int y;
        public final boolean inBounds;

        Point(int x, int y, boolean inBounds) {
            this.x = x;
            this.y = y;
            this.inBounds = inBounds;
        }

        public String describe() {
            return "(" + x + "," + y + ")" + (inBounds ? "" : " OUT_OF_BOUNDS");
        }
    }

    private CoordinateMapper() {
    }

    /**
     * 把模型坐标（基于 imageWidth × imageHeight）映射回真实屏幕。
     *
     * <p>宽高分别按比例映射，而不是用单一 scale：某些机型截图会被轻微压缩，
     * 单一 scale 会在远端累积出十几个像素的偏差。
     *
     * @return 映射后的点；越界时 inBounds=false，调用方应拒绝执行而不是硬点
     */
    public static Point mapToScreen(int modelX, int modelY,
                                    int imageWidth, int imageHeight,
                                    int screenWidth, int screenHeight) {
        if (imageWidth <= 0 || imageHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            return new Point(0, 0, false);
        }
        int x = Math.round(modelX * (float) screenWidth / (float) imageWidth);
        int y = Math.round(modelY * (float) screenHeight / (float) imageHeight);

        boolean inBounds = x >= 0 && y >= 0 && x < screenWidth && y < screenHeight;
        // 越界一律夹到边界内再判断：模型常给出 1~2 像素溢出的坐标，直接拒绝太脆
        x = clamp(x, 0, screenWidth - 1);
        y = clamp(y, 0, screenHeight - 1);
        return new Point(x, y, inBounds);
    }

    /** 用节点边界框的中心点作为点击坐标。 */
    public static Point centerOf(int left, int top, int right, int bottom,
                                 int screenWidth, int screenHeight) {
        int x = Math.round((left + right) / 2f);
        int y = Math.round((top + bottom) / 2f);
        boolean inBounds = x >= 0 && y >= 0 && x < screenWidth && y < screenHeight;
        return new Point(clamp(x, 0, Math.max(0, screenWidth - 1)),
                clamp(y, 0, Math.max(0, screenHeight - 1)), inBounds);
    }

    /** 校验一组手势坐标是否都落在屏幕内。 */
    public static boolean withinScreen(int x1, int y1, int x2, int y2,
                                       int screenWidth, int screenHeight) {
        return inScreen(x1, y1, screenWidth, screenHeight)
                && inScreen(x2, y2, screenWidth, screenHeight);
    }

    public static boolean inScreen(int x, int y, int screenWidth, int screenHeight) {
        return x >= 0 && y >= 0 && x < screenWidth && y < screenHeight;
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
