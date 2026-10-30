package com.agentruntime.dsh.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.agentruntime.AgentApp;
import com.agentruntime.dsh.DshGraph;
import com.agentruntime.dsh.runtime.DshController;
import com.agentruntime.dsh.runtime.DshTurnService;
import com.agentruntime.dsh.store.SessionStore;
import java.util.List;

public final class DshActivity extends Activity {
    private DshGraph graph;
    private String session;
    private boolean tools=true;
    private TextView subtitle,status;
    private EditText composer;
    private Button send,mode;
    private ChatRenderer renderer;
    private final Runnable update=this::refresh;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); graph=AgentApp.get().dsh();
        session=state==null?getIntent().getStringExtra("session"):state.getString("session");
        if(session==null) session=getPreferences(0).getString("session",null);
        if(graph.sessions.session(session)==null) {
            List<SessionStore.Session> all=graph.sessions.sessions(); session=all.isEmpty()?graph.sessions.createSession().id:all.get(0).id;
        }
        tools=state==null?getPreferences(0).getBoolean("tools",true):state.getBoolean("tools",true);
        build();
        if(state!=null) composer.setText(state.getString("draft",""));
        else composer.setText(getPreferences(0).getString("draft_"+session,""));
    }
    private void build() {
        LinearLayout root=UiKit.column(this); root.setBackgroundColor(UiKit.BG);
        LinearLayout top=UiKit.row(this); UiKit.pad(top,20,12,16,10);
        TextView mark=UiKit.bold(this,"ds",23,UiKit.WHITE); mark.setGravity(Gravity.CENTER); mark.setBackground(UiKit.shape(this,UiKit.BLUE,0,16));
        mark.setContentDescription("DSH Mobile"); top.addView(mark,new LinearLayout.LayoutParams(UiKit.dp(this,46),UiKit.dp(this,46)));
        LinearLayout heading=UiKit.column(this); LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,-2,1); hp.setMarginStart(UiKit.dp(this,12));
        heading.addView(UiKit.bold(this,"DSH Mobile",21,UiKit.INK)); subtitle=UiKit.text(this,"你的随身智能助手",11,UiKit.MUTED); subtitle.setMaxLines(1); heading.addView(subtitle); top.addView(heading,hp);
        Button fresh=UiKit.button(this,"＋",false,this::newSession); fresh.setTextSize(23); fresh.setContentDescription("新建对话"); top.addView(fresh,new LinearLayout.LayoutParams(UiKit.dp(this,48),UiKit.dp(this,48))); root.addView(top);
        status=UiKit.text(this,"",12,UiKit.MUTED); UiKit.pad(status,22,4,22,9); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(status);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); UiKit.pad(scroll,20,6,20,12);
        LinearLayout transcript=UiKit.column(this); scroll.addView(transcript,new ScrollView.LayoutParams(-1,-2)); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        renderer=new ChatRenderer(this,transcript,scroll,this::suggest);
        LinearLayout bottom=UiKit.column(this); UiKit.pad(bottom,16,8,16,8); bottom.setBackgroundColor(UiKit.BG);
        LinearLayout compose=UiKit.row(this); compose.setBackground(UiKit.shape(this,UiKit.WHITE,UiKit.LINE,22)); UiKit.pad(compose,8,7,7,7);
        composer=UiKit.input(this,"发消息，或让手机帮你做点事…",true); composer.setId(com.agentruntime.agent.R.id.dsh_composer); composer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        composer.setMaxLines(4); composer.setMinLines(1); composer.setSaveEnabled(false); composer.setGravity(Gravity.TOP|Gravity.START);
        composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(SessionStore.MAX_INPUT)});
        compose.addView(composer,new LinearLayout.LayoutParams(0,-2,1)); send=UiKit.button(this,"发送",true,this::sendOrStop); send.setId(com.agentruntime.agent.R.id.dsh_send); compose.addView(send,new LinearLayout.LayoutParams(UiKit.dp(this,66),UiKit.dp(this,50))); bottom.addView(compose);
        LinearLayout choices=UiKit.row(this); UiKit.gap(bottom,8);
        mode=UiKit.button(this,"",false,()->{ tools=!tools; refresh(); }); mode.setTextSize(12); choices.addView(mode,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1));
        Button files=UiKit.button(this,"工作区 ↗",false,()->UiKit.open(this,WorkspaceActivity.class)); files.setTextSize(12); LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1); fp.setMarginStart(UiKit.dp(this,8)); choices.addView(files,fp); bottom.addView(choices); root.addView(bottom);
        LinearLayout nav=UiKit.row(this); UiKit.pad(nav,16,6,16,10); nav.setBackgroundColor(UiKit.WHITE);
        addTab(nav,"对话",true,()->renderer.toBottom()); addTab(nav,"会话",false,()->UiKit.open(this,SessionsActivity.class));
        addTab(nav,"插件",false,()->UiKit.open(this,PluginsActivity.class)); addTab(nav,"设置",false,()->UiKit.open(this,DshSettingsActivity.class)); root.addView(nav);
        setContentView(root);
    }
    private void addTab(LinearLayout nav,String label,boolean selected,Runnable action) {
        Button v=UiKit.button(this,label,false,action); v.setTextColor(selected?UiKit.BLUE:UiKit.MUTED); v.setBackground(UiKit.shape(this,selected?0xffeef1ff:UiKit.WHITE,0,14));
        nav.addView(v,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1));
    }
    private void suggest(String text) { composer.setText(text); composer.setSelection(text.length()); composer.requestFocus(); }
    private void newSession() {
        saveDraft(); session=graph.sessions.createSession().id; composer.setText(""); renderer.reset(); refresh();
    }
    private void sendOrStop() {
        DshController.View active=graph.controller.active();
        if(active!=null) { graph.controller.cancel(active.id,"USER_CANCELLED"); return; }
        String text=composer.getText().toString().trim(); if(text.isEmpty()) return;
        if(!graph.provider.configured()) { UiKit.open(this,DshSettingsActivity.class); return; }
        String id=null;
        try {
            id=graph.controller.reserve(session,text,tools);
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},23);
            startForegroundService(new Intent(this,DshTurnService.class).putExtra(DshTurnService.JOB,id));
            composer.setText(""); saveDraft(); refresh();
        } catch(Exception e) {
            if(id!=null) graph.controller.abandon(id);
            UiKit.toast(this,e instanceof IllegalArgumentException||e instanceof IllegalStateException?e.getMessage():"无法读取模型配置，请在设置中重新保存密钥");
        }
    }
    private void refresh() {
        if(graph==null||isDestroyed()) return;
        SessionStore.Session current=graph.sessions.session(session); if(current==null) return;
        subtitle.setText(UiKit.visible(current.title));
        DshController.View active=graph.controller.active(); boolean running=active!=null;
        status.setText(running?(active.session.equals(session)?active.status:"另一对话正在运行，可停止后再发送"):
                (!graph.controller.lastError().isEmpty()?graph.controller.lastError():graph.provider.configured()?graph.provider.model()+"  ·  本机保存会话":"先在设置中连接模型，即可开始对话"));
        send.setText(running?"停止":"发送"); mode.setText(tools?"◉  手机助手":"○  纯对话"); mode.setTextColor(tools?UiKit.BLUE:UiKit.MUTED); mode.setEnabled(!running);
        try { renderer.show(session,graph.sessions.events(session),active!=null&&active.session.equals(session)?active:null); }
        catch(Exception e) { status.setText("读取会话失败，请重新打开应用"); }
    }
    private void saveDraft() {
        if(composer!=null) getPreferences(0).edit().putString("session",session).putBoolean("tools",tools).putString("draft_"+session,composer.getText().toString()).apply();
    }
    @Override protected void onResume() {
        super.onResume();
        if(graph.sessions.session(session)==null) { session=graph.sessions.createSession().id; composer.setText(""); renderer.reset(); }
        graph.controller.observe(update); refresh();
    }
    @Override protected void onPause() { saveDraft(); graph.controller.remove(update); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("session",session); out.putString("draft",composer.getText().toString()); out.putBoolean("tools",tools); super.onSaveInstanceState(out); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); String target=intent.getStringExtra("session");
        if(graph.sessions.session(target)!=null&&!target.equals(session)) { saveDraft(); session=target; composer.setText(getPreferences(0).getString("draft_"+session,"")); renderer.reset(); refresh(); }
    }
}
