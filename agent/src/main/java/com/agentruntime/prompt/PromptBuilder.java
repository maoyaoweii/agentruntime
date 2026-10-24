package com.agentruntime.prompt;

import com.agentruntime.env.EnvironmentSnapshot;

import java.util.List;

/**
 * 提示词组装器：把「系统约束 + 环境 + 技能清单 + 不可信屏幕数据 + 目标」拼成一次完整的模型输入。
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>约束放系统侧</b>——用户侧只放数据，避免"用户说什么模型就当规则"；</li>
 *   <li><b>环境必须显式注入</b>——屏幕尺寸、语言、深色模式直接影响动作是否成立；</li>
 *   <li><b>屏幕文本一律走不可信通道</b>——经 {@link PromptGuard} 清洗后再拼；</li>
 *   <li><b>整体有预算</b>——超长按头尾保留裁剪，防止上下文被单条长文本吃光。</li>
 * </ul>
 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    /** 一次模型调用的完整输入。 */
    public static final class Bundle {
        public final String systemPrompt;
        public final String userPrompt;
        public final String version;
        public final int approxChars;
        public final int suspiciousHits;

        Bundle(String system, String user, int suspiciousHits) {
            this.systemPrompt = system;
            this.userPrompt = user;
            this.version = PromptLibrary.PROMPT_VERSION;
            this.approxChars = system.length() + user.length();
            this.suspiciousHits = suspiciousHits;
        }

        /** 粗略 token 估算：中文按 1 字 1 token、英文按 4 字符 1 token 折中的保守值。 */
        public int approxTokens() {
            return approxChars / 2;
        }
    }

    // ------------------------------------------------------------- planner

    /**
     * 规划提示词：自然语言目标 → Task DSL。
     *
     * @param goal         用户目标（可信，来自调用方）
     * @param env          当前环境快照
     * @param skillIds     可用技能 ID 列表
     * @param allowedApps  允许操作的应用包名
     * @param contextNotes 额外上下文（可为 null），例如上轮失败原因
     */
    public static Bundle buildPlanner(String goal, EnvironmentSnapshot env,
                                      List<String> skillIds, List<String> allowedApps,
                                      String contextNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append(env != null ? env.toPromptBlock() : "<environment/>").append("\n\n");
        sb.append(buildSkillsBlock(skillIds)).append("\n");
        sb.append("<allowed_apps>\n");
        if (allowedApps != null) {
            for (String p : allowedApps) sb.append("- ").append(p).append('\n');
        }
        sb.append("</allowed_apps>\n\n");
        sb.append("<goal>\n").append(PromptGuard.sanitizeUntrusted(goal)).append("\n</goal>\n");

        if (contextNotes != null && !contextNotes.trim().isEmpty()) {
            sb.append("\n<previous_attempt>\n")
                    .append(PromptGuard.sanitizeUntrusted(contextNotes))
                    .append("\n</previous_attempt>\n")
                    .append("上一轮失败过，不要重复同样的步骤。\n");
        }

        sb.append('\n').append(PromptLibrary.PLANNER_EXAMPLE);

        String user = PromptGuard.enforceBudget(sb.toString(), PromptGuard.MAX_PROMPT_CHARS);
        int hits = PromptGuard.suspiciousHits(goal).size()
                + (contextNotes == null ? 0 : PromptGuard.suspiciousHits(contextNotes).size());
        return new Bundle(PromptLibrary.PLANNER_SYSTEM, user, hits);
    }

    // ----------------------------------------------------------- grounding

    /**
     * 视觉定位提示词：截图 + 目标描述 → 坐标。
     *
     * @param env       环境快照（提供屏幕尺寸，模型据此输出正确量级的坐标）
     * @param target    要定位的目标描述（例如"发送按钮"）
     * @param nodeDigest 无障碍树摘要（可为 null），给模型一个候选参考
     */
    public static Bundle buildGrounding(EnvironmentSnapshot env, String target, String nodeDigest) {
        StringBuilder sb = new StringBuilder();
        sb.append(env != null ? env.toPromptBlock() : "<environment/>").append("\n\n");
        sb.append("<target>\n").append(PromptGuard.sanitizeUntrusted(target)).append("\n</target>\n");

        if (nodeDigest != null && !nodeDigest.trim().isEmpty()) {
            sb.append('\n').append(PromptGuard.wrapUntrusted("accessibility_tree", nodeDigest)).append('\n')
                    .append("以上是无障碍树摘要，仅作参考；坐标必须以截图为准。\n");
        }

        sb.append("\n请输出该目标在截图中的可点击中心点。");
        String user = PromptGuard.enforceBudget(sb.toString(), PromptGuard.MAX_PROMPT_CHARS);
        int hits = PromptGuard.suspiciousHits(target).size()
                + (nodeDigest == null ? 0 : PromptGuard.suspiciousHits(nodeDigest).size());
        return new Bundle(PromptLibrary.GROUNDING_SYSTEM, user, hits);
    }

    // ------------------------------------------------------------- decider

    /**
     * 决策提示词：当前状态 + 目标 + 历史 → 下一个动作。
     *
     * @param env        环境快照
     * @param goal       任务目标
     * @param screenData 当前界面文本（不可信，走清洗通道）
     * @param history    已执行动作摘要（最近 5 条即可，多了浪费预算）
     * @param stepIndex  当前步序号，让模型知道自己还剩多少预算
     * @param budget     步数预算
     */
    public static Bundle buildDecider(EnvironmentSnapshot env, String goal, String screenData,
                                      List<String> history, int stepIndex, int budget,
                                      List<String> actionWhitelist) {
        StringBuilder sb = new StringBuilder();
        sb.append(env != null ? env.toPromptBlock() : "<environment/>").append("\n\n");
        sb.append("<goal>\n").append(PromptGuard.sanitizeUntrusted(goal)).append("\n</goal>\n\n");
        sb.append("<progress>step ").append(stepIndex).append(" / ").append(budget).append("</progress>\n\n");

        sb.append("<actions>\n");
        if (actionWhitelist != null) {
            for (String a : actionWhitelist) sb.append("- ").append(a).append('\n');
        }
        sb.append("</actions>\n\n");

        if (history != null && !history.isEmpty()) {
            sb.append("<history>\n");
            int from = Math.max(0, history.size() - 5);
            for (int i = from; i < history.size(); i++) {
                sb.append(i + 1).append(". ").append(PromptGuard.sanitizeUntrusted(history.get(i))).append('\n');
            }
            sb.append("</history>\n\n");
        }

        sb.append(PromptGuard.wrapUntrusted("current_screen", screenData));
        sb.append("\n\n请输出下一步动作。");

        String user = PromptGuard.enforceBudget(sb.toString(), PromptGuard.MAX_PROMPT_CHARS);
        int hits = PromptGuard.suspiciousHits(goal).size()
                + PromptGuard.suspiciousHits(screenData).size();
        return new Bundle(PromptLibrary.DECIDER_SYSTEM, user, hits);
    }

    // --------------------------------------------------------------- util

    static String buildSkillsBlock(List<String> skillIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(PromptLibrary.SKILLS_TAG).append(">\n");
        if (skillIds == null || skillIds.isEmpty()) {
            sb.append("（无可用技能）\n");
        } else {
            for (String id : skillIds) {
                sb.append("- ").append(id).append('\n');
            }
        }
        sb.append("</").append(PromptLibrary.SKILLS_TAG).append(">");
        return sb.toString();
    }
}
