package com.agentruntime;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.agentruntime.agent.R;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * 把真实测量后的视图树导出成 JSON，供离线重绘。
 *
 * <p>为什么需要：本机跑不了模拟器（固件 SVM 未开），也拿不到 Robolectric 的像素渲染
 * （Windows 没有 nativeruntime）。但"每个控件在什么位置、多大、什么颜色、写了什么字"
 * 这些数据在 measure/layout 之后是确定存在的——把它导出来，就能在 JVM 之外按真实几何重绘。
 *
 * <p><b>重要限制（已实测）：</b>Robolectric 的 LEGACY 图形模式下 `Paint.measureText` 是桩实现，
 * 实测把 42px 的 "agent runtime" 量成 13px 宽。于是所有 `wrap_content` 的文字控件
 * 宽度都偏小一个数量级——导出的 JSON 里"位置/尺寸"对容器可靠，对文字控件<b>不可靠</b>。
 * 所以这份数据只能用来核对层级与颜色，<b>不要直接拿它渲染成"界面预览图"</b>。
 * 想要像素级预览，要么用真机/模拟器截图，要么用 docs/shots/v16/mockup_*.html 那种按布局文件还原的图。
 */
@RunWith(RobolectricTestRunner.class)
public class UiTreeDumpTest {

    private static final File OUT = new File("../docs/shots/v16");

    @Test
    @Config(sdk = 30, qualifiers = "w411dp-h891dp-xxhdpi")
    public void dumpPortrait() throws Exception {
        dump(411, 891, "pixel5_portrait", false);
    }

    @Test
    @Config(sdk = 30, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void dumpLandscape() throws Exception {
        dump(891, 411, "pixel5_landscape", true);
    }

    private void dump(int wDp, int hDp, String tag, boolean landscape) throws Exception {
        ActivityController<com.agentruntime.ui.MainActivity> c =
                Robolectric.buildActivity(com.agentruntime.ui.MainActivity.class).setup();
        Activity a = c.get();
        View decor = a.getWindow().getDecorView();
        float density = a.getResources().getDisplayMetrics().density;
        int w = Math.round(wDp * density);
        int h = Math.round(hDp * density);
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, w, h);

        seedTranscript(a);

        JSONObject root = new JSONObject();
        root.put("width", w);
        root.put("height", h);
        root.put("density", density);
        root.put("tag", tag);
        root.put("landscape", landscape);
        root.put("bg", colorOf(a.getResources().getColor(R.color.bg, a.getTheme())));
        JSONArray nodes = new JSONArray();
        walk(a, a.findViewById(android.R.id.content), nodes, 0);
        root.put("nodes", nodes);

        if (!OUT.exists() && !OUT.mkdirs()) {
            throw new IllegalStateException("无法创建目录 " + OUT.getAbsolutePath());
        }
        File f = new File(OUT, "tree_" + tag + ".json");
        try (Writer wr = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            wr.write(root.toString(1));
        }
        System.out.println("[tree] " + f.getAbsolutePath() + "  nodes=" + nodes.length()
                + "  " + w + "x" + h + " @density " + density);
    }

    /** 造一段有代表性的会话，否则导出的是空态，看不出流水长什么样。 */
    private void seedTranscript(Activity a) {
        LinearLayout host = a.findViewById(R.id.transcript);
        ScrollView scroll = a.findViewById(R.id.scroll);
        if (host == null || host.getChildCount() > 0) return;

        com.agentruntime.ui.CodexTranscript t =
                new com.agentruntime.ui.CodexTranscript(scroll, host);
        t.addUser("观察当前设备环境，然后截图留证");
        t.addSection("运行");
        t.addToolCall("sys.observe", "step 1/2");
        t.addToolResult(true, "OK", 312);
        t.addToolCall("sys.screenshot", "step 2/2");
        t.addToolResult(true, "OK", 1180);
        t.addSummary(true, "任务完成：SUCCESS");
        t.addNote("plan", "规划完成 → t_8f3a1c");
    }

    private void walk(Activity a, View v, JSONArray out, int depth) throws Exception {
        if (v == null || depth > 30) return;
        if (v.getVisibility() != View.VISIBLE) return;

        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        int x = loc[0];
        int y = loc[1];
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) return;

        JSONObject n = new JSONObject();
        n.put("cls", v.getClass().getSimpleName());
        n.put("x", x);
        n.put("y", y);
        n.put("w", w);
        n.put("h", h);
        String idName = idName(a, v);
        if (!idName.isEmpty()) n.put("id", idName);

        int bg = backgroundColor(v);
        if (bg != 0) n.put("bg", colorOf(bg));

        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            String text = tv.getText().toString();
            if (text.isEmpty() && tv instanceof EditText) {
                text = tv.getHint() == null ? "" : tv.getHint().toString();
                n.put("hint", true);
            }
            n.put("text", text);
            n.put("fg", colorOf(tv.getCurrentTextColor()));
            n.put("size", Math.round(tv.getTextSize()));
            n.put("padL", tv.getPaddingLeft());
            n.put("padT", tv.getPaddingTop());
            n.put("gravity", tv.getGravity());
            n.put("lines", tv.getLineCount() > 0 ? tv.getLineCount() : 1);
        }
        out.put(n);

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(a, g.getChildAt(i), out, depth + 1);
            }
        }
    }

    private static String idName(Activity a, View v) {
        if (v.getId() == View.NO_ID) return "";
        try {
            return a.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return "";
        }
    }

    /** 背景色：纯色与 shape 两种都要取，取不到返回 0（重绘时按透明处理）。 */
    private static int backgroundColor(View v) {
        Drawable d = v.getBackground();
        if (d == null) return 0;
        try {
            if (d instanceof ColorDrawable) {
                return ((ColorDrawable) d).getColor();
            }
            if (d instanceof GradientDrawable) {
                return ((GradientDrawable) d).getColor() == null
                        ? 0 : ((GradientDrawable) d).getColor().getDefaultColor();
            }
            // 用户发言块用的是 layer-list（底色 + 左侧竖线），取第一层的底色
            if (d instanceof LayerDrawable) {
                LayerDrawable ld = (LayerDrawable) d;
                for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                    Drawable layer = ld.getDrawable(i);
                    if (layer instanceof GradientDrawable) {
                        android.content.res.ColorStateList c = ((GradientDrawable) layer).getColor();
                        if (c != null) return c.getDefaultColor();
                    } else if (layer instanceof ColorDrawable) {
                        return ((ColorDrawable) layer).getColor();
                    }
                }
            }
        } catch (Throwable ignored) {
            // Robolectric 的旧图形模式下部分 drawable 取不到颜色
        }
        return 0;
    }

    private static String colorOf(int argb) {
        return String.format("#%08X", argb);
    }
}
