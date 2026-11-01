/*
 * Inspired by Mika (Apache-2.0, https://github.com/wangzan101/mika)
 */
package com.agentruntime.goal;

import com.agentruntime.state.UiNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 主循环：把用户交代的一件事做完。
 *
 * <p>不是"一次模型回合"，也不按固定步数切断。每一轮都走同一条顺序：
 * <ol>
 *   <li>先有可观察的完成标准，没有就先把用户这句话写成标准；</li>
 *   <li>看清眼前（前台包名 + 无障碍树；树空就在同一轮改看图）；</li>
 *   <li>选一个动作（有核对过的路径优先重放）；</li>
 *   <li>做一个会改变画面的动作，然后必须重新取证，没变化不算成功；</li>
 *   <li>失败换招，换招的顺序固定在 {@link Tactic} 里，不交给模型自由发挥；</li>
 *   <li>同一招对同一目标连续两次无效就停用；招数用尽才 failed；</li>
 *   <li>标准满足就停，并把这次核对过的路径存下来。</li>
 * </ol>
 *
 * <p>付费、转账、下单支付进入 held：不是失败，等用户确认后同一条卡可以继续。
 * Ask / Autonomous 不是两套循环，区别只在 Ask 遇到付款时 held。
 */
public final class GoalMainLoop {
    public static final int MAX_ROUNDS = 40;

    /** 招式顺序固定：改这里等于改"失败之后先试什么"。 */
    public enum Tactic {
        SAVED_PATH, LAUNCH_BY_PACKAGE, YIELD_AND_LAUNCH, A11Y_TAP, CENTER_TAP, OCR_TAP, ENVIRONMENT
    }

    public interface Listener {
        void onRound(int round, Tactic tactic, String code);

        void onState(GoalCard card);
    }

    private static final Set<String> LAUNCHER_HINTS = new HashSet<>(Arrays.asList(
            ".home", "launcher", "lawnchair", "trebuchet", "quickstep"));

    private final GoalScreen screen;
    private final GoalInterpreter interpreter;
    private final GoalPathStore pathStore;
    private final Listener listener;

    public GoalMainLoop(GoalScreen screen, GoalPathStore pathStore, Listener listener) {
        this.screen = screen;
        this.pathStore = pathStore;
        this.interpreter = new GoalInterpreter();
        this.listener = listener;
    }

    public GoalMainLoop(GoalScreen screen, GoalPathStore pathStore) {
        this(screen, pathStore, null);
    }

    /** launcher / .home / lawnchair / trebuchet / quickstep 都算桌面。 */
    public static boolean isLauncher(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        String lower = packageName.toLowerCase(Locale.ROOT);
        if ("com.miui.home".equals(lower)) return true;
        for (String hint : LAUNCHER_HINTS) if (lower.contains(hint)) return true;
        return false;
    }

