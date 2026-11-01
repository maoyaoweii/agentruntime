package com.agentruntime.dsh.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import com.agentruntime.AgentApp;
import com.agentruntime.dsh.DshGraph;
import com.agentruntime.dsh.plugin.MobilePlugin;

public final class PluginsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); DshGraph graph=AgentApp.get().dsh();
        LinearLayout page=UiKit.page(this,"插件与技能","选择助手可以使用的能力");
        for(MobilePlugin plugin:graph.plugins.plugins()) {
            LinearLayout card=UiKit.card(this),row=UiKit.row(this); UiKit.pad(card,14,12,14,12);
            DshIcons.Name icon="device".equals(plugin.id())?DshIcons.Name.PHONE:"workspace".equals(plugin.id())?DshIcons.Name.FOLDER:DshIcons.Name.GRID;
            row.addView(UiKit.iconBadge(this,icon),new LinearLayout.LayoutParams(UiKit.dp(this,44),UiKit.dp(this,44)));
            LinearLayout title=UiKit.column(this); LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1); titleParams.setMarginStart(UiKit.dp(this,12));
            title.addView(UiKit.bold(this,plugin.title(),16,UiKit.INK)); title.addView(UiKit.text(this,plugin.tools().size()+" 个工具",12,UiKit.MUTED)); row.addView(title,titleParams);
            Switch toggle=UiKit.toggle(this,"启用"+plugin.title(),graph.plugins.enabled(plugin.id())); row.addView(toggle); card.addView(row);
            UiKit.gap(card,5); TextView description=UiKit.text(this,plugin.description(),12,UiKit.MUTED); LinearLayout.LayoutParams descParams=UiKit.matchWrap(); descParams.setMarginStart(UiKit.dp(this,56)); card.addView(description,descParams);
            LinearLayout actions=UiKit.row(this); actions.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
            actions.addView(UiKit.tonalButton(this,"查看工具",DshIcons.Name.RIGHT,()->{
                StringBuilder names=new StringBuilder(); for(MobilePlugin.Tool tool:plugin.tools()) names.append(tool.name).append("\n").append(tool.description).append(tool.confirm?" · 每次确认":"").append("\n\n");
                new AlertDialog.Builder(this).setTitle(plugin.title()).setMessage(names.toString().trim()).setPositiveButton("知道了",null).show();
            }),new LinearLayout.LayoutParams(-2,UiKit.dp(this,48))); card.addView(actions);
            toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                boolean restoring;
                @Override public void onCheckedChanged(CompoundButton button,boolean checked) {
                    if(restoring) return;
                    try { graph.plugins.setEnabled(plugin.id(),checked); }
                    catch(Exception e) { restoring=true; button.setChecked(graph.plugins.enabled(plugin.id())); restoring=false; UiKit.toast(PluginsActivity.this,"插件设置未保存，请重试"); }
                }
            }); page.addView(card,UiKit.matchWrap()); UiKit.gap(page,12);
        }
        page.addView(UiKit.tonalButton(this,"浏览全部手机技能",DshIcons.Name.UP_RIGHT,()->UiKit.open(this,com.agentruntime.ui.SkillsActivity.class)),UiKit.matchWrap());
        UiKit.gap(page,14); page.addView(UiKit.text(this,"新启用的工具在下一次发送时生效；停用会停止当前调用。",12,UiKit.MUTED));
    }
}
