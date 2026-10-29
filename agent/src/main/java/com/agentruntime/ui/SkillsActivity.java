package com.agentruntime.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.TextView;

import com.agentruntime.AgentApp;
import com.agentruntime.agent.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 技能库：把 assets/skills 里的技能摊开给人看。
 *
 * <p>为什么要这个页面：技能是这套系统的实际能力边界。用户得知道"它到底会做什么"，
 * 而不是靠试错发现。每个技能显示 ID、风险等级、参数、后置断言、步骤数。
 */
public final class SkillsActivity extends Activity {

    private LinearLayout container;
    private List<JSONObject> skills;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_skills);
        UiKit.applyStatusBarPadding(findViewById(R.id.top_bar));
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        container = findViewById(R.id.skill_container);
        skills = loadSkills();
        EditText search = findViewById(R.id.et_skill_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderSkills(s.toString());
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        renderSkills("");
    }

    private void renderSkills(String query) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        int count = 0;
        for (JSONObject s : skills) {
            String risk = s.optString("risk", "LOW").toUpperCase(Locale.ROOT);
            String riskLabel = "HIGH".equals(risk) ? "高风险" : "MEDIUM".equals(risk) ? "中风险" : "低风险";
            String searchable = s.optString("skill_id") + " " + s.optString("target_package") + " " + risk + " " + riskLabel;
            if (!searchable.toLowerCase(Locale.ROOT).contains(normalized)) continue;
            container.addView(buildRow(inflater, s));
            count++;
        }
        ((TextView) findViewById(R.id.tv_skill_total)).setText(count + " / " + skills.size() + " 项");
        if (count == 0) {
            TextView tv = new TextView(this);
            tv.setText(skills.isEmpty() ? "暂时没有可用技能" : "没有找到匹配的技能，试试其他关键词。");
            tv.setTextColor(getColor(R.color.text_tertiary));
            container.addView(tv);
        }
    }

    private View buildRow(LayoutInflater inflater, JSONObject s) {
        View row = inflater.inflate(R.layout.item_skill, container, false);
        String id = s.optString("skill_id", "?");
        ((TextView) row.findViewById(R.id.item_skill_id)).setText(id);

        String risk = s.optString("risk", "LOW").toUpperCase(Locale.ROOT);
        TextView riskView = row.findViewById(R.id.item_skill_risk);
        if ("HIGH".equals(risk)) {
            UiKit.setBadge(riskView, "高风险", UiKit.Badge.DANGER);
        } else if ("MEDIUM".equals(risk)) {
            UiKit.setBadge(riskView, "中风险", UiKit.Badge.WARN);
        } else {
            UiKit.setBadge(riskView, "低风险", UiKit.Badge.OK);
        }

        int steps = s.optJSONArray("steps") == null ? 0 : s.optJSONArray("steps").length();
        String target = s.optString("target_package", "");
        StringBuilder meta = new StringBuilder();
        meta.append("v").append(s.optString("version", "1.0"))
                .append("  ·  ").append(steps).append(" 步");
        JSONArray args = s.optJSONArray("args");
        if (args != null && args.length() > 0) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < args.length(); i++) names.add(args.optString(i));
            meta.append("  ·  参数 ").append(String.join(", ", names));
        } else {
            meta.append("  ·  无参数");
        }
        if (!target.isEmpty()) meta.append("\n目标 ").append(target);
        ((TextView) row.findViewById(R.id.item_skill_meta)).setText(meta.toString());

        JSONArray post = s.optJSONArray("postconditions");
        TextView postView = row.findViewById(R.id.item_skill_post);
        if (post != null && post.length() > 0) {
            List<String> ps = new ArrayList<>();
            for (int i = 0; i < post.length(); i++) ps.add(post.optString(i));
            postView.setText("校验 " + String.join(" / ", ps));
            postView.setVisibility(View.VISIBLE);
        } else {
            postView.setVisibility(View.GONE);
        }

        TextView runBtn = row.findViewById(R.id.item_skill_run);
        boolean hasRequiredArgs = args != null && args.length() > 0;
        if (hasRequiredArgs) {
            runBtn.setText("去控制台描述任务 →");
            runBtn.setOnClickListener(v -> startActivity(new Intent(this, ConsoleActivity.class)));
        } else {
            runBtn.setOnClickListener(v -> {
                Intent i = new Intent(this, ConsoleActivity.class);
                i.putExtra(ConsoleActivity.EXTRA_SKILL, id);
                startActivity(i);
            });
        }
        return row;
    }

    private List<JSONObject> loadSkills() {
        List<JSONObject> out = new ArrayList<>();
        try {
            String[] files = getAssets().list("skills");
            if (files == null) return out;
            List<String> names = new ArrayList<>();
            Collections.addAll(names, files);
            Collections.sort(names);
            for (String f : names) {
                if (!f.endsWith(".json")) continue;
                try {
                    out.add(new JSONObject(readAsset("skills/" + f)));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private String readAsset(String path) throws Exception {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
