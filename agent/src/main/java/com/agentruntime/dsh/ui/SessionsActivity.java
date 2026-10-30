package com.agentruntime.dsh.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import com.agentruntime.AgentApp;
import com.agentruntime.dsh.DshGraph;
import com.agentruntime.dsh.runtime.DshController;
import com.agentruntime.dsh.store.SessionStore;
import java.text.DateFormat;
import java.util.Date;

public final class SessionsActivity extends Activity {
    private DshGraph graph;
    @Override public void onCreate(Bundle state) { super.onCreate(state); graph=AgentApp.get().dsh(); render(); }
    private void render() {
        LinearLayout page=UiKit.page(this,"你的会话","随时回来继续。消息与工具记录保存在这部手机，最近 100 个会话显示在这里。");
        page.addView(UiKit.button(this,"＋  开始新对话",true,()->open(graph.sessions.createSession().id)),UiKit.matchWrap()); UiKit.gap(page,24);
        for(SessionStore.Session session:graph.sessions.sessions()) {
            LinearLayout card=UiKit.card(this); TextView title=UiKit.bold(this,UiKit.visible(session.title),17,UiKit.INK); card.addView(title);
            UiKit.gap(card,4); String date=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(session.updated));
            DshController.View active=graph.controller.active(); card.addView(UiKit.text(this,date+(active!=null&&active.session.equals(session.id)?"  ·  正在运行":""),12,UiKit.MUTED)); UiKit.gap(card,14);
            LinearLayout actions=UiKit.row(this); Button resume=UiKit.button(this,"继续对话  ↗",false,()->open(session.id)); actions.addView(resume,new LinearLayout.LayoutParams(0,-2,1));
            Button more=UiKit.button(this,"···",false,()->menu(session)); more.setContentDescription("管理会话 "+UiKit.visible(session.title)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(UiKit.dp(this,52),UiKit.dp(this,48)); p.setMarginStart(UiKit.dp(this,10)); actions.addView(more,p); card.addView(actions);
            page.addView(card,UiKit.matchWrap()); UiKit.gap(page,12);
        }
    }
    private void open(String id) { startActivity(new Intent(this,DshActivity.class).putExtra("session",id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish(); }
    private void menu(SessionStore.Session session) {
        new AlertDialog.Builder(this).setTitle(UiKit.visible(session.title)).setItems(new String[]{"重命名","删除会话"},(dialog,index)->{
            if(index==0) {
                EditText title=UiKit.input(this,"会话名称",false); title.setText(session.title); title.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
                new AlertDialog.Builder(this).setTitle("重命名").setView(title).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
                    try { graph.sessions.rename(session.id,title.getText().toString()); render(); } catch(Exception e) { UiKit.toast(this,"名称需要 1–80 个字符"); }
                }).show();
            } else new AlertDialog.Builder(this).setTitle("删除这段会话？").setMessage("消息和工具记录将从本机删除，工作区文件保留。")
                    .setNegativeButton("保留",null).setPositiveButton("删除",(d,w)->{
                        DshController.View active=graph.controller.active();
                        if(active!=null&&active.session.equals(session.id)) { UiKit.toast(this,"请先停止当前任务再删除"); return; }
                        try {
                            graph.sessions.delete(session.id); getSharedPreferences("com.agentruntime.dsh.ui.DshActivity",0).edit().remove("draft_"+session.id).apply(); render();
                        } catch(Exception e) { UiKit.toast(this,"请先停止当前任务再删除"); }
                    }).show();
        }).show();
    }
}