    public GoalCard run(String request) {
        GoalCard card = new GoalCard("goal-" + Long.toHexString(System.currentTimeMillis()), request);
        GoalInterpreter.Plan plan = interpreter.interpret(request, screen);
        card.criteria(plan.criteria, plan.targetPackage);
        if (plan.payment) {
            card.hold("payment_confirmation");
            notifyState(card);
            return card;
        }
        if (plan.question) {
            // 问能不能、解释、假设：不打开应用，也不假装做过。
            card.fail("NOT_AN_ACTION", screen.read().packageName);
            notifyState(card);
            return card;
        }
        if (card.criteria().isEmpty()) {
            card.fail("NO_CRITERIA", screen.read().packageName);
            notifyState(card);
            return card;
        }
        List<GoalCard.PathStep> saved = pathStore == null ? new ArrayList<>() : pathStore.load(plan.key);
        card.restorePath(saved);
        int pathCursor = 0;
        Set<Tactic> disabled = new LinkedHashSet<>();
        int[] strikes = new int[Tactic.values().length];

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            if (card.state() == GoalCard.State.CANCELLED || card.state() == GoalCard.State.HELD) break;
            card.round();
            GoalScreen.Facts before = observe(plan);

            String proof = verify(plan, before);
            if (proof != null) {
                card.succeed(proof);
                savePath(card, plan);
                notifyState(card);
                return card;
            }

            Tactic tactic = choose(before, plan, disabled, strikes, pathCursor < card.path().size());
            if (tactic == null) {
                card.fail("TACTICS_EXHAUSTED", before.packageName);
                notifyState(card);
                return card;
            }

            Step step = execute(tactic, before, plan, card, pathCursor);
            notify(round, tactic, step.code);
            if (step.yielded) {
                // 让出前台只做一次：再让一次也不会变，下一轮改用包名启动。
                strikes[tactic.ordinal()]++;
                if (strikes[tactic.ordinal()] >= 1) disabled.add(tactic);
                continue;
            }
            if (!step.executed) {
                disabled.add(tactic);
                continue;
            }

            GoalScreen.Facts after = observe(plan);
            boolean changed = !after.signature.equals(before.signature);
            if (tactic == Tactic.LAUNCH_BY_PACKAGE) {
                changed = plan.targetPackage != null && plan.targetPackage.equals(after.packageName);
                if (!changed) step.code = "LAUNCH_NOT_FOREGROUND";
            }
            if (!changed) {
                strikes[tactic.ordinal()]++;
                card.record(round, tactic.name(), step.code + "_NO_EFFECT",
                        before.packageName, after.packageName, step.detail);
                if (strikes[tactic.ordinal()] >= 2) disabled.add(tactic);
                // 重放某步核对失败：立刻回到现选，不把旧路径跑完还报成功。
                if (tactic == Tactic.SAVED_PATH) {
                    card.clearPath();
                    pathCursor = 0;
                    disabled.add(Tactic.SAVED_PATH);
                }
                if (disabled.contains(Tactic.A11Y_TAP) && !disabled.contains(Tactic.CENTER_TAP)) {
                    // 无障碍点击没改变画面：下一招是点同一个节点的中心。
                }
                if (tactic == Tactic.LAUNCH_BY_PACKAGE) disabled.add(Tactic.A11Y_TAP);
                if (allDisabled(disabled)) {
                    card.fail("TACTICS_EXHAUSTED", after.packageName);
                    notifyState(card);
                    return card;
                }
                continue;
            }

            strikes[tactic.ordinal()] = 0;
            card.record(round, tactic.name(), "STEP_OK", before.packageName, after.packageName, step.detail);
            card.recordPath(tactic.name(), step.target, "STEP_OK");
            if (tactic == Tactic.SAVED_PATH) pathCursor++;
            String afterProof = verify(plan, after);
            if (afterProof != null) {
                card.succeed(afterProof);
                savePath(card, plan);
                notifyState(card);
                return card;
            }
            if (tactic == Tactic.OCR_TAP || tactic == Tactic.A11Y_TAP || tactic == Tactic.CENTER_TAP) {
                // 画面变了但还没到目标：这一招已经用掉，交给下一轮重新选。
                disabled.add(tactic);
            }
        }
        if (card.state() == GoalCard.State.RUNNING) card.fail("TACTICS_EXHAUSTED", screen.read().packageName);
        notifyState(card);
        return card;
    }

    /** 第 2 步：看清眼前；树空或几乎没有字，就在同一轮里看图。 */
    private GoalScreen.Facts observe(GoalInterpreter.Plan plan) {
        GoalScreen.Facts facts = screen.read();
        if (!facts.sparse()) return facts;
        GoalScreen.OcrResult ocr = screen.readImage();
        if (!ocr.ok || ocr.items.isEmpty()) return facts;
        String text = "";
        for (GoalScreen.OcrItem item : ocr.items) text += item.text + "\u0001";
        // 同一轮改用识别到的文字，而不是停下来叫用户去开开关。
        return new GoalScreen.Facts(facts.packageName, facts.windowTitle, facts.nodes, ocr.items);
    }

    private Tactic choose(GoalScreen.Facts before, GoalInterpreter.Plan plan,
                          Set<Tactic> disabled, int[] strikes, boolean hasSavedStep) {
        if (hasSavedStep && !disabled.contains(Tactic.SAVED_PATH)) return Tactic.SAVED_PATH;
        boolean self = screen.selfPackage() != null && screen.selfPackage().equals(before.packageName);
        if (self) {
            if (!disabled.contains(Tactic.YIELD_AND_LAUNCH) && plan.targetPackage != null) {
                return Tactic.YIELD_AND_LAUNCH;
            }
            // 让出失败也要继续：按包名启动并核对，而不是停下来假装已经在后台。
            if (plan.targetPackage != null && !disabled.contains(Tactic.LAUNCH_BY_PACKAGE)
                    && strikes[Tactic.LAUNCH_BY_PACKAGE.ordinal()] == 0) {
                return Tactic.LAUNCH_BY_PACKAGE;
            }
            if (!disabled.contains(Tactic.ENVIRONMENT)) return Tactic.ENVIRONMENT;
            return null;
        }
        if (isLauncher(before.packageName) && plan.targetPackage != null
                && !disabled.contains(Tactic.LAUNCH_BY_PACKAGE)) {
            return Tactic.LAUNCH_BY_PACKAGE;
        }
        UiNode node = pickTargetNode(before, plan);
        if (node != null && !disabled.contains(Tactic.A11Y_TAP) && strikes[Tactic.A11Y_TAP.ordinal()] == 0) {
            return Tactic.A11Y_TAP;
        }
        if (node != null && !disabled.contains(Tactic.CENTER_TAP) && strikes[Tactic.CENTER_TAP.ordinal()] == 0
                && strikes[Tactic.A11Y_TAP.ordinal()] > 0) {
            return Tactic.CENTER_TAP;
        }
        if (before.sparse() && !before.ocrItems.isEmpty() && !disabled.contains(Tactic.OCR_TAP)
                && strikes[Tactic.OCR_TAP.ordinal()] == 0) {
            return Tactic.OCR_TAP;
        }
        if (plan.targetPackage != null && !disabled.contains(Tactic.LAUNCH_BY_PACKAGE)
                && strikes[Tactic.LAUNCH_BY_PACKAGE.ordinal()] == 0) {
            return Tactic.LAUNCH_BY_PACKAGE;
        }
        if (!disabled.contains(Tactic.ENVIRONMENT)) return Tactic.ENVIRONMENT;
        return null;
    }

    private Step execute(Tactic tactic, GoalScreen.Facts before, GoalInterpreter.Plan plan,
                         GoalCard card, int pathCursor) {
        Step step = new Step();
        switch (tactic) {
            case SAVED_PATH: {
                GoalCard.PathStep saved = card.path().get(pathCursor);
                step.target = saved.target;
                try {
                    Tactic recorded = Tactic.valueOf(saved.tactic);
                    Step replayed = execute(recorded, before, plan, card, pathCursor);
                    replayed.code = "REPLAY_" + replayed.code;
                    return replayed;
                } catch (IllegalArgumentException e) {
                    step.code = "PATH_INVALID";
                    return step;
                }
            }
            case LAUNCH_BY_PACKAGE: {
                if (plan.targetPackage == null) {
                    step.code = "NO_TARGET_PACKAGE";
                    return step;
                }
                if (screen.selfPackage() != null && screen.selfPackage().equals(before.packageName)) {
                    screen.yieldForeground();
                }
                boolean sent = screen.launchByPackage(plan.targetPackage);
                step.executed = true;
                step.target = plan.targetPackage;
                step.code = sent ? "LAUNCH_SENT" : "LAUNCH_FAILED";
                GoalScreen.Facts first = screen.read();
                if (plan.targetPackage.equals(first.packageName)) {
                    step.code = "LAUNCH_OK";
                } else {
                    GoalScreen.Facts second = screen.read();
                    step.code = plan.targetPackage.equals(second.packageName) ? "LAUNCH_OK" : "LAUNCH_NOT_FOREGROUND";
                    step.detail = "foreground=" + second.packageName;
                }
                return step;
            }
            case YIELD_AND_LAUNCH: {
                boolean yielded = screen.yieldForeground();
                step.yielded = true;
                step.executed = yielded;
                step.code = yielded ? "YIELDED" : "YIELD_FAILED";
                if (!yielded && !screen.virtualDisplayAvailable()) {
                    step.code = "ENVIRONMENT";
                    step.detail = "无法让出前台，且没有可用的虚拟显示；下一步只能用包名启动再核对。";
                }
                return step;
            }
            case A11Y_TAP: {
                UiNode node = pickTargetNode(before, plan);
                if (node == null) {
                    step.code = "NO_TARGET_NODE";
                    return step;
                }
                step.executed = true;
                step.target = label(node);
                step.code = screen.tapNode(node) ? "TAP_SENT" : "TAP_FAILED";
                return step;
            }
            case CENTER_TAP: {
                UiNode node = pickTargetNode(before, plan);
                if (node == null) {
                    step.code = "NO_TARGET_NODE";
                    return step;
                }
                int area = Math.max(0, node.right - node.left) * Math.max(0, node.bottom - node.top);
                int screenArea = 1080 * 2400;
                if (area * 3 > screenArea) {
                    step.code = "TARGET_TOO_LARGE";
                    return step;
                }
                step.executed = true;
                step.target = label(node);
                step.code = screen.tapCenter(node) ? "CENTER_TAP_SENT" : "CENTER_TAP_FAILED";
                return step;
            }
            case OCR_TAP: {
                GoalScreen.OcrItem item = pickOcrItem(before, plan);
                if (item == null) {
                    step.code = "OCR_TARGET_MISSING";
                    return step;
                }
                step.executed = true;
                step.target = item.text;
                step.code = screen.tapPoint(item.centerX(), item.centerY()) ? "OCR_TAP_SENT" : "OCR_TAP_FAILED";
                return step;
            }
            case ENVIRONMENT:
            default: {
                step.code = "ENVIRONMENT";
                step.detail = "缺少可用路径：前台=" + before.packageName
                        + (screen.virtualDisplayAvailable() ? "" : "；虚拟显示不可用");
                return step;
            }
        }
    }

    private GoalScreen.OcrItem pickOcrItem(GoalScreen.Facts facts, GoalInterpreter.Plan plan) {
        if (plan.targetLabel == null) return null;
        GoalScreen.OcrItem best = null;
        for (GoalScreen.OcrItem item : facts.ocrItems) {
            if (item.text == null) continue;
            if (!item.text.contains(plan.targetLabel)) continue;
            if (best == null || item.area() > best.area()) best = item;
        }
        return best;
    }

    /** 多个同名可点节点：只点面积最小的那个。 */
    UiNode pickTargetNode(GoalScreen.Facts facts, GoalInterpreter.Plan plan) {
        if (plan.targetLabel == null) return null;
        List<UiNode> hits = new ArrayList<>();
        for (UiNode node : facts.nodes) {
            if (!node.visible || !node.clickable) continue;
            String label = label(node);
            if (label.isEmpty()) continue;
            if (label.equals(plan.targetLabel) || label.contains(plan.targetLabel)) hits.add(node);
        }
        UiNode best = null;
        for (UiNode node : hits) {
            if (best == null || area(node) < area(best)) best = node;
        }
        return best;
    }

    private static int area(UiNode node) {
        return Math.max(0, node.right - node.left) * Math.max(0, node.bottom - node.top);
    }

    private static String label(UiNode node) {
        if (node.text != null && !node.text.trim().isEmpty()) return node.text.trim();
        return node.contentDescription == null ? "" : node.contentDescription.trim();
    }

    /** 完成标准只认核对过的证据，不认模型的话。 */
    private String verify(GoalInterpreter.Plan plan, GoalScreen.Facts facts) {
        if (plan.targetPackage != null && plan.targetPackage.equals(facts.packageName)) {
            return "前台包名=" + facts.packageName;
        }
        if (plan.targetPackage == null && plan.targetLabel != null && facts.hasText(plan.targetLabel)) {
            // 只知道名字时，桌面上那个图标本身也算"屏幕上出现"，所以必须同时离开桌面/自己的窗口。
            boolean leftLauncher = !isLauncher(facts.packageName)
                    && !facts.packageName.equals(screen.selfPackage());
            if (leftLauncher) return "已离开桌面，且屏幕出现「" + plan.targetLabel + "」（前台=" + facts.packageName + "）";
        }
        return null;
    }

    private boolean allDisabled(Set<Tactic> disabled) {
        for (Tactic tactic : Tactic.values()) {
            if (tactic == Tactic.SAVED_PATH) continue;
            if (!disabled.contains(tactic)) return false;
        }
        return true;
    }

    private void savePath(GoalCard card, GoalInterpreter.Plan plan) {
        if (pathStore == null) return;
        try {
            pathStore.save(plan.key, card.path());
        } catch (RuntimeException ignored) {
        }
    }

    private void notify(int round, Tactic tactic, String code) {
        if (listener == null) return;
        try {
            listener.onRound(round, tactic, code);
        } catch (RuntimeException ignored) {
        }
    }

    private void notifyState(GoalCard card) {
        if (listener == null) return;
        try {
            listener.onState(card);
        } catch (RuntimeException ignored) {
        }
    }

    static final class Step {
        boolean executed;
        boolean yielded;
        String code = "OK";
        String target = "";
        String detail = "";
    }
}
