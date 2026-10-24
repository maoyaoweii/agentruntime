package com.agentruntime.vision;

/**
 * 一帧屏幕截图及其几何信息。
 *
 * <p>关键点：模型看到的是<b>缩放后</b>的图，它吐出的坐标也是缩放图的坐标。
 * 所以必须同时携带 scale 与真实屏幕尺寸，否则点击会整体偏移——
 * 这类 bug 在真机上表现为"点到了隔壁"，且只在特定分辨率下复现，极难排查。
 */
public final class ScreenFrame {

    /** 真实屏幕尺寸（像素）。 */
    public final int screenWidth;
    public final int screenHeight;

    /** 实际送给模型的图片尺寸（像素）。 */
    public final int imageWidth;
    public final int imageHeight;

    /** 缩放比 = imageWidth / screenWidth。 */
    public final float scale;

    public final byte[] imageBytes;
    public final String mime;
    public final long capturedAt;
    public final long captureMs;

    public ScreenFrame(int screenWidth, int screenHeight, int imageWidth, int imageHeight,
                       byte[] imageBytes, String mime, long capturedAt, long captureMs) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.scale = screenWidth > 0 ? (float) imageWidth / (float) screenWidth : 1f;
        this.imageBytes = imageBytes;
        this.mime = mime == null ? "image/jpeg" : mime;
        this.capturedAt = capturedAt;
        this.captureMs = captureMs;
    }

    public boolean valid() {
        return imageBytes != null && imageBytes.length > 0
                && imageWidth > 0 && imageHeight > 0
                && screenWidth > 0 && screenHeight > 0;
    }

    public int sizeKb() {
        return imageBytes == null ? 0 : imageBytes.length / 1024;
    }

    public String describe() {
        return screenWidth + "x" + screenHeight + " -> " + imageWidth + "x" + imageHeight
                + " (" + sizeKb() + "KB, " + mime + ")";
    }
}
