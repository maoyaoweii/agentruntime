package com.agentruntime.goal;

import com.agentruntime.ext.Extensions;
import com.agentruntime.state.UiNode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** 主循环的验收：不成功就是没成功，换招在主循环里，扩展关掉也能跑完。 */
public class GoalMainLoopTest {

    private static final String SELF = "com.agentruntime.agent";

    static final class Fake implements GoalScreen {
        String self = SELF;
        String pkg = "com.example.app";
        List<UiNode> nodes = new ArrayList<>();
        List<GoalScreen.OcrItem> ocr = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        boolean launchChanges;
        String afterLaunch;
        String pointTargetPackage;
        int launches, ocrCalls;
        boolean virtual;
        final List<UiNode> tapped = new ArrayList<>();
        final List<int[]> points = new ArrayList<>();

        public Facts read() { return new Facts(pkg, "", nodes, Collections.emptyList()); }

        public boolean launchByPackage(String p) {
            launches++;
            if (launchChanges) pkg = afterLaunch != null ? afterLaunch : p;
            return true;
        }

        public boolean tapNode(UiNode n) { tapped.add(n); return true; }

        public boolean tapCenter(UiNode n) { tapped.add(n); return true; }

        public boolean tapPoint(int x, int y) {
            points.add(new int[]{x, y});
            if (pointTargetPackage != null) pkg = pointTargetPackage;
            return true;
        }

        public boolean yieldForeground() { return true; }

        public OcrResult readImage() {
            ocrCalls++;
            return new OcrResult(true, "SCREEN_OCR", ocr);
        }

        public boolean virtualDisplayAvailable() { return virtual; }

        public String resolvePackage(String label) { return names.get(label); }

        public String selfPackage() { return self; }
    }

    private static UiNode node(String id, String text, int l, int t, int r, int b) {
        return new UiNode(id, "android.widget.TextView", text, null, null,
                true, true, true, false, false, false, false, l, t, r, b, Collections.emptyList());
    }

    /** 1. 同一棵桌面树，点「微信」前后签名相同 → 不是成功。 */
    @Test public void tappingWithoutAnyChangeIsNeverSuccess() {
        Fake screen = new Fake();
        screen.nodes.add(node("n1", "微信", 40, 400, 240, 600));

        GoalCard card = new GoalMainLoop(screen, GoalPathStore.inMemory()).run("点一下微信");

        assertNotEquals(GoalCard.State.SUCCEEDED, card.state());
        assertEquals(GoalCard.State.FAILED, card.state());
        assertFalse(card.verified());
        for (GoalCard.Evidence evidence : card.evidence()) {
            assertNotEquals("STEP_OK", evidence.code);
        }
    }

    /** 2. 同名可点节点选面积小的；launcher 判定。 */
    @Test public void sameNameNodesPickTheSmallerIcon() {
        assertTrue(GoalMainLoop.isLauncher("com.android.launcher3"));
        assertTrue(GoalMainLoop.isLauncher("com.miui.home"));
        assertTrue(GoalMainLoop.isLauncher("com.example.lawnchair"));
        assertFalse(GoalMainLoop.isLauncher("com.tencent.mm"));

        Fake screen = new Fake();
        screen.pkg = "com.android.launcher3";
        screen.nodes.add(node("big", "小米商城", 0, 0, 400, 400));
        screen.nodes.add(node("small", "小米商城", 0, 0, 100, 100));

        new GoalMainLoop(screen, GoalPathStore.inMemory()).run("打开小米商城");

        assertFalse(screen.tapped.isEmpty());
        UiNode first = screen.tapped.get(0);
        assertEquals("small", first.nodeId);
        assertEquals(100 * 100, (first.right - first.left) * (first.bottom - first.top));
    }

