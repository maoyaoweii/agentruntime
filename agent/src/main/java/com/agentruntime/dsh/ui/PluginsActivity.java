package com.agentruntime.dsh.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.*;
import com.agentruntime.AgentApp;
import com.agentruntime.dsh.DshGraph;
import com.agentruntime.dsh.plugin.MobilePlugin;

public final class PluginsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); DshGraph graph=AgentApp.get().dsh();
        LinearLayout page=UiKit.page(this,"插件与技能","选择助手可以使用的能力。新启用的工具从下一次发送开始生效；停用会停止该插件正在执行的任务。");
        TextView summary=UiKit.bold(this,"为手机而生的工具箱",23,UiKit.INK); page.addView(summary); UiKit.gap(page,20);
        for(MobilePlugin plugin:graph.plugins.plugins()) {
            LinearLayout card=UiKit.card(this),row=UiKit.row(this); LinearLayout title=UiKit.column(this);
            title.addView(UiKit.bold(this,plugin.title(),18,UiKit.INK)); title.addView(UiKit.text(this,plugin.tools().size()+" 个工具",12,UiKit.BLUE)); row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            Switch toggle=new Switch(this); toggle.setContentDescription("启用"+plugin.title()); toggle.setMinHeight(UiKit.dp(this,48)); toggle.setChecked(graph.plugins.enabled(plugin.id())); row.addView(toggle); card.addView(row);
            UiKit.gap(card,12); card.addView(UiKit.text(this,plugin.description(),14,UiKit.MUTED)); UiKit.gap(card,16);
            card.addView(UiKit.button(this,"查看工具  ›",false,()->{
                StringBuilder names=new StringBuilder(); for(MobilePlugin.Tool tool:plugin.tools()) names.append(tool.name).append("\n").append(tool.description).append(tool.confirm?" · 每次确认":"").append("\n\n");
                new AlertDialog.Builder(this).setTitle(plugin.title()).setMessage(names.toString().trim()).setPositiveButton("知道了",null).show();
            }),UiKit.matchWrap());
            toggle.setOnCheckedChangeListener((button,checked)->{
                try { graph.plugins.setEnabled(plugin.id(),checked); }
                catch(Exception e) { toggle.setChecked(graph.plugins.enabled(plugin.id())); UiKit.toast(this,"插件设置未保存，请重试"); }
            }); page.addView(card,UiKit.matchWrap()); UiKit.gap(page,14);
        }
        UiKit.gap(page,8); page.addView(UiKit.button(this,"浏览全部手机技能  ↗",false,()->UiKit.open(this,com.agentruntime.ui.SkillsActivity.class)),UiKit.matchWrap());
        UiKit.gap(page,18); page.addView(UiKit.text(this,"当前使用随应用安装的 Android 插件。桌面版 DSH 的 Node / Cordis 插件需要专门移植后才能运行。",12,UiKit.MUTED));
    }
}
