package com.agentruntime.dsh.ui;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 模型回复里的 Markdown 必须被渲染，而不是把 ** 和 | 原样甩给用户。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class MarkdownTest {

    private static String render(String source) {
        Application context = RuntimeEnvironment.getApplication();
        return Markdown.render(context, source).toString();
    }

    @Test
    public void stripsEmphasisMarkers() {
        String out = render("**已完成:** B 站已启动，`LAUNCH_OK`。");
        assertTrue(out.contains("已完成:"));
        assertTrue(out.contains("LAUNCH_OK"));
        assertFalse(out.contains("**"));
        assertFalse(out.contains("`"));
    }

    @Test
    public void rendersTableAsAlignedRows() {
        String out = render("| 步骤 | 结果 |\n|---|------|\n| 打开 | 成功 |");
        assertTrue(out.contains("步骤"));
        assertTrue(out.contains("成功"));
        assertFalse(out.contains("|---"));
        assertFalse(out.contains("| 步骤 |"));
    }

    @Test
    public void keepsListItemsReadable() {
        String out = render("- 第一条\n- 第二条");
        assertTrue(out.contains("•  第一条"));
        assertTrue(out.contains("•  第二条"));
    }

    @Test
    public void codeBlockLosesFence() {
        String out = render("```json\n{\"ok\":true}\n```");
        assertTrue(out.contains("{\"ok\":true}"));
        assertFalse(out.contains("```"));
    }

    @Test
    public void plainTextIsUnchanged() {
        String text = "现在几点？帮我打开时钟。";
        assertTrue(render(text).startsWith(text));
    }
}