    /** 3. 启动调用成功但前台仍是自己的包名 → LAUNCH_NOT_FOREGROUND，下一招不是再点图标。 */
    @Test public void launchWithoutForegroundIsNotSuccessAndDoesNotTapTheIcon() {
        Fake screen = new Fake();
        screen.pkg = SELF;
        screen.nodes.add(node("n1", "微信", 40, 400, 240, 600));
        screen.launchChanges = true;
        screen.afterLaunch = SELF;

        GoalCard card = new GoalMainLoop(screen, GoalPathStore.inMemory()).run("打开微信");

        assertEquals(GoalCard.State.FAILED, card.state());
        boolean sawNotForeground = false;
        for (GoalCard.Evidence evidence : card.evidence()) {
            if (evidence.code.contains("LAUNCH_NOT_FOREGROUND")) sawNotForeground = true;
        }
        assertTrue("必须记录 LAUNCH_NOT_FOREGROUND", sawNotForeground);
        assertTrue("启动失败后不该再去点同一个图标", screen.tapped.isEmpty());
        assertTrue(card.foregroundAtFailure().contains(SELF));
    }

    /** 4. 无障碍空树 → 同一轮改看图，并按识别位置点。 */
    @Test public void emptyAccessibilityTreeSwitchesToImageInTheSameRound() {
        Fake screen = new Fake();
        screen.pkg = "com.example.app";
        screen.ocr.add(new GoalScreen.OcrItem("微信", 100, 800, 300, 900));
        screen.pointTargetPackage = "com.tencent.mm";

        GoalCard card = new GoalMainLoop(screen, GoalPathStore.inMemory()).run("打开微信");

        assertEquals(GoalCard.State.SUCCEEDED, card.state());
        assertTrue(card.verified());
        assertTrue("必须在同一轮里看图", screen.ocrCalls >= 1);
        assertEquals(1, screen.points.size());
        assertEquals(200, screen.points.get(0)[0]);
        assertEquals(850, screen.points.get(0)[1]);
    }

    /** 5. 口头说完成但证据为空 → 不是 succeeded。 */
    @Test public void modelClaimWithoutEvidenceIsNeverSuccess() {
        GoalCard card = new GoalCard("g1", "打开微信");
        card.criteria("前台包名是 com.tencent.mm", "com.tencent.mm");

        card.modelClaimedDone();

        assertEquals(GoalCard.State.RUNNING, card.state());
        assertFalse(card.verified());
        assertEquals(GoalCard.Outcome.NOT_POSSIBLE, card.outcome());
        assertTrue(card.modelClaimObserved());
    }

    /** 6. 同一招第二次失败就换招；招数用尽才 failed。 */
    @Test public void repeatedFailureSwitchesTacticAndExhaustionFails() {
        Fake screen = new Fake();
        screen.nodes.add(node("n1", "微信", 40, 400, 240, 600));

        GoalCard card = new GoalMainLoop(screen, GoalPathStore.inMemory()).run("点一下微信");

        assertEquals(GoalCard.State.FAILED, card.state());
        assertEquals("TACTICS_EXHAUSTED", card.failureCode());
        int a11y = 0, center = 0;
        for (GoalCard.Evidence evidence : card.evidence()) {
            if (evidence.tactic.equals(GoalMainLoop.Tactic.A11Y_TAP.name())) a11y++;
            if (evidence.tactic.equals(GoalMainLoop.Tactic.CENTER_TAP.name())) center++;
        }
        assertTrue("无障碍点击最多两次", a11y <= 2);
        assertTrue("无障碍不行之后要换点中心", center >= 1);
        assertTrue(card.rounds() <= GoalMainLoop.MAX_ROUNDS);
    }

    /** 7. 关掉全部扩展，打开应用仍能跑完。 */
    @Test public void appOpenCompletesWithAllExtensionsDisabled() {
        Extensions extensions = new Extensions(null);
        for (Extensions.Kind kind : Extensions.Kind.values()) {
            assertFalse("扩展应当是关闭的：" + kind, extensions.available(kind));
            assertFalse(extensions.missing(kind).isEmpty());
        }

        Fake screen = new Fake();
        screen.pkg = "com.android.launcher3";
        screen.launchChanges = true;
        screen.afterLaunch = "com.tencent.mm";
        GoalPathStore store = GoalPathStore.inMemory();

        GoalCard card = new GoalMainLoop(screen, store).run("打开微信");

        assertEquals(GoalCard.State.SUCCEEDED, card.state());
        assertTrue(card.verified());
        assertEquals(GoalCard.Outcome.DONE, card.outcome());
        assertFalse("核对过的路径要存下来", store.load("app:com.tencent.mm").isEmpty());
    }
}
