package com.agentruntime;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.agentruntime.agent.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 界面结构验证：在 JVM 里跑真实 Android 运行时，把界面在多种机型尺寸下真正 inflate 并测量。
 *
 * <p><b>为什么不是截图：</b>本机两条路都堵死了——
 * (1) 固件没开 SVM，Android 模拟器在纯 TCG 下 qemu 起不来（CPU 时间恒为 0）；
 * (2) Robolectric 的 NATIVE 图形模式没有 Windows 版运行时（缺 robolectric-nativeruntime.dll），
 *     拿不到真像素。所以这里验的是"能画出来之前"的所有环节：
 *     资源能否解析、布局能否 inflate、四种尺寸下测量与摆放是否合理、横屏是否真的换了排版。
 *
 * <p><b>这些断言能挡住什么：</b>
 * 深色主题写错（颜色解析失败会直接抛）、横屏没生效 layout-land（右栏宽度断言会挂）、
 * 底栏被挤出屏幕（composer 下边界越界）、流水区被压成 0 高（transcript 高度断言）。
 *
 * <p><b>这些断言挡不住什么：</b>字体渲染差异、GPU 合成、刘海/手势条避让、真实滚动手感。
 * 这些一律标"未验证"，不能因为本测试绿了就当成真机可用。
 */
@RunWith(RobolectricTestRunner.class)
public class UiLayoutTest {

    /** 常见机型尺寸。dp 取自各档位典型设备。 */
    /**
     * 目标机型只用 dp 描述，像素由运行时 density 换算。
     *
     * <p>第一版写死了 dpi，结果 xxhdpi 实际是 480 而按 440 算，测出来的画布比真实窄 —— 
     * 断言依然"通过"，但测的不是目标机型。这类误差只有对着 density 算才消得掉。
     */
    private static final class Device {
        final String name;
        final int wDp;
        final int hDp;

        Device(String name, int wDp, int hDp) {
            this.name = name;
            this.wDp = wDp;
            this.hDp = hDp;
        }
    }

    private static final Device PIXEL5 = new Device("pixel5", 411, 891);
    private static final Device SMALL = new Device("small", 320, 640);
    private static final Device PIXEL5_LAND = new Device("pixel5_land", 891, 411);
    private static final Device TABLET = new Device("tablet", 800, 1280);

    // ------------------------------------------------------------ 用例

    @Test
    @Config(sdk = 30, qualifiers = "w411dp-h891dp-xxhdpi")
    public void portraitLayoutIsSane() {
        Activity a = launch(PIXEL5);
        assertEquals("竖屏应使用单列布局", View.VISIBLE, a.findViewById(R.id.composer).getVisibility());
        assertBottomComposer(a, PIXEL5);
        assertTranscriptTakesMiddle(a, PIXEL5);
        assertNoChildOverflows(a, PIXEL5);
    }

    @Test
    @Config(sdk = 30, qualifiers = "w320dp-h640dp-xhdpi")
    public void smallScreenKeepsComposerVisible() {
        Activity a = launch(SMALL);
        assertBottomComposer(a, SMALL);
        assertTranscriptTakesMiddle(a, SMALL);
        assertNoChildOverflows(a, SMALL);
    }

    /** 小屏上最容易出的问题是：底栏被键盘/输入框顶出屏幕，或者流水区被压没。 */
    @Test
    @Config(sdk = 30, qualifiers = "w320dp-h640dp-xhdpi")
    public void smallScreenComposerIsNotClipped() {
        Activity a = launch(SMALL);
        View composer = a.findViewById(R.id.composer);
        int[] loc = locate(composer);
        int bottom = loc[1] + composer.getHeight();
        assertTrue("输入区下边界越界：" + bottom + " > " + px(a, SMALL.hDp),
                bottom <= px(a, SMALL.hDp));
        assertTrue("输入区高度异常：" + composer.getHeight(), composer.getHeight() > 0);
    }

