package com.agentruntime.executor;

import com.agentruntime.state.UiNode;
import com.agentruntime.state.WorldState;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * tap_text 的两个兜底：首启弹窗自动关闭（只点无副作用按钮）+ 优先滚最上层容器。
 *
 * <p>实测依据：C05 里 NewPipe 首启弹出「Google 开发者验证」公告挡住整棵树，
 * 且抽屉打开时树里的第一个 scrollable 是背景 ViewPager，可滚动容器选错 -> SCROLL_FAILED。
 */
public class TapTextDialogTest {

    @Test
    public void safeLabelsAcceptedButAuthorizationLabelsRejected() {
        assertTrue(ActionExecutor.isSafeDialogLabel("OK"));
        assertTrue(ActionExecutor.isSafeDialogLabel(" got it "));
        assertTrue(ActionExecutor.isSafeDialogLabel("确定"));
        // 授权类必须由人点，永远不允许自动点。
        assertFalse(ActionExecutor.isSafeDialogLabel("Allow"));
        assertFalse(ActionExecutor.isSafeDialogLabel("允许"));
        assertFalse(ActionExecutor.isSafeDialogLabel("While using the app"));
        // 近似但不是白名单文案的不能点。
        assertFalse(ActionExecutor.isSafeDialogLabel("OK, delete everything"));
        assertFalse(ActionExecutor.isSafeDialogLabel(""));
        assertFalse(ActionExecutor.isSafeDialogLabel(null));
    }

    @Test
    public void partialWindowWithOkButtonIsDismissable() {
        // 实测 NewPipe 首启公告：android:id/parentPanel + android:id/button1。
        UiNode ok = node("button", "Button", "OK", null, true,
                Arrays.asList(), 466, 900, 642, 1012, "android:id/button1");
        UiNode panel = node("panel", "LinearLayout", "", null, false,
                Arrays.asList(ok), 45, 190, 675, 1012, "android:id/parentPanel");
        UiNode root = node("root", "FrameLayout", "", null, false,
                Arrays.asList(panel), 1, 146, 719, 1147, null);

        UiNode picked = ActionExecutor.dismissBlockingDialog(state(root));
        assertEquals("button", picked.nodeId);
    }

    @Test
    public void appScreenWithOkButtonIsNotTreatedAsDialog() {
        // 普通应用界面里出现一个叫 OK 的按钮（比如表单提交），不能自动点。
        // 实测 AVD 应用可见区域只有 720x1012，用"窗口比屏幕小"判断会误伤，所以改用结构 id。
        UiNode ok = node("ok", "Button", "OK", null, true,
                Arrays.asList(), 0, 600, 300, 700, "com.example.app:id/submit");
        UiNode root = node("root", "FrameLayout", "", null, false,
                Arrays.asList(ok), 0, 0, 720, 1012, null);

        assertNull(ActionExecutor.dismissBlockingDialog(state(root)));
        assertFalse(ActionExecutor.isDialogWindow(root));
    }

    @Test
    public void permissionDialogIsNotAutoClicked() {
        UiNode deny = node("deny", "Button", "Deny", null, true,
                Arrays.asList(), 40, 900, 340, 1012, "android:id/button2");
        UiNode allow = node("allow", "Button", "Allow", null, true,
                Arrays.asList(), 380, 900, 680, 1012, "android:id/button1");
        UiNode root = node("root", "FrameLayout", "", null, false,
                Arrays.asList(deny, allow), 20, 300, 700, 1012, "android:id/parentPanel");

        assertTrue(ActionExecutor.isDialogWindow(root));
        assertNull(ActionExecutor.dismissBlockingDialog(state(root)));
    }

    @Test
    public void dialogClassAloneIsEnoughToDetectDialog() {
        UiNode ok = node("ok", "Button", "Got it", null, true,
                Arrays.asList(), 10, 10, 100, 60, null);
        UiNode root = node("root", "androidx.appcompat.app.AlertDialog", "", null, false,
                Arrays.asList(ok), 0, 0, 720, 600, null);

        assertTrue(ActionExecutor.isDialogWindow(root));
        assertEquals("ok", ActionExecutor.dismissBlockingDialog(state(root)).nodeId);
    }

    @Test
    public void scrollablesAreReturnedTopmostFirst() {
        // 实测树序：背景 ViewPager 在前，抽屉 RecyclerView 在后。
        UiNode pager = scrollable(node("pager", "ViewPager", "", null, false,
                Arrays.asList(), 0, 431, 720, 1012, null));
        UiNode drawer = scrollable(node("drawer", "RecyclerView", "", null, false,
                Arrays.asList(), 0, 145, 544, 1012, null));
        UiNode root = node("root", "FrameLayout", "", null, false,
                Arrays.asList(pager, drawer), 0, 0, 720, 1280, null);

        List<UiNode> order = ActionExecutor.bottomUpScrollables(state(root));
        assertEquals(2, order.size());
        assertEquals("drawer", order.get(0).nodeId);
        assertEquals("pager", order.get(1).nodeId);
    }

    // ------------------------------------------------------------- helpers

    private static WorldState state(UiNode root) {
        return new WorldState(1, 1, 0, "com.example.app", "w", "test", root, null, 0);
    }

    private static UiNode node(String id, String cls, String text, String desc, boolean clickable,
                               List<UiNode> children, int l, int t, int r, int b, String resId) {
        return new UiNode(id, cls, text, desc, resId, clickable, true, true,
                false, false, false, false, l, t, r, b, new ArrayList<>(children));
    }

    private static UiNode scrollable(UiNode n) {
        return new UiNode(n.nodeId, n.className, n.text, n.contentDescription, n.resourceId,
                n.clickable, n.enabled, n.visible, n.focusable, n.editable, true, n.focused,
                n.left, n.top, n.right, n.bottom, n.children);
    }
}
