/*
 * Inspired by Mika (Apache-2.0, https://github.com/wangzan101/mika)
 */
package com.agentruntime.goal;

import com.agentruntime.state.UiNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 主循环看世界的方式。
 *
 * <p>刻意做窄：读一屏、按包名启动、点节点/坐标、让出前台、看图识字、查虚拟显示。
 * 测试用假实现，真机用 {@link DeviceGoalScreen}。主循环不直接碰 Android API，
 * 这样"同一棵桌面树、点完签名没变"这类判定可以被单测精确复现。
 */
public interface GoalScreen {

    /** 当前这一屏。 */
    Facts read();

    /** 从无障碍服务 Context 启动；返回是否发出成功（不代表已经到前台）。 */
    boolean launchByPackage(String packageName);

    /** 无障碍节点点击。 */
    boolean tapNode(UiNode node);

    /** 点节点中心（无障碍点击无效时的下一步）。 */
    boolean tapCenter(UiNode node);

    boolean tapPoint(int x, int y);

    /** 把聊天界面让到后台，让目标应用回到前台。 */
    boolean yieldForeground();

    /** 截图 + 本机文字识别，同一轮里完成。 */
    OcrResult readImage();

    /** 虚拟显示是否可用：可用时同一条 GoalCard 可以在后台继续。 */
    boolean virtualDisplayAvailable();

    /** 可见名称能不能唯一对应到已安装应用；不能就返回 null。 */
    String resolvePackage(String visibleLabel);

    String selfPackage();

    final class OcrItem {
        public final String text;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public OcrItem(String text, int left, int top, int right, int bottom) {
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int centerX() { return (left + right) / 2; }

        public int centerY() { return (top + bottom) / 2; }

        public int area() { return Math.max(0, right - left) * Math.max(0, bottom - top); }
    }

    final class OcrResult {
        public final boolean ok;
        public final String code;
        public final List<OcrItem> items;

        public OcrResult(boolean ok, String code, List<OcrItem> items) {
            this.ok = ok;
            this.code = code;
            this.items = items == null ? Collections.emptyList() : items;
        }

        public static OcrResult unavailable(String code) {
            return new OcrResult(false, code, Collections.emptyList());
        }
    }

    /** 一屏的不可变快照。签名用于判断"这一下到底有没有改变画面"。 */
    final class Facts {
        public final String packageName;
        public final String windowTitle;
        public final List<UiNode> nodes;
        public final List<OcrItem> ocrItems;
        public final String signature;
        public final String visibleText;

        public Facts(String packageName, String windowTitle, List<UiNode> nodes, List<OcrItem> ocrItems) {
            this.packageName = packageName == null ? "" : packageName;
            this.windowTitle = windowTitle == null ? "" : windowTitle;
            this.nodes = nodes == null ? Collections.emptyList() : new ArrayList<>(nodes);
            this.ocrItems = ocrItems == null ? Collections.emptyList() : new ArrayList<>(ocrItems);
            StringBuilder text = new StringBuilder();
            StringBuilder signature = new StringBuilder(this.packageName).append('|').append(this.windowTitle);
            for (UiNode node : this.nodes) {
                String label = node.text != null && !node.text.trim().isEmpty()
                        ? node.text.trim() : node.contentDescription;
                if (label != null && !label.trim().isEmpty()) text.append(label.trim()).append('\u0001');
                signature.append('|').append(node.className).append(':').append(label)
                        .append(':').append(node.left).append(',').append(node.top)
                        .append(',').append(node.right).append(',').append(node.bottom)
                        .append(':').append(node.clickable ? 1 : 0);
            }
            for (OcrItem item : this.ocrItems) {
                text.append(item.text).append('\u0001');
                signature.append("|ocr:").append(item.text).append(':')
                        .append(item.left).append(',').append(item.top)
                        .append(',').append(item.right).append(',').append(item.bottom);
            }
            this.visibleText = text.toString();
            this.signature = signature.toString();
        }

        /** 树为空或几乎没有字：这一轮就要改看图，而不是等用户去开开关。 */
        public boolean sparse() {
            return nodes.size() <= 2 && visibleText.trim().length() < 12;
        }

        public boolean hasText(String needle) {
            return needle != null && !needle.isEmpty() && visibleText.contains(needle);
        }

        public List<UiNode> clickableNamed(String label) {
            List<UiNode> hits = new ArrayList<>();
            if (label == null || label.isEmpty()) return hits;
            for (UiNode node : nodes) {
                if (!node.clickable || !node.visible) continue;
                boolean match = label.equals(node.text) || label.equals(node.contentDescription);
                boolean contains = (node.text != null && node.text.contains(label))
                        || (node.contentDescription != null && node.contentDescription.contains(label));
                if (match || contains) hits.add(node);
            }
            return hits;
        }
    }
}
