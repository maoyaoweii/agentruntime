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
    private Button connection;
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
        boolean wide=DshNavigation.isWide(this);
        LinearLayout root=UiKit.column(this); root.setBackgroundColor(UiKit.BG);
        root.setFocusableInTouchMode(true); root.requestFocus();
        LinearLayout top=UiKit.row(this); UiKit.pad(top,20,12,16,6);
        TextView mark=UiKit.title(this,"ds",23); mark.setTextColor(UiKit.WHITE); mark.setGravity(Gravity.CENTER); mark.setBackground(UiKit.shape(this,UiKit.BLUE,0,12));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); top.addView(mark,new LinearLayout.LayoutParams(UiKit.dp(this,42),UiKit.dp(this,42)));
        LinearLayout heading=UiKit.column(this); LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,-2,1); hp.setMarginStart(UiKit.dp(this,12));
        heading.addView(UiKit.title(this,"DSH Mobile",20)); subtitle=UiKit.text(this,"你的随身智能助手",11,UiKit.MUTED); subtitle.setMaxLines(1); heading.addView(subtitle); top.addView(heading,hp);
        ImageButton fresh=UiKit.iconButton(this,DshIcons.Name.PLUS,"新建对话",this::newSession); fresh.setBackground(UiKit.ripple(this,UiKit.WHITE,UiKit.LINE,24));
        top.addView(fresh,new LinearLayout.LayoutParams(UiKit.dp(this,48),UiKit.dp(this,48))); root.addView(top);
        LinearLayout connectionRow=UiKit.row(this); UiKit.pad(connectionRow,20,0,20,0);
        connection=UiKit.button(this,"连接模型",false,()->UiKit.open(this,DshSettingsActivity.class)); connection.setId(com.agentruntime.agent.R.id.dsh_connection);
        connection.setTextSize(12); connection.setMaxWidth(UiKit.dp(this,260)); connection.setSingleLine(true); connection.setEllipsize(android.text.TextUtils.TruncateAt.END);
        connection.setCompoundDrawablePadding(UiKit.dp(this,6)); connection.setBackground(UiKit.ripple(this,UiKit.WHITE,UiKit.LINE,24));
        connectionRow.addView(connection,new LinearLayout.LayoutParams(-2,UiKit.dp(this,48))); root.addView(connectionRow);
        status=UiKit.text(this,"",12,UiKit.MUTED); status.setId(com.agentruntime.agent.R.id.dsh_status); UiKit.pad(status,22,2,22,6); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(status);
        ScrollView scroll=new ScrollView(this); scroll.setId(com.agentruntime.agent.R.id.dsh_transcript); scroll.setFillViewport(true); scroll.setClipToPadding(false); UiKit.pad(scroll,20,4,20,8);
        LinearLayout transcript=UiKit.column(this); scroll.addView(transcript,new ScrollView.LayoutParams(-1,-2)); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        renderer=new ChatRenderer(this,transcript,scroll,this::suggest);
        LinearLayout bottom=UiKit.column(this); UiKit.pad(bottom,16,6,16,5); bottom.setBackgroundColor(UiKit.BG);
        LinearLayout composeCard=UiKit.column(this); UiKit.pad(composeCard,8,8,8,8); composeCard.setBackground(UiKit.shape(this,UiKit.WHITE,UiKit.LINE,22));
        LinearLayout compose=UiKit.row(this);
        composer=UiKit.input(this,"发消息，或让手机帮你做点事…",true); composer.setId(com.agentruntime.agent.R.id.dsh_composer); composer.setBackground(UiKit.shape(this,0xfff7f9ff,UiKit.LINE,12));
        composer.setContentDescription("消息"); composer.setTextSize(14);
        composer.setMaxLines(4); composer.setMinLines(1); composer.setSaveEnabled(false); composer.setGravity(Gravity.TOP|Gravity.START);
        composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(SessionStore.MAX_INPUT)});
        compose.addView(composer,new LinearLayout.LayoutParams(0,-2,1)); send=UiKit.button(this,"发送",true,this::sendOrStop); send.setId(com.agentruntime.agent.R.id.dsh_send);
        LinearLayout.LayoutParams sendParams=new LinearLayout.LayoutParams(UiKit.dp(this,68),UiKit.dp(this,50)); sendParams.setMarginStart(UiKit.dp(this,7));
        LinearLayout choices=UiKit.row(this);
        mode=UiKit.tonalButton(this,"手机助手",null,()->{ tools=!tools; refresh(); }); mode.setId(com.agentruntime.agent.R.id.dsh_mode); mode.setTextSize(12);
        Button files=UiKit.tonalButton(this,"工作区",null,()->UiKit.open(this,WorkspaceActivity.class)); files.setTextColor(UiKit.INK); files.setTextSize(12);
        files.setId(com.agentruntime.agent.R.id.dsh_workspace_shortcut); files.setContentDescription("工作区"); UiKit.iconLabel(files,"工作区",DshIcons.Name.FOLDER,UiKit.MUTED,19);
        if(wide) {
            LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(UiKit.dp(this,112),UiKit.dp(this,48)); mp.setMarginStart(UiKit.dp(this,7)); compose.addView(mode,mp);
            LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(UiKit.dp(this,96),UiKit.dp(this,48)); fp.setMarginStart(UiKit.dp(this,7)); compose.addView(files,fp);
            compose.addView(send,sendParams); composeCard.addView(compose);
        } else {
            compose.addView(send,sendParams); composeCard.addView(compose); UiKit.gap(composeCard,8);
            choices.addView(mode,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1));
            LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1); fp.setMarginStart(UiKit.dp(this,8)); choices.addView(files,fp); composeCard.addView(choices);
        }
        bottom.addView(composeCard); root.addView(bottom);
        View nav=DshNavigation.mount(this,root,DshNavigation.Section.CHAT);
        Runnable updateCompact=()->{
            if(root.getHeight()==0) return;
            boolean compact=composer.hasFocus()&&root.getHeight()<UiKit.dp(this,360);
            int visibility=compact?View.GONE:View.VISIBLE;
            setVisible(nav,wide?View.VISIBLE:visibility); setVisible(choices,visibility); setVisible(top,visibility); setVisible(connectionRow,visibility);
        };
        root.addOnLayoutChangeListener((view,left,topEdge,right,bottomEdge,oldLeft,oldTop,oldRight,oldBottom)->updateCompact.run());
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus,newFocus)->updateCompact.run());
    }
    private static void setVisible(View view,int visibility) { if(view.getVisibility()!=visibility) view.setVisibility(visibility); }
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
        subtitle.setText("新对话".equals(current.title)?"你的随身智能助手":UiKit.visible(current.title));
        DshController.View active=graph.controller.active(); boolean running=active!=null;
        boolean configured=graph.provider.configured(); String model=configured?graph.provider.model():"连接模型";
        connection.setText(model); connection.setContentDescription(configured?"已保存模型配置："+model+"，点击设置":"连接模型，点击设置");
        android.graphics.drawable.GradientDrawable dot=new android.graphics.drawable.GradientDrawable(); dot.setShape(android.graphics.drawable.GradientDrawable.OVAL); dot.setColor(configured?UiKit.LINK:UiKit.MUTED); dot.setBounds(0,0,UiKit.dp(this,7),UiKit.dp(this,7));
        connection.setCompoundDrawablesRelative(dot,null,new DshIcons(this,DshIcons.Name.RIGHT,UiKit.MUTED,15,false),null);
        String message=running?(active.session.equals(session)?active.status:"另一对话正在运行，可停止后再发送"):graph.controller.lastError();
        status.setText(message); status.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);
        send.setText(running?"停止":"发送"); String modeLabel=tools?"手机助手":"纯对话"; int modeColor=tools?UiKit.LINK:UiKit.MUTED;
        UiKit.iconLabel(mode,modeLabel,DshIcons.Name.PHONE,modeColor,19); mode.setTextColor(modeColor); mode.setEnabled(!running);
        mode.setContentDescription(tools?"手机助手模式，点击切换到纯对话":"纯对话模式，点击切换到手机助手");
        try { renderer.show(session,graph.sessions.events(session),active!=null&&active.session.equals(session)?active:null); }
        catch(Exception e) { status.setText("读取会话失败，请重新打开应用"); status.setVisibility(View.VISIBLE); }
    }
    private void saveDraft() {
        if(composer!=null) getPreferences(0).edit().putString("session",session).putBoolean("tools",tools).putString("draft_"+session,composer.getText().toString()).apply();
    }
    @Override protected void onResume() {
        super.onResume();
        com.agentruntime.ui.AppWindow.track(this);
        if(graph.sessions.session(session)==null) { session=graph.sessions.createSession().id; composer.setText(""); renderer.reset(); }
        graph.controller.observe(update); refresh();
    }
    @Override protected void onPause() { saveDraft(); com.agentruntime.ui.AppWindow.untrack(this); graph.controller.remove(update); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("session",session); out.putString("draft",composer.getText().toString()); out.putBoolean("tools",tools); super.onSaveInstanceState(out); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); String target=intent.getStringExtra("session");
        if(graph.sessions.session(target)!=null&&!target.equals(session)) { saveDraft(); session=target; composer.setText(getPreferences(0).getString("draft_"+session,"")); renderer.reset(); refresh(); }
    }
}
