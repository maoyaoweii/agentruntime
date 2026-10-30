package com.agentruntime.dsh.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import com.agentruntime.AgentApp;
import com.agentruntime.dsh.DshGraph;
import com.agentruntime.dsh.runtime.DshController;
import com.agentruntime.dsh.store.SessionStore;
import java.text.DateFormat;
import java.util.Date;
import java.time.LocalDate;
import java.time.ZoneId;

public final class SessionsActivity extends Activity {
    private DshGraph graph;
    @Override public void onCreate(Bundle state) { super.onCreate(state); graph=AgentApp.get().dsh(); render(); }
    private void render() {
        LinearLayout page=UiKit.page(this,"你的会话","随时回来继续");
        Button create=UiKit.button(this,"开始新对话",true,()->open(graph.sessions.createSession().id));
        create.setCompoundDrawablesRelative(new DshIcons(this,DshIcons.Name.PLUS,UiKit.WHITE,20,false),null,null,null); create.setCompoundDrawablePadding(UiKit.dp(this,8));
        page.addView(create,UiKit.matchWrap()); UiKit.gap(page,18);
        for(SessionStore.Session session:graph.sessions.sessions()) {
            LinearLayout card=UiKit.card(this); UiKit.pad(card,14,12,14,12);
            LinearLayout row=UiKit.row(this); row.addView(UiKit.iconBadge(this,DshIcons.Name.SESSIONS),new LinearLayout.LayoutParams(UiKit.dp(this,42),UiKit.dp(this,42)));
            LinearLayout labels=UiKit.column(this); LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,-2,1); labelParams.setMarginStart(UiKit.dp(this,12));
            TextView title=UiKit.bold(this,UiKit.visible(session.title),16,UiKit.INK); title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END); labels.addView(title);
            DshController.View active=graph.controller.active(); labels.addView(UiKit.text(this,formatDate(session.updated)+(active!=null&&active.session.equals(session.id)?"  ·  正在运行":""),12,UiKit.MUTED));
            row.addView(labels,labelParams); row.addView(UiKit.iconButton(this,DshIcons.Name.MORE,"管理会话 "+UiKit.visible(session.title),()->menu(session)),new LinearLayout.LayoutParams(UiKit.dp(this,48),UiKit.dp(this,48))); card.addView(row);
            LinearLayout actions=UiKit.row(this); View spacer=new View(this); actions.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
            Button resume=UiKit.tonalButton(this,"继续对话",DshIcons.Name.UP_RIGHT,()->open(session.id)); actions.addView(resume,new LinearLayout.LayoutParams(-2,UiKit.dp(this,48))); card.addView(actions);
            card.setOnClickListener(v->open(session.id)); card.setFocusable(true); card.setContentDescription("会话 "+UiKit.visible(session.title)+"，继续对话");
            page.addView(card,UiKit.matchWrap()); UiKit.gap(page,12);
        }
    }
    private String formatDate(long timestamp) {
        Date date=new Date(timestamp); LocalDate day=date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate(),today=LocalDate.now();
        String prefix=day.equals(today)?"今天":day.equals(today.minusDays(1))?"昨天":DateFormat.getDateInstance(DateFormat.SHORT).format(date);
        return prefix+" "+DateFormat.getTimeInstance(DateFormat.SHORT).format(date);
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