    @Test
    @Config(sdk = 30, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void landscapeUsesTwoPane() {
        Activity a = launch(PIXEL5_LAND);
        View transcript = a.findViewById(R.id.transcript);
        View statusRow = a.findViewById(R.id.status_row);
        assertNotNull("横屏缺少状态列", statusRow);

        // layout-land 里状态列是 248dp 宽的右栏；竖屏时它是一条横向可滚动的行。
        int expectedPanelPx = px(a, 248);
        View panel = (View) statusRow.getParent();
        assertTrue("横屏右栏宽度应接近 248dp，实际 " + panel.getWidth() + "px",
                Math.abs(panel.getWidth() - expectedPanelPx) <= 8);

        // 两栏：流水区必须比整屏窄，否则说明还在用单列布局
        assertTrue("横屏下流水区占了整屏宽度，layout-land 没生效："
                        + transcript.getWidth() + " / " + px(a, PIXEL5_LAND.wDp),
                transcript.getWidth() < px(a, PIXEL5_LAND.wDp));

        // 横屏垂直空间紧张，输入区仍必须在屏内
        assertBottomComposer(a, PIXEL5_LAND);
    }

    @Test
    @Config(sdk = 30, qualifiers = "sw600dp-w800dp-h1280dp-xhdpi")
    public void tabletLayoutIsSane() {
        Activity a = launch(TABLET);
        assertBottomComposer(a, TABLET);
        assertTranscriptTakesMiddle(a, TABLET);
    }

    /** 深色主题：窗口背景必须解析成暗色。写错颜色这里就会露馅。 */
    @Test
    @Config(sdk = 30, qualifiers = "w411dp-h891dp-xxhdpi")
    public void darkThemeAppliesToWindow() {
        Activity a = launch(PIXEL5);
        int color = a.getResources().getColor(R.color.bg, a.getTheme());
        double lum = luminance(color);
        assertTrue("bg 不够暗：lum=" + lum, lum < 60);
        // 主文字必须够亮，否则暗底上看不清
        assertTrue("text_primary 不够亮",
                luminance(a.getResources().getColor(R.color.text_primary, a.getTheme())) > 180);
    }

    /** 会话流水组件本身：加条目不能崩，且必须真的挂到容器上。 */
    @Test
    @Config(sdk = 30, qualifiers = "w411dp-h891dp-xxhdpi")
    public void transcriptAcceptsAllEntryKinds() {
        Activity a = launch(PIXEL5);
        LinearLayout host = a.findViewById(R.id.transcript);
        ScrollView scroll = a.findViewById(R.id.scroll);
        com.agentruntime.ui.CodexTranscript t =
                new com.agentruntime.ui.CodexTranscript(scroll, host);

        int before = host.getChildCount();
        t.addUser("观察当前设备环境");
        t.addSection("运行");
        t.addToolCall("sys.observe", "step 1/1");
        t.addToolResult(true, "OK", 312);
        t.addSummary(true, "任务完成：SUCCESS");
        t.addError("任务未成功：FAILED");
        t.addWarn("无障碍未开启");
        t.addNote("plan", "规划完成 → t_001");

        assertEquals("8 条记录应全部挂上", before + 8, host.getChildCount());
        // 用户发言块用的是定制背景，必须解析成功（写错 drawable 会抛 NotFoundException）
        assertNotNull(host.getChildAt(before).getBackground());
    }

    // ------------------------------------------------------------ 断言工具

    private Activity launch(Device d) {
        ActivityController<com.agentruntime.ui.MainActivity> c =
                Robolectric.buildActivity(com.agentruntime.ui.MainActivity.class).setup();
        Activity a = c.get();
        View decor = a.getWindow().getDecorView();
        int w = px(a, d.wDp);
        int h = px(a, d.hDp);
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, w, h);
        return a;
    }

    private int px(Activity a, int dp) {
        return Math.round(dp * a.getResources().getDisplayMetrics().density);
    }

    private void assertBottomComposer(Activity a, Device d) {
        View composer = a.findViewById(R.id.composer);
        assertNotNull(d.name + " 找不到输入区", composer);
        int[] loc = locate(composer);
        assertTrue(d.name + " 输入区被顶出屏幕：top=" + loc[1], loc[1] < px(a, d.hDp));
        assertTrue(d.name + " 输入区高度为 0", composer.getHeight() > 0);
    }

    private void assertTranscriptTakesMiddle(Activity a, Device d) {
        View transcript = a.findViewById(R.id.transcript);
        View scroll = a.findViewById(R.id.scroll);
        assertNotNull(d.name + " 找不到流水区", transcript);
        assertTrue(d.name + " 流水区高度过小：" + scroll.getHeight(),
                scroll.getHeight() > px(a, d.hDp) * 0.35);
        int[] t = locate(scroll);
        int[] c = locate(a.findViewById(R.id.composer));
        assertTrue(d.name + " 流水区应在输入区上方", t[1] < c[1]);
    }

    /** 横向溢出：任何子视图的右边界都不该超出屏幕。布局写错 padding/宽度时最容易出这个。 */
    private void assertNoChildOverflows(Activity a, Device d) {
        View root = a.findViewById(android.R.id.content);
        assertTrue(d.name + " 越界检查失败", !overflow(root, px(a, d.wDp), 0));
    }

    private boolean overflow(View v, int screenW, int depth) {
        if (v == null || depth > 40) return false;
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        if (loc[0] + v.getWidth() > screenW + 2) {
            System.out.println("[overflow] " + v.getClass().getSimpleName()
                    + " id=" + (v.getId() == View.NO_ID ? "-" : String.valueOf(v.getId()))
                    + " right=" + (loc[0] + v.getWidth()) + " > " + screenW);
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (overflow(g.getChildAt(i), screenW, depth + 1)) return true;
            }
        }
        return false;
    }

    private int[] locate(View v) {
        int[] loc = new int[2];
        if (v != null) v.getLocationInWindow(loc);
        return loc;
    }

    private double luminance(int c) {
        return 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c);
    }

}
