package com.agentruntime.goal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次用户请求的目标卡。
 *
 * <p>它贯穿整件事，而不是"一次模型回合"：主循环按轮次推进，卡片记录状态、证据、
 * 已经核对成功的路径。状态只有 running / held / succeeded / failed / cancelled，
 * succeeded 必须 verified=true；结局只有 done / partial / not_possible。
 *
 * <p>付款、转账、下单支付进入 held：不是失败，等用户确认后可以继续同一条卡。
 */
public final class GoalCard {
    public enum State { RUNNING, HELD, SUCCEEDED, FAILED, CANCELLED }

    public enum Outcome { DONE, PARTIAL, NOT_POSSIBLE }

    public static final class Evidence {
        public final int round;
        public final String tactic;
        public final String code;
        public final String before;
        public final String after;
        public final String detail;

        Evidence(int round, String tactic, String code, String before, String after, String detail) {
            this.round = round;
            this.tactic = tactic;
            this.code = code;
            this.before = before;
            this.after = after;
            this.detail = detail;
        }

        public JSONObject toJson() throws Exception {
            return new JSONObject().put("round", round).put("tactic", tactic).put("code", code)
                    .put("before", before == null ? "" : before)
                    .put("after", after == null ? "" : after)
                    .put("detail", detail == null ? "" : detail);
        }
    }

    /** 已经核对成功的一步，用于下次重放。 */
    public static final class PathStep {
        public final String tactic;
        public final String target;
        public final String code;

        PathStep(String tactic, String target, String code) {
            this.tactic = tactic;
            this.target = target;
            this.code = code;
        }

        public JSONObject toJson() throws Exception {
            return new JSONObject().put("tactic", tactic).put("target", target).put("code", code);
        }
    }

    private final String id;
    private final String request;
    private final long createdAt = System.currentTimeMillis();
    private String criteria = "";
    private String targetPackage = "";
    private State state = State.RUNNING;
    private boolean verified;
    private String failureCode = "";
    private String foregroundAtFailure = "";
    private String heldReason = "";
    private boolean modelClaimedDone;
    private int rounds;
    private final List<Evidence> evidence = new ArrayList<>();
    private final List<PathStep> path = new ArrayList<>();

    public GoalCard(String id, String request) {
        this.id = id == null ? "goal" : id;
        this.request = request == null ? "" : request;
    }

    public String id() { return id; }
    public String request() { return request; }
    public long createdAt() { return createdAt; }

    public synchronized String criteria() { return criteria; }

    public synchronized String targetPackage() { return targetPackage; }

    public synchronized void criteria(String value, String targetPackage) {
        if (value != null && !value.trim().isEmpty()) criteria = value.trim();
        if (targetPackage != null) this.targetPackage = targetPackage;
    }

    public synchronized State state() { return state; }

    public synchronized boolean isTerminal() {
        return state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED;
    }

    public synchronized boolean verified() { return verified; }

    public synchronized String failureCode() { return failureCode; }

    public synchronized String foregroundAtFailure() { return foregroundAtFailure; }

    public synchronized String heldReason() { return heldReason; }

    public synchronized int rounds() { return rounds; }

    public synchronized void round() { rounds++; }

    public synchronized List<Evidence> evidence() {
        return Collections.unmodifiableList(new ArrayList<>(evidence));
    }

    public synchronized List<PathStep> path() {
        return Collections.unmodifiableList(new ArrayList<>(path));
    }

    synchronized void record(int round, String tactic, String code, String before, String after, String detail) {
        evidence.add(new Evidence(round, tactic, code, before, after, detail));
    }

    synchronized void recordPath(String tactic, String target, String code) {
        path.add(new PathStep(tactic, target, code));
    }

    synchronized void clearPath() { path.clear(); }

    /** 只有完成标准被核对通过时才允许 succeeded；没有证据一律拒绝。 */
    synchronized boolean succeed(String criteriaProof) {
        if (state != State.RUNNING) return false;
        if (evidence.isEmpty()) return false;
        if (criteriaProof == null || criteriaProof.trim().isEmpty()) return false;
        state = State.SUCCEEDED;
        verified = true;
        record(rounds, "VERIFY", "CRITERIA_MET", "", criteriaProof, criteriaProof);
        return true;
    }

    synchronized void fail(String code, String foregroundPackage) {
        if (isTerminal()) return;
        state = State.FAILED;
        verified = false;
        failureCode = code == null ? "FAILED" : code;
        foregroundAtFailure = foregroundPackage == null ? "" : foregroundPackage;
    }

    synchronized void hold(String reason) {
        if (isTerminal()) return;
        state = State.HELD;
        heldReason = reason == null ? "payment_confirmation" : reason;
    }

    synchronized void cancel() {
        if (isTerminal()) return;
        state = State.CANCELLED;
    }

    /** 模型嘴上说完成没有用：只记一笔，状态仍然是 running。 */
    synchronized void modelClaimedDone() {
        modelClaimedDone = true;
        record(rounds, "MODEL", "CLAIM_WITHOUT_EVIDENCE", "", "", "模型声明完成，但没有可核验的工具证据；回到第 2 步重新取证。");
    }

    public synchronized boolean modelClaimObserved() { return modelClaimedDone; }

    /** done / partial / not_possible；判不出来就是 not_possible。 */
    public synchronized Outcome outcome() {
        if (state == State.SUCCEEDED && verified) return Outcome.DONE;
        if (state == State.HELD) return Outcome.PARTIAL;
        if (state == State.RUNNING) return Outcome.NOT_POSSIBLE;
        boolean anyVerifiedStep = false;
        for (Evidence item : evidence) {
            if ("STEP_OK".equals(item.code)) { anyVerifiedStep = true; break; }
        }
        return anyVerifiedStep ? Outcome.PARTIAL : Outcome.NOT_POSSIBLE;
    }

    public synchronized JSONObject toJson() throws Exception {
        JSONArray evidenceJson = new JSONArray();
        for (Evidence item : evidence) evidenceJson.put(item.toJson());
        JSONArray pathJson = new JSONArray();
        for (PathStep step : path) pathJson.put(step.toJson());
        return new JSONObject().put("goal_id", id).put("request", request).put("criteria", criteria)
                .put("target_package", targetPackage).put("state", state.name())
                .put("verified", verified).put("outcome", outcome().name())
                .put("rounds", rounds).put("failure_code", failureCode)
                .put("foreground_at_failure", foregroundAtFailure)
                .put("held_reason", heldReason).put("evidence", evidenceJson).put("path", pathJson);
    }

    static List<PathStep> pathFromJson(JSONArray array) {
        List<PathStep> steps = new ArrayList<>();
        if (array == null) return steps;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            steps.add(new PathStep(item.optString("tactic"), item.optString("target"), item.optString("code")));
        }
        return steps;
    }

    synchronized void restorePath(List<PathStep> steps) {
        path.clear();
        path.addAll(steps);
    }
}
