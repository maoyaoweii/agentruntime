package com.agentruntime.vision.vlm;

/**
 * Idefics3 / SmolVLM 的图像预处理。
 *
 * <p>这一步决定了模型"看得见什么"，也是端侧 VLM 最容易出错的地方：
 * 尺寸算错 → patch 数不对 → 视觉 token 数与模型预期不符 → 直接崩或输出垃圾。
 *
 * <p>处理规则（与 HuggingFace 的 Idefics3ImageProcessor 对齐）：
 * <ol>
 *   <li>保持宽高比，把图像缩放到能装进 maxPatches 个 512×512 格子的最大尺寸</li>
 *   <li>按 512 网格切块，每块单独归一化</li>
 *   <li>归一化用 SigLIP 的 mean=0.5 / std=0.5，即像素值线性映射到 [-1, 1]</li>
 * </ol>
 *
 * <p>刻意不依赖 Android 的 Bitmap：输入是 ARGB 像素数组，这样这段逻辑能在 JVM 单测里
 * 用构造数据完整验证。
 */
public final class VlmPreprocessor {

    /** SigLIP 视觉编码器要求的输入边长。 */
    public static final int PATCH_SIZE = 512;

    /** SmolVLM-256M 默认最多 4 个图像 patch，防止长截图撑爆显存。 */
    public static final int DEFAULT_MAX_PATCHES = 4;

    public static final float MEAN = 0.5f;
    public static final float STD = 0.5f;

    private VlmPreprocessor() {
    }

    /** 网格规划结果。 */
    public static final class Grid {
        public final int cols;
        public final int rows;
        public final int width;
        public final int height;

        Grid(int cols, int rows, int width, int height) {
            this.cols = cols;
            this.rows = rows;
            this.width = width;
            this.height = height;
        }

        public int patchCount() {
            return cols * rows;
        }

        public String describe() {
            return cols + "x" + rows + " patches, resized " + width + "x" + height;
        }
    }

    /**
     * 计算缩放后的尺寸与 patch 网格。
     *
     * <p>按面积约束求解缩放比：保证 cols*rows <= maxPatches，同时尽量保持原比例。
     * 比如 1080×2340 竖屏截图 → 1×3 网格（而不是硬切成 2×2 导致画面被压扁）。
     */
    public static Grid planGrid(int srcWidth, int srcHeight, int patchSize, int maxPatches) {
        if (srcWidth <= 0 || srcHeight <= 0) {
            return new Grid(1, 1, patchSize, patchSize);
        }
        int patch = patchSize > 0 ? patchSize : PATCH_SIZE;
        int maxP = Math.max(1, maxPatches);

        double srcArea = (double) srcWidth * srcHeight;
        double budgetArea = (double) maxP * patch * patch;
        double scale = Math.sqrt(budgetArea / srcArea);
        // 比例已经很小时不要放大（放大不增加信息，只增加计算）
        if (scale > 1.0) scale = 1.0;

        int targetW = Math.max(patch, (int) Math.round(srcWidth * scale));
        int targetH = Math.max(patch, (int) Math.round(srcHeight * scale));

        int cols = Math.max(1, Math.round((float) targetW / patch));
        int rows = Math.max(1, Math.round((float) targetH / patch));

        // 兜底：四舍五入可能超预算，按长边收缩
        while (cols * rows > maxP) {
            if (cols >= rows) cols--;
            else rows--;
            cols = Math.max(1, cols);
            rows = Math.max(1, rows);
        }

        return new Grid(cols, rows, cols * patch, rows * patch);
    }

    /**
     * 预处理：ARGB 像素 → NCHW float 张量（每个 patch 一个 [3][512][512]）。
     *
     * @param argb       长度 >= width*height 的 ARGB_8888 像素
     * @param width      源图宽
     * @param height     源图高
     * @param maxPatches 最多 patch 数
     * @return [patchCount][3 * patch * patch]，值域 [-1, 1]
     */
    public static float[][] preprocess(int[] argb, int width, int height, int maxPatches) {
        Grid grid = planGrid(width, height, PATCH_SIZE, maxPatches);
        int patch = PATCH_SIZE;
        int perPatch = 3 * patch * patch;
        float[][] out = new float[grid.patchCount()][perPatch];

        int idx = 0;
        for (int r = 0; r < grid.rows; r++) {
            for (int c = 0; c < grid.cols; c++) {
                float[] dst = out[idx++];
                fillPatch(dst, argb, width, height, grid, r, c, patch);
            }
        }
        return out;
    }

    /** 取网格中第 (row, col) 块，缩放到 patch×patch 并归一化写入 dst。 */
    private static void fillPatch(float[] dst, int[] argb, int srcW, int srcH,
                                  Grid grid, int row, int col, int patch) {
        // 源图上的浮点采样区域：按缩放比例反推，避免网格边界累积误差
        double sx = (double) srcW / grid.cols;
        double sy = (double) srcH / grid.rows;
        double x0 = col * sx;
        double y0 = row * sy;

        int planeSize = patch * patch;
        for (int y = 0; y < patch; y++) {
            // 采样点落在源图上的浮点坐标（双线性）
            double fy = y0 + (y + 0.5) * sy / patch;
            int y1 = (int) Math.floor(fy);
            int y2 = Math.min(srcH - 1, y1 + 1);
            double wy = fy - y1;
            y1 = clamp(y1, 0, srcH - 1);

            for (int x = 0; x < patch; x++) {
                double fx = x0 + (x + 0.5) * sx / patch;
                int x1 = (int) Math.floor(fx);
                int x2 = Math.min(srcW - 1, x1 + 1);
                double wx = fx - x1;
                x1 = clamp(x1, 0, srcW - 1);

                int p11 = pixel(argb, srcW, srcH, x1, y1);
                int p21 = pixel(argb, srcW, srcH, x2, y1);
                int p12 = pixel(argb, srcW, srcH, x1, y2);
                int p22 = pixel(argb, srcW, srcH, x2, y2);

                double r = bilinear((p11 >> 16) & 0xFF, (p21 >> 16) & 0xFF,
                        (p12 >> 16) & 0xFF, (p22 >> 16) & 0xFF, wx, wy);
                double g = bilinear((p11 >> 8) & 0xFF, (p21 >> 8) & 0xFF,
                        (p12 >> 8) & 0xFF, (p22 >> 8) & 0xFF, wx, wy);
                double b = bilinear(p11 & 0xFF, p21 & 0xFF, p12 & 0xFF, p22 & 0xFF, wx, wy);

                int off = y * patch + x;
                dst[off] = normalize(r);
                dst[planeSize + off] = normalize(g);
                dst[2 * planeSize + off] = normalize(b);
            }
        }
    }

    private static double bilinear(int v11, int v21, int v12, int v22, double wx, double wy) {
        double top = v11 * (1 - wx) + v21 * wx;
        double bottom = v12 * (1 - wx) + v22 * wx;
        return top * (1 - wy) + bottom * wy;
    }

    /** 0..255 → [-1, 1]，即 (v/255 - mean) / std。 */
    public static float normalize(double v255) {
        return (float) ((v255 / 255.0 - MEAN) / STD);
    }

    private static int pixel(int[] argb, int w, int h, int x, int y) {
        if (argb == null) return 0xFF000000;
        int idx = y * w + x;
        if (idx < 0 || idx >= argb.length) return 0xFF000000;
        return argb[idx];
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
